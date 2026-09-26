package me.drex.polymerpatcher.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the pack's blockstate files for the game's own blocks in terms a client without the mods can read.
 * <p>
 * A mod may hang a property of its own on a block the game ships - The Sift puts an {@code ichorlogged}
 * beside every {@code waterlogged} - and everything that writes a blockstate file on this server writes it
 * from this server's blocks. Polymer names its carrier states with every property they have, and
 * {@link CarrierBlockStates} gives the rest of the states back their models the same way, so the file that
 * reaches a client describes oak leaves by a property it has never heard of.
 * <p>
 * A client does not skip a key like that. It throws, and the whole file goes with it - every state of the
 * block, carriers and real ones alike. So once everything else has written its file, each one is put into
 * the client's terms: a property the client does not have is taken out of every key and every multipart
 * condition. A rule that only applies at the property's default applies to every state the client can be
 * in, because the client is always at that default; a rule that needs any other value can never apply to
 * a client at all and is dropped.
 * <p>
 * Nothing here names a mod or a property. Which properties a client has is read out of the pristine
 * 26.2 block map that {@link BlockSyncCheck} already carries.
 */
public final class ClientBlockStateKeys {

    private ClientBlockStateKeys() {
    }

    private static final String PREFIX = "assets/minecraft/blockstates/";

    /** A multipart condition that can never be met by a client, and so a part that can be dropped. */
    private static final JsonObject NEVER = new JsonObject();

    /** A multipart condition every client meets, and so one that can be left off. */
    private static final JsonObject ALWAYS = new JsonObject();

    public static void sanitize(ResourcePackBuilder builder) {
        int files = 0;
        int dropped = 0;

        for (Map.Entry<ResourceKey<Block>, Block> entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            Set<String> known = BlockSyncCheck.clientPropertiesOf(id.toString());
            if (known == null) {
                // Not a block the client has: whatever its file says, the client never loads it
                continue;
            }

            Map<String, String> defaults = BlockSyncCheck.valueNames(entry.getValue().defaultBlockState());
            Set<String> unknown = new HashSet<>(defaults.keySet());
            unknown.removeAll(known);
            if (unknown.isEmpty()) {
                continue;
            }

            try {
                int[] count = new int[1];
                if (sanitizeFile(builder, PREFIX + id.getPath() + ".json", unknown, defaults, count)) {
                    files++;
                    dropped += count[0];
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not put the blockstate file of {} into a client's terms", id, e);
            }
        }

        if (files > 0) {
            PolymerPatcher.LOGGER.info("Took properties no client has out of {} blockstate file(s), dropping {} rule(s) "
                + "that could only apply to states that exist on this server alone", files, dropped);
        }
    }

    private static boolean sanitizeFile(ResourcePackBuilder builder, String path, Set<String> unknown,
                                        Map<String, String> defaults, int[] dropped) {
        byte[] packed = builder.getDataOrSource(path);
        if (packed == null) {
            return false;
        }
        JsonElement parsed = JsonParser.parseString(new String(packed, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            return false;
        }

        JsonObject file = parsed.getAsJsonObject();
        boolean changed = false;

        if (file.has("variants") && file.get("variants").isJsonObject()) {
            JsonObject readable = new JsonObject();
            for (Map.Entry<String, JsonElement> variant : file.getAsJsonObject("variants").entrySet()) {
                String key = readableKey(variant.getKey(), unknown, defaults);
                if (key == null) {
                    dropped[0]++;
                    changed = true;
                    continue;
                }
                if (!key.equals(variant.getKey())) {
                    changed = true;
                }
                // Two keys can only become one when they differed in nothing but a property the client
                // does not have, and at its default - the first says it as well as the second
                if (readable.has(key)) {
                    changed = true;
                    continue;
                }
                readable.add(key, variant.getValue());
            }
            file.add("variants", readable);
        }

        if (file.has("multipart") && file.get("multipart").isJsonArray()) {
            JsonArray readable = new JsonArray();
            for (JsonElement part : file.getAsJsonArray("multipart")) {
                if (!part.isJsonObject() || !part.getAsJsonObject().has("when")) {
                    readable.add(part);
                    continue;
                }
                JsonObject copy = part.getAsJsonObject().deepCopy();
                JsonObject when = readableCondition(copy.get("when"), unknown, defaults);
                if (when == NEVER) {
                    dropped[0]++;
                    changed = true;
                    continue;
                }
                if (when == ALWAYS) {
                    copy.remove("when");
                } else {
                    copy.add("when", when);
                }
                if (!copy.equals(part)) {
                    changed = true;
                }
                readable.add(copy);
            }
            file.add("multipart", readable);
        }

        if (changed) {
            builder.addData(path, file.toString().getBytes(StandardCharsets.UTF_8));
        }
        return changed;
    }

    /**
     * A variant key with the properties no client has taken out, or null where it names one of them at
     * anything but its default and so describes a state no client can be in.
     */
    public static @Nullable String readableKey(String key, Set<String> unknown, Map<String, String> defaults) {
        if (key.isEmpty()) {
            return key;
        }

        StringBuilder readable = new StringBuilder();
        for (String pair : key.split(",")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (unknown.contains(name)) {
                if (equals < 0 || !pair.substring(equals + 1).equals(defaults.get(name))) {
                    return null;
                }
                continue;
            }
            if (!readable.isEmpty()) {
                readable.append(',');
            }
            readable.append(pair);
        }
        return readable.toString();
    }

    /**
     * A multipart condition in the client's terms: {@link #NEVER} where no client can meet it, {@link #ALWAYS}
     * where every client does, and otherwise what is left of it once the properties it cannot have are gone.
     */
    private static JsonObject readableCondition(JsonElement condition, Set<String> unknown, Map<String, String> defaults) {
        if (!condition.isJsonObject()) {
            return condition.isJsonNull() ? ALWAYS : NEVER;
        }
        JsonObject object = condition.getAsJsonObject();

        for (String combine : List.of("OR", "AND")) {
            if (!object.has(combine) || !object.get(combine).isJsonArray()) {
                continue;
            }
            boolean any = combine.equals("OR");
            JsonArray kept = new JsonArray();
            for (JsonElement term : object.getAsJsonArray(combine)) {
                JsonObject readable = readableCondition(term, unknown, defaults);
                if (readable == (any ? ALWAYS : NEVER)) {
                    // One true branch makes an OR true; one false branch makes an AND false
                    return readable;
                }
                if (readable != (any ? NEVER : ALWAYS)) {
                    kept.add(readable);
                }
            }
            if (kept.isEmpty()) {
                return any ? NEVER : ALWAYS;
            }
            JsonObject result = new JsonObject();
            result.add(combine, kept);
            return result;
        }

        JsonObject readable = new JsonObject();
        for (Map.Entry<String, JsonElement> term : object.entrySet()) {
            String name = term.getKey();
            if (!unknown.contains(name)) {
                readable.add(name, term.getValue());
                continue;
            }
            if (!allows(term.getValue().getAsString(), defaults.get(name))) {
                return NEVER;
            }
            // Always met: a client is always at the default of a property it does not have
        }
        return readable.size() == 0 ? ALWAYS : readable;
    }

    /** Whether a multipart value like {@code "false"}, {@code "a|b"} or {@code "!a"} accepts this value. */
    private static boolean allows(String spec, @Nullable String value) {
        boolean negated = spec.startsWith("!");
        Set<String> values = Set.of((negated ? spec.substring(1) : spec).split("\\|"));
        return negated != values.contains(value);
    }
}
