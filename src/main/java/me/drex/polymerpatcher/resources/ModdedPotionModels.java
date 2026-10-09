package me.drex.polymerpatcher.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps item model files readable when a mod picks a model by a potion the client has never heard of.
 * <p>
 * Ice Caves replaces the game's potion, splash potion, lingering potion and tipped arrow item files with a
 * choice made on the potion: if it is {@code ice_caves:freezing}, use its own picture. A client without Ice Caves
 * cannot read that name, so it threw the whole file away, and every potion and tipped arrow in the game went
 * untextured. It could never have matched for that client anyway: Polymer sends a modded potion to it as a plain
 * coloured potion carrying only its name.
 * <p>
 * So each choice naming a potion a client does not know is taken out of the file, and its model is kept as an
 * item file of its own. A potion of that kind is pointed at that file as it is sent, which no client needs to
 * understand anything for - the freezing potion still looks like the freezing potion.
 */
public final class ModdedPotionModels {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The item file each modded potion is drawn with, by the item it is on and the potion. */
    private static final Map<String, Identifier> MODELS = new ConcurrentHashMap<>();
    private static volatile boolean listening;

    private ModdedPotionModels() {
    }

    /** The given item file, with every choice on a potion a client does not know taken out. */
    static byte[] clean(Identifier file, byte[] data, ResourcePackBuilder pack) {
        String path = file.getPath();
        if (!path.startsWith("items/") || !path.endsWith(".json")) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.contains("potion_contents")) {
            return data;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            Identifier item = Identifier.fromNamespaceAndPath(file.getNamespace(), path.substring("items/".length(), path.length() - ".json".length()));
            List<String> taken = new ArrayList<>();
            JsonElement model = root.get("model");
            if (model == null) {
                return data;
            }
            JsonElement cleaned = clean(model, item, pack, taken);
            if (taken.isEmpty()) {
                return data;
            }
            root.add("model", cleaned);
            listen();
            PolymerPatcher.LOGGER.info("assets/{}/{} chose its model by {} potion(s) a client without the mod cannot read, which "
                + "made the client discard the whole file; those choices now have files of their own: {}",
                file.getNamespace(), path, taken.size(), taken);
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return data;
        }
    }

    /** One model, cleaned, with every model nested inside it cleaned too. */
    private static JsonElement clean(JsonElement element, Identifier item, ResourcePackBuilder pack, List<String> taken) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject model = element.getAsJsonObject();
        String type = name(model.get("type"));

        if ("select".equals(type) && "component".equals(name(model.get("property")))
            && "potion_contents".equals(name(model.get("component"))) && model.has("cases")) {
            JsonArray kept = new JsonArray();
            for (JsonElement entry : model.getAsJsonArray("cases")) {
                if (!entry.isJsonObject() || !entry.getAsJsonObject().has("model")) {
                    kept.add(entry);
                    continue;
                }
                JsonObject choice = entry.getAsJsonObject();
                JsonElement when = choice.get("when");
                List<JsonElement> values = new ArrayList<>();
                if (when != null && when.isJsonArray()) {
                    when.getAsJsonArray().forEach(values::add);
                } else if (when != null) {
                    values.add(when);
                }
                JsonArray readable = new JsonArray();
                for (JsonElement value : values) {
                    Identifier potion = potionOf(value);
                    if (potion != null && !Identifier.DEFAULT_NAMESPACE.equals(potion.getNamespace())) {
                        Identifier own = Identifier.fromNamespaceAndPath(PolymerPatcher.MOD_ID,
                            "potion/" + item.getNamespace() + "/" + item.getPath() + "/" + potion.getNamespace() + "/" + potion.getPath());
                        JsonObject file = new JsonObject();
                        file.add("model", clean(choice.get("model").deepCopy(), item, pack, taken));
                        pack.addData("assets/" + own.getNamespace() + "/items/" + own.getPath() + ".json", GSON.toJson(file).getBytes(StandardCharsets.UTF_8));
                        MODELS.put(item + "|" + potion, own);
                        taken.add(potion.toString());
                    } else {
                        readable.add(value);
                    }
                }
                if (readable.isEmpty()) {
                    continue;
                }
                choice.add("when", when != null && when.isJsonArray() ? readable : readable.get(0));
                choice.add("model", clean(choice.get("model"), item, pack, taken));
                kept.add(choice);
            }
            if (kept.isEmpty() && model.has("fallback")) {
                // Nothing left to choose between: what it fell back to is the model
                return clean(model.get("fallback"), item, pack, taken);
            }
            model.add("cases", kept);
            if (model.has("fallback")) {
                model.add("fallback", clean(model.get("fallback"), item, pack, taken));
            }
            return model;
        }

        // Every other kind of model, for the choices nested inside it
        for (String key : new String[]{"fallback", "on_true", "on_false"}) {
            if (model.has(key)) {
                model.add(key, clean(model.get(key), item, pack, taken));
            }
        }
        for (String key : new String[]{"cases", "entries"}) {
            if (model.has(key) && model.get(key).isJsonArray()) {
                for (JsonElement entry : model.getAsJsonArray(key)) {
                    if (entry.isJsonObject() && entry.getAsJsonObject().has("model")) {
                        entry.getAsJsonObject().add("model", clean(entry.getAsJsonObject().get("model"), item, pack, taken));
                    }
                }
            }
        }
        if (model.has("models") && model.get("models").isJsonArray()) {
            JsonArray parts = new JsonArray();
            for (JsonElement part : model.getAsJsonArray("models")) {
                parts.add(clean(part, item, pack, taken));
            }
            model.add("models", parts);
        }
        return model;
    }

    /** The potion a choice names - written as its id, or as the potion contents with one. */
    private static @Nullable Identifier potionOf(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return Identifier.tryParse(value.getAsString());
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("potion")) {
            JsonElement potion = value.getAsJsonObject().get("potion");
            return potion.isJsonPrimitive() ? Identifier.tryParse(potion.getAsString()) : null;
        }
        return null;
    }

    private static @Nullable String name(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    /** Points each modded potion that has a file of its own at that file, as it is sent to a client without the mod. */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        PolymerItemUtils.ITEM_MODIFICATION_EVENT.register((original, client, context) -> {
            PotionContents contents = original.get(DataComponents.POTION_CONTENTS);
            if (contents == null || contents.potion().isEmpty()) {
                return client;
            }
            Identifier potion = contents.potion().get().unwrapKey().map(key -> key.identifier()).orElse(null);
            if (potion == null || Identifier.DEFAULT_NAMESPACE.equals(potion.getNamespace())) {
                return client;
            }
            Identifier model = MODELS.get(BuiltInRegistries.ITEM.getKey(original.getItem()) + "|" + potion);
            if (model != null) {
                client.set(DataComponents.ITEM_MODEL, model);
            }
            return client;
        });
    }
}
