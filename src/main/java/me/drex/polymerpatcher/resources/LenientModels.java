package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import me.drex.polymerpatcher.PolymerPatcher;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a model the way the game reads it, rather than the way the letter of the format says it.
 * <p>
 * The game's own model loader is forgiving: a field it cannot make sense of is dropped and the model
 * is used anyway. The codec these assets are read through is not - it refuses the whole model, and a
 * refusal here does not cost one block, it costs the entire resource pack, because Polymer answers a
 * build that throws by abandoning the pack. Every player then joins with no textures at all.
 * <p>
 * Alex's Caves is the example that found this. Every cave painting inherits
 * {@code alexscaves:block/cave_painting_base}, whose north face reads
 * {@code "cullface": "painting"} - the texture's name pasted where a direction belongs. A real client
 * shrugs, ignores it and draws the painting. This refused it, and with it the pack.
 * <p>
 * So a value that is not a direction is dropped rather than argued with, which is precisely what the
 * game does with it. Nothing else about the model is touched.
 */
public final class LenientModels {

    private LenientModels() {
    }

    private static final Set<String> DIRECTIONS = Set.of("down", "up", "north", "south", "west", "east");

    /**
     * Reads a model, quietly repairing the kinds of mistake the game itself overlooks.
     *
     * @throws RuntimeException if the model is broken in a way the game would not overlook either
     */
    public static ModelAsset read(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        try {
            return ModelAsset.fromJson(json);
        } catch (Throwable first) {
            String repaired = dropUnknownCullfaces(json);
            if (repaired == null) {
                throw first;
            }
            // Only reached when something was actually removed, so a model that fails for any other
            // reason still fails with its original complaint rather than a confusing second one
            return ModelAsset.fromJson(repaired);
        }
    }

    /**
     * Removes {@code cullface} values that name no direction, or null when there were none to remove.
     */
    private static String dropUnknownCullfaces(String json) {
        try {
            JsonObject model = JsonParser.parseString(json).getAsJsonObject();
            JsonElement elements = model.get("elements");
            if (elements == null || !elements.isJsonArray()) {
                return null;
            }

            int dropped = 0;
            for (JsonElement element : elements.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonElement faces = element.getAsJsonObject().get("faces");
                if (faces == null || !faces.isJsonObject()) {
                    continue;
                }

                for (var face : faces.getAsJsonObject().entrySet()) {
                    if (!face.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject side = face.getValue().getAsJsonObject();
                    JsonElement cullface = side.get("cullface");
                    if (cullface == null || !cullface.isJsonPrimitive()) {
                        continue;
                    }
                    if (!DIRECTIONS.contains(cullface.getAsString().toLowerCase(Locale.ROOT))) {
                        side.remove("cullface");
                        dropped++;
                    }
                }
            }

            if (dropped == 0) {
                return null;
            }
            PolymerPatcher.LOGGER.debug("Dropped {} cullface value(s) that name no direction, as the game would", dropped);
            return model.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
