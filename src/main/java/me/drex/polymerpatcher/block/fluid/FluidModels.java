package me.drex.polymerpatcher.block.fluid;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Builds a surface for every modded fluid, at every depth it can be.
 * <p>
 * A fluid block is not one shape but sixteen: full when it is a source, and progressively shallower as
 * it spreads. The game draws that itself from the fluid's own pictures; here each depth is written out
 * as its own little model, a slab of the right height wearing the still picture on top and the flowing
 * one around the sides, and the block picks the one matching its level.
 * <p>
 * The heights are the game's own, to the pixel, because the client is also sent water at the same depth
 * so that it can be swum in - and two surfaces at nearly the same height is worse than either alone. A
 * slab two pixels short of the water put the <em>water's</em> surface on top: an acid lake looked down
 * on was water, with acid somewhere underneath it. Matched exactly, and nudged a hundredth of a pixel
 * outwards so it wins wherever the two coincide, the water ends up behind ours on every face and is
 * never seen.
 */
public final class FluidModels {

    private FluidModels() {
    }

    /**
     * Writes the models, and the item definitions that are the only way a display can reach them.
     */
    public static void generate(ResourcePackBuilder builder) {
        var fluids = ModdedFluids.all();
        if (fluids.isEmpty()) {
            return;
        }

        int written = 0;
        for (var entry : fluids.entrySet()) {
            Identifier fluid = BuiltInRegistries.BLOCK.getKey(entry.getKey());
            if (fluid == null) {
                continue;
            }
            ModdedFluids.Skin skin = entry.getValue();
            int tiles = ModdedFluids.worldTiles(fluid);

            for (int level = 0; level <= BlockStateProperties.MAX_LEVEL_15; level++) {
                boolean flowingSurface = level > 0 && level < 8;
                float height = heightOf(level);
                // With edge walls on, a source needs no skirt of its own: the wall beside it reaches as
                // far down as the flow beside it actually is, where a skirt could only guess one level
                boolean edges = me.drex.polymerpatcher.config.ConfigManager.config().blocks.fluidEdgeWalls;
                write(builder, ModdedFluids.modelFor(fluid, level), skin, height,
                    level >= 8, flowingSurface, level == 0 && !edges ? heightOf(1) : height);
                writeOccupied(builder, ModdedFluids.occupiedModelFor(fluid, level), skin,
                    height, level >= 8, flowingSurface);
                written += 2;
                for (int open = 0; open <= ALL_OPEN; open++) {
                    Identifier id = ModdedFluids.occupiedModelFor(fluid, level, open);
                    builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json",
                        occupiedAsset(skin, heightOf(level), level >= 8, flowingSurface, open));
                    builder.addData("assets/" + id.getNamespace() + "/items/-/" + id.getPath() + ".json",
                        new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))),
                            ItemAsset.Properties.DEFAULT));
                    written++;
                }

                if (tiles > 1) {
                    boolean ok = writeTiles(builder, ModdedFluids.modelFor(fluid, level), tiles)
                        & writeTiles(builder, ModdedFluids.occupiedModelFor(fluid, level), tiles);
                    if (!ok) {
                        ModdedFluids.tilingFailed(fluid);
                        PolymerPatcher.LOGGER.warn("Could not spread {}'s texture across {} blocks; it is drawn whole on every block instead",
                            fluid, tiles);
                        tiles = 1;
                    }
                }
            }
            if (tiles > 1) {
                PolymerPatcher.LOGGER.info("{}'s texture is spread across {}x{} blocks, as its own mod draws it", fluid, tiles, tiles);
            }
            for (int neighbour = 0; neighbour <= 7; neighbour++) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    Identifier id = ModdedFluids.edgeWallFor(fluid, neighbour, side);
                    builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json",
                        edgeAsset(skin, heightOf(0), neighbour == 0 ? 0.0F : heightOf(neighbour), side));
                    builder.addData("assets/" + id.getNamespace() + "/items/-/" + id.getPath() + ".json",
                        new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))),
                            ItemAsset.Properties.DEFAULT));
                }
            }
        }

        PolymerPatcher.LOGGER.info("Built {} surface(s) for {} modded fluid(s), which would otherwise be invisible",
            written, fluids.size());
    }

    /**
     * How tall the game draws a fluid at this level, in pixels.
     * <p>
     * A block's level is not its depth: nought is a source and full, one to seven are the spreading
     * depths, and eight and up are falling, which is full again. The game turns that into an amount out
     * of nine and draws the fluid that fraction of a block tall.
     */
    static float heightOf(int level) {
        if (!me.drex.polymerpatcher.config.ConfigManager.config().blocks.fluidSurfacesMatchGameHeights) {
            // What this drew before the game's own heights were worked out: a little short of the water,
            // which is wrong but is what every one of these has looked like until now
            return level < 8 ? (float) Math.floor(net.minecraft.util.Mth.lerp(level / 8f, 12, 2)) : 16;
        }

        int amount = level == 0 || level >= 8 ? 8 : 8 - level;
        return amount / 9.0F * 16.0F;
    }

    private static void write(ResourcePackBuilder builder, Identifier modelId, ModdedFluids.Skin skin,
                              float height, boolean falling, boolean flowingSurface,
                              float sourceSkirtBottom) {
        builder.addData("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json",
            surfaceAsset(skin, height, falling, flowingSurface, sourceSkirtBottom));

        // A display is handed an item, and an item finds its model only through a definition of its own.
        // These live in this mod's namespace, which nothing bridges automatically, so without this there
        // is a model in the pack that nothing ever points at
        builder.addData("assets/" + modelId.getNamespace() + "/items/-/" + modelId.getPath() + ".json",
            new ItemAsset(new BasicItemModel(modelId, List.of(new MapColorTintSource(0xFFFFFF))),
                ItemAsset.Properties.DEFAULT));
    }

    static ModelAsset surfaceAsset(ModdedFluids.Skin skin, float height, boolean falling,
                                   boolean flowingSurface, float sourceSkirtBottom) {
        boolean source = !falling && !flowingSurface;
        ModelAsset.Builder model = ModelAsset.builder()
            // Flowing sprites are laid out as side strips, not square top tiles. Putting one here
            // stretches its frames into the very high-resolution surface reported in game. Vanilla
            // animates flow direction through renderer UVs; a state-only model cannot, so its still
            // square is the correct safe horizontal face at every depth.
            .texture("top", skin.still())
            .texture("side", skin.flowing())
            .textureReference("particle", "top")
            // Keep translucent geometry inside the legal 0..16 model bounds. Extending it by even 0.01
            // makes 26.2's transparency baker sample pixels [-1..17]; animated textures such as purple
            // soda then reject the entire model and the water carrier is all that remains visible.
            .element(new Vec3(0, 0, 0), new Vec3(16, height, 16), element -> {
                for (Direction direction : Direction.values()) {
                    // A lake's surface is only its surface. The game draws a fluid's side only where the
                    // block beside it is not the same fluid, and a model cannot ask - so every source
                    // block drew all four walls, and every pair of neighbours drew the wall between them
                    // twice. Seen across a lake that is a grid of lines at every block edge. A source next
                    // to open air is the one place a wall is missed, and a source there flows into it
                    if (source && direction != Direction.UP) {
                        continue;
                    }
                    // A falling fluid is a vertical continuation, not sixteen independent pools.
                    // Drawing a top face in every block made waterfalls look like a stack of glowing
                    // cubes. Bottom faces are never externally visible and only create seams against
                    // the cell below, so no fluid level emits one.
                    if (direction == Direction.DOWN || (falling && direction == Direction.UP)) {
                        continue;
                    }
                    if (direction == Direction.UP) {
                        element.face(direction, "#top");
                    } else {
                        element.face(direction, 0, 8 - height / 2f, 8, 8, "#side", null);
                    }
                }
            });
        if (source) {
            // A source has no ordinary side faces because seeing four translucent walls through every
            // neighbouring source turns a lake into a grid. It still needs the one-level band immediately
            // below its surface, however: the first flowing level beside it is exactly this much shorter.
            // Without the band there is literally no face between the two heights and the ground shows
            // through as the rectangular gaps reported in game. Keeping only this narrow skirt closes the
            // step without bringing back full-height internal walls.
            float bottom = Math.min(height, sourceSkirtBottom);
            if (bottom < height) {
                model.element(new Vec3(0, bottom, 0), new Vec3(16, height, 16),
                    element -> addSideBand(element, bottom, height));
            }
        // Walls from the surface to the top of the block, so that a column has no gap between its layers.
        // A falling column needs them. A source must not get these: they stand above its surface as a rim.
        } else if (!flowingSurface && height < 16) {
            // A source or falling cell beneath more of the same fluid needs walls all the way to
            // the next cell. Its surface remains at vanilla's 8/9 height, but without this strip
            // every level in a tall column exposes a visible 1.78-pixel horizontal gap.
            model.element(new Vec3(0, height, 0), new Vec3(16, 16, 16),
                element -> addUpperWalls(element, height));
        }
        return model.build();
    }

    /**
     * A full cell used only underneath the player to give an unmodded client water movement.
     *
     * <p>Ordinary block-model faces point outwards and are back-face culled. That is normally correct,
     * but it made this model disappear precisely when the camera entered it, revealing the blue water
     * carried underneath. Six paper-thin inner walls add inward-facing twins without changing anything
     * seen from outside, so the custom liquid remains visible while the player is in it.</p>
     */
    /**
     * A copy of a finished fluid model for every place in the square its texture is spread across.
     * <p>
     * The same model everywhere puts the whole texture on every block. Each copy moves every face's
     * texture coordinates into its own slice - {@code new = (16 * place + old) / blocks} - which is the
     * sum The Sift's client does per block for ichor, done once here instead. A face that states no
     * coordinates is given the ones the game would have worked out for it first, so it moves with the
     * rest rather than staying whole.
     *
     * @return false where the model could not be read back, so the caller can fall back to the whole one
     */
    private static boolean writeTiles(ResourcePackBuilder builder, Identifier modelId, int tiles) {
        byte[] written = builder.getData("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json");
        if (written == null) {
            return false;
        }
        com.google.gson.JsonObject model = com.google.gson.JsonParser.parseString(
            new String(written, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();

        for (int tileX = 0; tileX < tiles; tileX++) {
            for (int tileZ = 0; tileZ < tiles; tileZ++) {
                com.google.gson.JsonObject copy = model.deepCopy();
                moveIntoTile(copy, tileX, tileZ, tiles);
                Identifier id = ModdedFluids.tiled(modelId, tileX, tileZ);
                builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json",
                    copy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                builder.addData("assets/" + id.getNamespace() + "/items/-/" + id.getPath() + ".json",
                    new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))),
                        ItemAsset.Properties.DEFAULT));
            }
        }
        return true;
    }

    private static void moveIntoTile(com.google.gson.JsonObject model, int tileX, int tileZ, int tiles) {
        if (!model.has("elements")) {
            return;
        }
        for (com.google.gson.JsonElement entry : model.getAsJsonArray("elements")) {
            com.google.gson.JsonObject element = entry.getAsJsonObject();
            if (!element.has("faces")) {
                continue;
            }
            float[] from = xyz(element.getAsJsonArray("from"));
            float[] to = xyz(element.getAsJsonArray("to"));
            for (var side : element.getAsJsonObject("faces").entrySet()) {
                com.google.gson.JsonObject face = side.getValue().getAsJsonObject();
                float[] uv = face.has("uv") ? xyz4(face.getAsJsonArray("uv")) : defaultUv(side.getKey(), from, to);
                com.google.gson.JsonArray moved = new com.google.gson.JsonArray();
                moved.add((16 * tileX + uv[0]) / tiles);
                moved.add((16 * tileZ + uv[1]) / tiles);
                moved.add((16 * tileX + uv[2]) / tiles);
                moved.add((16 * tileZ + uv[3]) / tiles);
                face.add("uv", moved);
            }
        }
    }

    /** The texture coordinates the game gives a face that names none, from the element's own corners. */
    private static float[] defaultUv(String side, float[] from, float[] to) {
        return switch (side) {
            case "down" -> new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
            case "up" -> new float[]{from[0], from[2], to[0], to[2]};
            case "north" -> new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case "south" -> new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case "west" -> new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            default -> new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        };
    }

    private static float[] xyz(com.google.gson.JsonArray values) {
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()};
    }

    private static float[] xyz4(com.google.gson.JsonArray values) {
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(),
            values.get(2).getAsFloat(), values.get(3).getAsFloat()};
    }

    private static void writeOccupied(ResourcePackBuilder builder, Identifier modelId,
                                      ModdedFluids.Skin skin, float height, boolean falling,
                                      boolean flowingSurface) {
        builder.addData("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json",
            occupiedAsset(skin, height, falling, flowingSurface));
        builder.addData("assets/" + modelId.getNamespace() + "/items/-/" + modelId.getPath() + ".json",
            new ItemAsset(new BasicItemModel(modelId, List.of(new MapColorTintSource(0xFFFFFF))),
                ItemAsset.Properties.DEFAULT));
    }

    /** Package-visible so the verifier can reject invalid or empty-faced models before shipping. */
    static ModelAsset occupiedAsset(ModdedFluids.Skin skin, float height, boolean falling,
                                    boolean flowingSurface) {
        final float shell = 0.01F;
        float wallHeight = fullWallHeight(height, flowingSurface);
        ModelAsset.Builder model = ModelAsset.builder()
            .texture("top", skin.still())
            .texture("side", skin.flowing())
            .textureReference("particle", "top")
            .element(new Vec3(0, 0, 0), new Vec3(16, height, 16), element -> {
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN || (falling && direction == Direction.UP)) {
                        continue;
                    }
                    if (direction == Direction.UP) {
                        element.face(direction, "#top");
                    } else {
                        element.face(direction, 0, 8 - height / 2f, 8, 8, "#side", null);
                    }
                }
            });
        if (!flowingSurface && height < 16) {
            model.element(new Vec3(0, height, 0), new Vec3(16, wallHeight, 16),
                element -> addUpperWalls(element, height));
        }
        ModelAsset asset = model
            // Inward-facing shell. Each thin element contributes only the face looking into the cell.
            .element(new Vec3(0, 0, 0), new Vec3(shell, wallHeight, 16),
                element -> addInnerWall(element, Direction.EAST, wallHeight))
            .element(new Vec3(16 - shell, 0, 0), new Vec3(16, wallHeight, 16),
                element -> addInnerWall(element, Direction.WEST, wallHeight))
            .element(new Vec3(0, 0, 0), new Vec3(16, wallHeight, shell),
                element -> addInnerWall(element, Direction.SOUTH, wallHeight))
            .element(new Vec3(0, 0, 16 - shell), new Vec3(16, wallHeight, 16),
                element -> addInnerWall(element, Direction.NORTH, wallHeight))
            .element(new Vec3(0, 0, 0), new Vec3(16, shell, 16),
                element -> element.face(Direction.UP, "#top"))
            .element(new Vec3(0, Math.max(0, height - shell), 0), new Vec3(16, height, 16),
                // This face points into the cell, not out into the column. Falling levels used to
                // omit it, producing an element with no faces; 26.2 rejects the entire model.
                element -> element.face(Direction.DOWN, "#top"))
            .build();
        return asset;
    }

    /**
     * Which faces of an in-fluid cell look out at something other than more of the player's own local fluid.
     * <p>
     * The fluid a player is standing in is shown to them, and only them, as water in the cells their body is
     * in - so that they can swim - with the modded fluid's skin drawn over it. Each of those cells used to wear
     * the whole skin: four walls, and a rim above the surface for the case of more fluid above. Standing up
     * puts a player in two cells, and moving puts them across two more, so the walls between those cells, and
     * the rim, showed as edges all round them. The game itself draws nothing between two blocks of water, so
     * nothing there needs covering. Only a side that looks out of the player's cells does.
     */
    public static final int OPEN_NORTH = 1;
    public static final int OPEN_EAST = 2;
    public static final int OPEN_SOUTH = 4;
    public static final int OPEN_WEST = 8;
    public static final int OPEN_ABOVE = 16;
    public static final int OPEN_BELOW = 32;
    public static final int ALL_OPEN = 63;

    static int bit(Direction direction) {
        return switch (direction) {
            case NORTH -> OPEN_NORTH;
            case EAST -> OPEN_EAST;
            case SOUTH -> OPEN_SOUTH;
            case WEST -> OPEN_WEST;
            case UP -> OPEN_ABOVE;
            case DOWN -> OPEN_BELOW;
        };
    }

    /**
     * The in-fluid view of one cell, with faces only where it looks out of the player's own cells.
     * <p>
     * With more of the same above, the walls run on to meet it and there is no surface; with nothing above
     * they stop at the surface, so there is no rim. Every element is only added when it has a face, because
     * 26.2 throws away a whole model for one element without any.
     */
    static ModelAsset occupiedAsset(ModdedFluids.Skin skin, float height, boolean falling,
                                    boolean flowingSurface, int open) {
        final float shell = 0.01F;
        boolean openAbove = (open & OPEN_ABOVE) != 0;
        float wallHeight = openAbove ? height : 16;
        boolean surface = openAbove && !falling;
        List<Direction> sides = new java.util.ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if ((open & bit(direction)) != 0) {
                sides.add(direction);
            }
        }

        ModelAsset.Builder model = ModelAsset.builder()
            .texture("top", skin.still())
            .texture("side", skin.flowing())
            .textureReference("particle", "top");
        if (surface || !sides.isEmpty()) {
            model.element(new Vec3(0, 0, 0), new Vec3(16, height, 16), element -> {
                if (surface) {
                    element.face(Direction.UP, "#top");
                }
                for (Direction direction : sides) {
                    element.face(direction, 0, 8 - height / 2f, 8, 8, "#side", null);
                }
            });
        }
        if (!openAbove && height < 16 && !sides.isEmpty()) {
            model.element(new Vec3(0, height, 0), new Vec3(16, 16, 16), element -> {
                for (Direction direction : sides) {
                    element.face(direction, 0, 0, 8, 8 - height / 2f, "#side", null);
                }
            });
        }
        for (Direction direction : sides) {
            switch (direction) {
                case WEST -> model.element(new Vec3(0, 0, 0), new Vec3(shell, wallHeight, 16),
                    element -> addInnerWall(element, Direction.EAST, wallHeight));
                case EAST -> model.element(new Vec3(16 - shell, 0, 0), new Vec3(16, wallHeight, 16),
                    element -> addInnerWall(element, Direction.WEST, wallHeight));
                case NORTH -> model.element(new Vec3(0, 0, 0), new Vec3(16, wallHeight, shell),
                    element -> addInnerWall(element, Direction.SOUTH, wallHeight));
                case SOUTH -> model.element(new Vec3(0, 0, 16 - shell), new Vec3(16, wallHeight, 16),
                    element -> addInnerWall(element, Direction.NORTH, wallHeight));
                default -> {
                }
            }
        }
        if ((open & OPEN_BELOW) != 0) {
            model.element(new Vec3(0, 0, 0), new Vec3(16, shell, 16),
                element -> element.face(Direction.UP, "#top"));
        }
        if (openAbove) {
            model.element(new Vec3(0, Math.max(0, height - shell), 0), new Vec3(16, height, 16),
                element -> element.face(Direction.DOWN, "#top"));
        }
        return model.build();
    }

    private static float fullWallHeight(float height, boolean flowingSurface) {
        return flowingSurface ? height : 16;
    }

    private static void addUpperWalls(eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement.Builder element,
                                      float height) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            element.face(direction, 0, 0, 8, 8 - height / 2f, "#side", null);
        }
    }

    /** A vertical strip between two fluid heights, using the same quadrant as the ordinary side. */
    /**
     * Which side of a model ends up facing a direction in the world. An item display turns its model half
     * round before drawing it, so the model's north edge is the world's south: a wall meant for one side
     * has to be written on the other.
     */
    public static Direction modelSide(Direction world) {
        return world.getAxis().isHorizontal() ? world.getOpposite() : world;
    }

    /** One wall of a full block of fluid, facing {@code world}, from {@code bottom} up to {@code top}. */
    static ModelAsset edgeAsset(ModdedFluids.Skin skin, float top, float bottom, Direction world) {
        Direction side = modelSide(world);
        Vec3 from = switch (side) {
            case SOUTH -> new Vec3(0, bottom, 16);
            case EAST -> new Vec3(16, bottom, 0);
            default -> new Vec3(0, bottom, 0);
        };
        Vec3 to = switch (side) {
            case NORTH -> new Vec3(16, top, 0);
            case WEST -> new Vec3(0, top, 16);
            default -> new Vec3(16, top, 16);
        };
        return ModelAsset.builder()
            .texture("side", skin.flowing())
            .textureReference("particle", "side")
            .element(from, to, element -> {
                // Seen from outside, and from inside the lower flow beside it
                element.face(side, 0, 8 - top / 2f, 8, 8 - bottom / 2f, "#side", null);
                element.face(side.getOpposite(), 0, 8 - top / 2f, 8, 8 - bottom / 2f, "#side", null);
            })
            .build();
    }

    private static void addSideBand(eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement.Builder element,
                                    float bottom, float top) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            element.face(direction, 0, 8 - top / 2f, 8, 8 - bottom / 2f, "#side", null);
        }
    }

    private static void addInnerWall(eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement.Builder element,
                                     Direction direction, float height) {
        // The exterior uses only one 16-pixel quadrant of the animated 32-pixel flowing sprite.
        // Automatic UVs on the inner sheets sampled the entire sprite, so entering a fluid made it
        // abruptly change scale and appear to flow at a different speed/direction.
        element.face(direction, 0, 8 - height / 2f, 8, 8, "#side", null);
    }

    /** The pictures a fluid is drawn from have to be in the atlas, like any other block texture. */
    public static void stitch(java.util.Collection<Identifier> sprites) {
        for (ModdedFluids.Skin skin : ModdedFluids.all().values()) {
            sprites.add(skin.still());
            sprites.add(skin.flowing());
        }
    }
}
