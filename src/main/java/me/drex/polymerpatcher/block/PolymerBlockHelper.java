package me.drex.polymerpatcher.block;

import eu.pb4.factorytools.api.block.model.SignModel;
import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.BlockConfig;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.resources.ResourcePackGenerator;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.*;

import java.util.Map;

public class PolymerBlockHelper {
    public static void registerPolymerBlock(Identifier id, Block block) {
        // Solid and transparent blocks used to be registered through separate calls; factorytools now
        // takes both through one and works the difference out from the block itself
        // FactoryTools assumes every registered block has a conventional blockstate JSON and tries
        // to construct a String from null when a renderer-only block does not. The call already fails
        // without registering anything; avoiding it here removes a large startup stack trace per
        // custom-rendered block and saves the failed parse without changing its fallback path.
        if (ResourceHelper.getAsset(id.getNamespace(), "blockstates/" + id.getPath() + ".json") != null) {
            BlockStateModelManager.addBlock(id, block);
        } else {
            PolymerPatcher.LOGGER.debug("{} has no blockstate JSON; skipping FactoryTools' conventional model decoder", id);
        }

        PolymerBlock polymerBlock = requestPolymerBlock(id, block);
        PolymerBlock.registerOverlay(block, polymerBlock);
        if (polymerBlock instanceof BlockWithElementHolder blockWithElementHolder) {
            BlockWithElementHolder.registerOverlay(block, blockWithElementHolder);
        }

        if (block instanceof SignBlock) {
            SignModel.setModel(block, id.withPrefix("block_sign/"));
        }
        if (block.getClass().equals(StandingSignBlock.class)) {
            ResourcePackGenerator.SIGNS.add(id.withPath(id.getPath().replace("_sign", "")));
        }
    }

    /**
     * The order blocks are given carriers in, lowest first.
     * <p>
     * This matters because carriers run out. Whatever is asked for first gets the real thing; whatever is
     * left over is drawn by an entity standing where a block should be, which looks right up close,
     * vanishes at a distance and costs a frame rate in bulk.
     * <p>
     * A configured weight still wins, and everything that has none is now ordered by what it will cost -
     * the number of block states it has. That is deliberately greedy: serving the cheap blocks first
     * serves the most blocks, and the cheap ones are overwhelmingly the ones that appear in bulk. A cave
     * is built out of one-state cubes. A block with three hundred states is a wall or a fence or some
     * piece of machinery, placed a handful of times, and it was quietly eating the budget a whole cave
     * needed because the two were previously indistinguishable and registry order decided.
     */
    public static int getPriority(Holder.Reference<Block> reference) {
        Identifier id = reference.key().identifier();
        BlockConfig blocks = ConfigManager.config().blocks;

        // Fluid surfaces are now carried by the two dry tripwire pools. There are only enough states
        // for a handful of complete fluids, and ordinary plants otherwise consume both pools before a
        // liquid is reached in registry order. Reserve them first: a missed plant can fall back to one
        // display per placed block, while a missed fluid becomes one display per cell in an entire lake.
        if (reference.value() instanceof LiquidBlock
            && me.drex.polymerpatcher.block.fluid.ModdedFluids.skinOf(reference.value()) != null) {
            return Integer.MIN_VALUE;
        }

        Integer weight = blocks.weights.get(id);
        if (weight != null) {
            return weight;
        }

        for (BlockConfig.RegexWeight regexWeight : blocks.regexWeights) {
            if (id.toString().matches(regexWeight.regex())) {
                return regexWeight.weight();
            }
        }

        // What this world is actually full of, learned from earlier runs (see BlockUsage)
        Integer used = BlockUsage.weight(id);
        if (used != null) {
            return used;
        }

        // Branches are world-generation blocks: a single primordial cave can contain thousands of
        // them. If their small transparent carrier pools have already been spent, every branch turns
        // into an item-display entity and the cave's frame rate collapses. Reserve those carriers by
        // what the block is called, not by one mod's id, so any mod adding *_branch or *_branches gets
        // the same protection. Leaves still go first because they are even more numerous.
        String path = id.getPath();
        if (path.endsWith("_branch") || path.endsWith("_branches")) {
            return 300;
        }

        // Added to the default rather than replacing it, so a configured weight - all of which sit at or
        // below the default - still comes first whatever the block costs
        return blocks.defaultWeight + Math.min(stateCount(reference.value()), 1000);
    }

    private static int stateCount(Block block) {
        try {
            return block.getStateDefinition().getPossibleStates().size();
        } catch (Throwable e) {
            return 0;
        }
    }

    public static PolymerBlock requestPolymerBlock(Identifier id, Block block) {
        PolymerPatcher.LOGGER.debug("Registering polymer block {}...", id);
        // A fluid is not drawn from a model the way a block is, so the shape matching below has nothing
        // to work with and leaves it invisible. Any modded fluid whose pictures could be found is drawn
        // by a surface built for it instead. This deliberately comes before every ordinary block path:
        // Enderscape's lachryma used to have a private copy of this mechanism, which meant improvements
        // to the global fluid renderer fixed every other mod while leaving that one behind.
        if (me.drex.polymerpatcher.block.fluid.ModdedFluids.skinOf(block) != null) {
            return new me.drex.polymerpatcher.block.fluid.ModdedFluidBlock(id, block);
        }

        return AutomaticFactoryBlock.create(id, block);
    }
}
