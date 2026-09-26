package me.drex.polymerpatcher.block.fluid;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every modded fluid on the server, and the two pictures each is made of.
 * <p>
 * A fluid is not drawn from a model the way a block is. The game builds its surface itself, from a
 * still picture and a flowing one that a mod hands to the client in code - code a server never runs.
 * So a modded fluid arrives at a stranger as nothing at all: Enderscape's void lachryma was an
 * invisible pit, and Alex's Caves' acid and purple soda are the same.
 * <p>
 * There is no way to ask the fluid what it looks like from here, but there does not need to be. Every
 * mod names these two pictures the same way - the fluid's own name with {@code _still} and
 * {@code _flow} or {@code _flowing} after it - because that is the naming the game's own fluids use
 * and what every tutorial copies. So the mod's assets are searched for that pair, and a fluid whose
 * pair is found gets a model built for every depth it can have.
 * <p>
 * Nothing here is specific to one mod. A fluid added by anything gets the same treatment, which is why
 * this replaced the hand-written handling the lachryma used to need.
 */
public final class ModdedFluids {

    private ModdedFluids() {
    }

    /** The two pictures a fluid is drawn from. */
    public record Skin(Identifier still, Identifier flowing) {
    }

    /** The suffixes mods use, in the order they are worth trying. */
    private static final String[] STILL = {"_still", "_source"};
    private static final String[] FLOWING = {"_flow", "_flowing"};

    private static final Map<Block, Skin> FOUND = new LinkedHashMap<>();
    private static boolean searched;

    /**
     * Works out which modded fluids can be drawn, once, before the pack is built.
     */
    public static synchronized void find() {
        if (searched) {
            return;
        }
        searched = true;

        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof LiquidBlock)) {
                continue;
            }
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || RegistryPatcher.isVanillaBlock(id)) {
                continue;
            }

            Skin skin = skinFor(id);
            if (skin != null) {
                FOUND.put(block, skin);
            } else {
                PolymerPatcher.LOGGER.debug("Found no still/flowing pair for the fluid {}; it will be left as it was", id);
            }
        }

        if (!FOUND.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} modded fluid(s) will be drawn rather than left invisible: {}",
                FOUND.size(), FOUND.keySet().stream().map(BuiltInRegistries.BLOCK::getKey).toList());
        }
    }

    /** Every fluid worth drawing, for the pack generator to write models for. */
    public static Map<Block, Skin> all() {
        find();
        return FOUND;
    }

    /** The skin for this block, or null when it is not a fluid this can draw. */
    @Nullable
    public static Skin skinOf(Block block) {
        find();
        return FOUND.get(block);
    }

    /**
     * The pair of pictures for a fluid, tried against the names mods actually use.
     * <p>
     * The block's own name first, then the same without a trailing {@code _block}, because a mod that
     * calls the block one thing and the fluid another almost always differs only by that.
     */
    @Nullable
    private static Skin skinFor(Identifier id) {
        String namespace = id.getNamespace();
        for (String name : new String[]{id.getPath(), id.getPath().replaceAll("_block$", "")}) {
            for (String still : STILL) {
                Identifier stillId = Identifier.fromNamespaceAndPath(namespace, "block/" + name + still);
                if (!hasTexture(stillId)) {
                    continue;
                }
                for (String flowing : FLOWING) {
                    Identifier flowingId = Identifier.fromNamespaceAndPath(namespace, "block/" + name + flowing);
                    if (hasTexture(flowingId)) {
                        return new Skin(stillId, flowingId);
                    }
                }
                // A fluid with only a still picture is still better drawn than not drawn
                return new Skin(stillId, stillId);
            }
        }
        return null;
    }

    private static boolean hasTexture(Identifier texture) {
        return ResourceHelper.getAsset(texture.getNamespace(), "textures/" + texture.getPath() + ".png") != null;
    }

    /** How many blocks across this fluid's texture is spread; one for an ordinary fluid. Capped at 16. */
    public static int worldTiles(Identifier fluid) {
        if (TILING_FAILED.contains(fluid)) {
            return 1;
        }
        Integer tiles = me.drex.polymerpatcher.config.ConfigManager.config().blocks.fluidWorldTiles.get(fluid.toString());
        return tiles == null || tiles < 2 ? 1 : Math.min(tiles, 16);
    }

    /** Fluids whose copies could not be written, which are drawn untiled rather than with missing models. */
    private static final java.util.Set<Identifier> TILING_FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void tilingFailed(Identifier fluid) {
        TILING_FAILED.add(fluid);
    }

    /** The copy of a fluid model for one place in its square, counted in blocks from the square's corner. */
    public static Identifier tiled(Identifier model, int tileX, int tileZ) {
        return Identifier.fromNamespaceAndPath(model.getNamespace(), model.getPath() + "_tile_" + tileX + "_" + tileZ);
    }

    /** Where the model for one depth of one fluid is written. */
    public static Identifier modelFor(Identifier fluid, int level) {
        return PolymerPatcher.id("block/fluid/" + fluid.getNamespace() + "/" + fluid.getPath() + "/" + level);
    }

    /**
     * The model for a block of this fluid with more of the same above it.
     * <p>
     * The game draws a fluid with fluid above it as a whole block - there is no surface there to draw -
     * whatever the block's own depth says. One model covers every depth for that reason: they all come
     * out as the same whole block.
     */
    /** The in-fluid view of one depth with only the faces in {@code open}; see FluidModels.OPEN_NORTH. */
    public static Identifier occupiedModelFor(Identifier fluid, int level, int open) {
        return PolymerPatcher.id("block/fluid/" + fluid.getNamespace() + "/" + fluid.getPath()
            + "/occupied_" + level + "_" + open);
    }

    /**
     * The wall standing on one side of a full block of this fluid, down to the surface of the flow beside
     * it at {@code neighbourLevel}, or to the ground when that is 0.
     */
    public static Identifier edgeWallFor(Identifier fluid, int neighbourLevel, net.minecraft.core.Direction side) {
        return PolymerPatcher.id("block/fluid/" + fluid.getNamespace() + "/" + fluid.getPath()
            + "/edge_" + neighbourLevel + "_" + side.getName());
    }

    public static Identifier occupiedModelFor(Identifier fluid, int level) {
        return PolymerPatcher.id("block/fluid/" + fluid.getNamespace() + "/" + fluid.getPath()
            + "/occupied_" + level);
    }
}
