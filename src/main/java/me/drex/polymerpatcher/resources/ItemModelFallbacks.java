package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a readable stand-in for a mod's item definition under a separate identifier.
 * <p>
 * Some item definitions need the mod's code to be read at all - a cave tablet tinted by its biome, a
 * gauntlet or a shield drawn by the mod's own renderer. A client without the mod cannot read those, so the
 * pack carries a plain version. It used to write that plain version over the mod's file, and a client
 * that <i>does</i> have the mod loads the server's pack on top of its own assets, so it lost the tint and
 * the renderer too - on the very items it was now being handed for real.
 * <p>
 * So the plain version is written under this mod's namespace instead and only a stand-in is pointed at the
 * plain one. The raw code-only definition is omitted from the generated server pack: a vanilla client tries
 * to decode every definition in an enabled pack and would otherwise log an error even when no stack uses it.
 * A client with the mod still resolves the original definition from its installed mod, while a client without
 * it is sent a stand-in which names the readable copy.
 * <p>
 * Which definitions get a copy is worked out from the mod's own file, so it is known from the moment the
 * server starts - before the pack is built, and whether or not it is built here at all.
 */
public final class ItemModelFallbacks {
    private ItemModelFallbacks() {
    }

    /** Definitions an exact compat converter translates in place instead of replacing statically. */
    private static final Set<Identifier> CONVERTED_IN_PLACE = ConcurrentHashMap.newKeySet();

    /** Each item model asked about, and the copy a stand-in should carry instead - or none. */
    private static final Map<Identifier, Optional<Identifier>> DECIDED = new ConcurrentHashMap<>();

    /** Where the readable copy of this item's definition lives. */
    public static Identifier standInId(String namespace, String itemPath) {
        return PolymerPatcher.id("stand_in/" + namespace + "/" + itemPath);
    }

    public static String definitionPath(Identifier id) {
        return "assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json";
    }

    /** Whether the pack converts this definition somewhere else, and so has no copy for it. */
    public static boolean convertedInPlace(String namespace, String itemPath) {
        return CONVERTED_IN_PLACE.contains(Identifier.fromNamespaceAndPath(namespace, itemPath));
    }

    public static void registerConvertedInPlace(Identifier itemModel) {
        CONVERTED_IN_PLACE.add(itemModel);
    }

    /** Written down by the pack as it makes a copy, so the two can never disagree about one it has made. */
    static void record(String namespace, String itemPath) {
        DECIDED.put(Identifier.fromNamespaceAndPath(namespace, itemPath), Optional.of(standInId(namespace, itemPath)));
    }

    /**
     * The item model a stand-in should carry in place of this one, or null where the mod's own definition
     * is readable by everybody.
     */
    public static @Nullable Identifier standInFor(Identifier model) {
        Optional<Identifier> known = DECIDED.get(model);
        if (known == null) {
            known = decide(model);
            if (known == null) {
                // Could not be read this time; asked again next time rather than remembered as "none"
                return null;
            }
            DECIDED.put(model, known);
        }
        return known.orElse(null);
    }

    private static @Nullable Optional<Identifier> decide(Identifier model) {
        String namespace = model.getNamespace();
        if (!PolymerPatcher.PATCHED_MODS.contains(namespace) || convertedInPlace(namespace, model.getPath())) {
            return Optional.empty();
        }
        try {
            IoSupplier<InputStream> asset = ResourceHelper.getAsset(namespace, "items/" + model.getPath() + ".json");
            if (asset == null) {
                return Optional.empty();
            }
            try (InputStream in = asset.get()) {
                JsonElement root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (root.isJsonObject() && needsModCode(root.getAsJsonObject().get("model"))) {
                    return Optional.of(standInId(namespace, model.getPath()));
                }
                return Optional.empty();
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read the item definition of {}", model, e);
            return null;
        }
    }

    /** Whether a definition names a model, property or tint type that only the mod's own code can read. */
    /**
     * The same definition with everything only the mod can read taken out of it, or null where taking
     * it out leaves nothing.
     * <p>
     * Not every unreadable definition is an unreadable <em>item</em>. A mod that swaps an item's model
     * while it is in use writes an ordinary switch with ordinary models in it, and names the switch
     * after something of its own - Alex's Caves' candy cane hook says "which model while cast", and
     * both models it chooses between are plain files any client can read. Thrown away whole, that item
     * fell back on a shape read out of the mod's drawing code, which for the hook is the hook itself:
     * the thing on the end of the line, baked at the size a thrown hook is drawn at, and invisibly
     * small in a hand.
     * <p>
     * So the switch is taken out and what it would have shown by default is kept. An item that really
     * does need the mod - one drawn by its renderer rather than chosen by it - has nothing underneath
     * and still comes back null, which leaves those exactly as they were.
     */
    public static @Nullable JsonElement withoutModCode(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            return needsModCode(element) ? null : element;
        }

        JsonObject object = element.getAsJsonObject();
        if (isMods(object.get("type"))) {
            // Drawn by the mod itself. There is nothing underneath to keep
            return null;
        }

        if (isMods(object.get("property"))) {
            // A switch on something only the mod knows. What it shows when nothing else applies is the
            // honest answer for a client that cannot ask the question
            JsonElement otherwise = object.has("fallback") ? object.get("fallback") : object.get("on_false");
            return withoutModCode(otherwise);
        }

        JsonObject kept = new JsonObject();
        for (var entry : object.entrySet()) {
            JsonElement child = withoutModCode(entry.getValue());
            if (child == null && needsModCode(entry.getValue())) {
                return null;
            }
            kept.add(entry.getKey(), child == null ? entry.getValue() : child);
        }
        return kept;
    }

    private static boolean isMods(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return false;
        }
        String id = value.getAsString();
        return id.indexOf(':') >= 0 && !id.startsWith(Identifier.DEFAULT_NAMESPACE + ":");
    }

    public static boolean needsModCode(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (needsModCode(child)) return true;
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }

        JsonObject object = element.getAsJsonObject();
        for (String key : List.of("type", "property")) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String id = value.getAsString();
                if (id.indexOf(':') >= 0 && !id.startsWith(Identifier.DEFAULT_NAMESPACE + ":")) {
                    return true;
                }
            }
        }
        for (var entry : object.entrySet()) {
            if (needsModCode(entry.getValue())) return true;
        }
        return false;
    }
}
