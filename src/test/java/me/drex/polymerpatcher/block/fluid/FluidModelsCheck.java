package me.drex.polymerpatcher.block.fluid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import net.minecraft.resources.Identifier;

/** Prevents a malformed in-fluid model from making an entire falling-fluid cell disappear. */
public final class FluidModelsCheck {
    private FluidModelsCheck() {
    }

    public static void main(String[] args) {
        ModdedFluids.Skin skin = new ModdedFluids.Skin(
            Identifier.fromNamespaceAndPath("test", "block/still"),
            Identifier.fromNamespaceAndPath("test", "block/flowing"));
        for (int level = 0; level < 16; level++) {
            boolean falling = level >= 8;
            boolean flowing = level > 0 && level < 8;
            float height = (level == 0 || falling ? 8 : 8 - level) / 9.0F * 16.0F;
            float firstFlowingHeight = 7 / 9.0F * 16.0F;
            check(level, height, flowing, level == 0,
                FluidModels.surfaceAsset(skin, height, falling, flowing, firstFlowingHeight));
            check(level, height, flowing, false, FluidModels.occupiedAsset(skin, height, falling, flowing));
            // The per-side versions of the in-fluid view: some have hardly any faces, and one element
            // without a face makes 26.2 throw the whole model away
            for (int open = 0; open <= FluidModels.ALL_OPEN; open++) {
                String json = FluidModels.occupiedAsset(skin, height, falling, flowing, open).toJson();
                ModelAsset.fromJson(json);
                var elements = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("elements");
                if (elements != null) {
                    for (var value : elements) {
                        JsonObject element = value.getAsJsonObject();
                        if (!element.has("faces") || element.getAsJsonObject("faces").isEmpty()) {
                            throw new AssertionError("In-fluid view " + level + "/" + open + " has an empty-faced element");
                        }
                    }
                }
                if ((open & FluidModels.OPEN_ABOVE) != 0 && !falling && !json.contains("\"up\"")) {
                    throw new AssertionError("In-fluid view " + level + "/" + open + " is open above but has no surface");
                }
            }
        }
        System.out.println("Verified all 16 world and in-fluid models have valid faces, consistent UVs, connected columns, and source skirts without upper rims");
    }

    /**
     * @param lakeSurface the surface of a source block, whose only walls must be the narrow band down to
     *                    the first flowing level. A wall above its surface becomes a rim; a wall all the way
     *                    down becomes a dark grid visible through every translucent source top.
     */
    private static void check(int level, float height, boolean flowing, boolean lakeSurface, ModelAsset asset) {
            String json = asset.toJson();
            // Exercise the same model decoder family that rejected occupied_8..15 in the client.
            ModelAsset.fromJson(json);
            JsonArray elements = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("elements");
            boolean reachesNextCell = false;
            boolean hasTop = false;
            boolean hasWall = false;
            boolean sourceWallOutsideSkirt = false;
            float firstFlowingHeight = 7 / 9.0F * 16.0F;
            for (var value : elements) {
                JsonObject element = value.getAsJsonObject();
                if (!element.has("faces") || element.getAsJsonObject("faces").isEmpty()) {
                    throw new AssertionError("Fluid level " + level + " has an empty-faced element");
                }
                for (var face : element.getAsJsonObject("faces").entrySet()) {
                    hasTop |= face.getKey().equals("up");
                    hasWall |= face.getKey().matches("north|south|east|west");
                    if (face.getKey().matches("north|south|east|west")
                        && !face.getValue().getAsJsonObject().has("uv")) {
                        throw new AssertionError("Fluid level " + level + " has a side with mismatched UVs");
                    }
                }
                double top = element.getAsJsonArray("to").get(1).getAsDouble();
                double bottom = element.getAsJsonArray("from").get(1).getAsDouble();
                if (top > 16 || top < 0) {
                    throw new AssertionError("Fluid level " + level + " has out-of-block geometry: " + top);
                }
                if (top == 16 && element.getAsJsonObject("faces").has("north")) {
                    reachesNextCell = true;
                }
                if (lakeSurface && element.getAsJsonObject("faces").entrySet().stream()
                    .anyMatch(face -> face.getKey().matches("north|south|east|west"))
                    && (bottom < firstFlowingHeight - 1.0E-4 || top > height + 1.0E-4)) {
                    sourceWallOutsideSkirt = true;
                }
            }
            if (lakeSurface) {
                if (!hasTop) {
                    throw new AssertionError("A lake surface has no surface");
                }
                if (!hasWall) {
                    throw new AssertionError("A source has no skirt to meet the first flowing level");
                }
                if (sourceWallOutsideSkirt) {
                    throw new AssertionError("A source wall extends outside its one-level skirt");
                }
                return;
            }
            if (!flowing && !reachesNextCell) {
                throw new AssertionError("Full-depth fluid level " + level + " has a vertical seam");
            }
    }
}
