package me.drex.polymerpatcher.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.impl.DefaultModelData;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Extra vanilla block states offered to Polymer as carriers, added before it takes its copy of the list.
 * <p>
 * A carrier is a vanilla block state the pack quietly re-skins, so a vanilla client can be shown a modded
 * block as a real block rather than as an entity standing where one should be. Polymer picks those states
 * itself, and it picks conservatively: a state is only usable if some other state of the same block looks
 * exactly like it, so that a real one in the world can be sent as its twin and nothing vanilla changes
 * appearance. That rules out most of the game.
 * <p>
 * What it leaves is uneven. The full-cube pool is a note block's eight hundred and fifty states and is
 * comfortable; the see-through full-cube pool is five leaf blocks with thirteen usable states each, and on
 * this server it has been empty since the thirteenth modded block that wanted one. Every block after that
 * fell to a display entity - which is what makes a modded cave look empty from across the room and cost a
 * frame rate to stand in.
 * <p>
 * Cherry leaves are the one leaf block Polymer does not use, in either pool. They are neither biome-tinted
 * nor otherwise special - the same block, the same shape, the same twenty-six usable states as the birch
 * and spruce leaves already being used - so there is no reason for them to be sitting out.
 */
public final class CarrierPools {
    private CarrierPools() {
    }

    private static boolean widened;

    /**
     * Must run before anything asks Polymer for a carrier.
     * <p>
     * Polymer copies these lists once, when its allocator is first touched, and works from the copy
     * afterwards. Adding to them after that point changes nothing and says nothing, so the sizes are
     * logged either side: if the pool is not bigger afterwards, something got there first.
     */
    public static void widen() {
        if (widened) {
            return;
        }
        widened = true;

        try {
            int before = poolSize(BlockModelType.LEAVES) + poolSize(BlockModelType.LEAVES_WATERLOGGED);
            addLeaves(Blocks.CHERRY_LEAVES);
            int after = poolSize(BlockModelType.LEAVES) + poolSize(BlockModelType.LEAVES_WATERLOGGED);

            // Before Polymer takes its copy, like everything else here
            int offered = totalSize();
            int removed = keepOnlyClientStates();
            if (removed > 0) {
                PolymerPatcher.LOGGER.info("Left {} of {} carrier state(s) out of every pool: they exist on this server only, "
                    + "because a mod added a property to the vanilla block they belong to, and a client would be "
                    + "shown the ordinary block in their place", removed, offered);
            }

            // Reading the live count forces Polymer to take its copy now, with these additions in it, so
            // nothing loaded later can take one without them
            int live = eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.LEAVES)
                + eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.LEAVES_WATERLOGGED);

            PolymerPatcher.LOGGER.info("See-through full-cube carriers: {} offered by Polymer, {} after adding cherry leaves, {} actually available",
                before, after, live);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not add to Polymer's carrier pools; the ones it ships with are used as they are", e);
        }
    }

    /**
     * Takes out of every pool the states a client does not have.
     * <p>
     * Polymer builds its pools from this server's registry, and so does {@link #addLeaves}: every state
     * of the block. A mod that adds a property to a vanilla block puts a server-only twin beside every
     * one of those, and the twins look like perfectly good spare states - spare because nothing in the
     * world is ever in them. Sent to a client, each one becomes the real state beside it. See
     * {@link me.drex.polymerpatcher.util.BlockSyncCheck#usableAsCarrier}.
     */
    private static int keepOnlyClientStates() {
        int removed = 0;
        for (List<BlockState> pool : DefaultModelData.USABLE_STATES.values()) {
            if (pool == null) {
                continue;
            }
            int before = pool.size();
            try {
                pool.removeIf(state -> !me.drex.polymerpatcher.util.BlockSyncCheck.usableAsCarrier(state));
            } catch (UnsupportedOperationException e) {
                PolymerPatcher.LOGGER.warn("A carrier pool could not be changed, so it may still offer states a client does not have");
            }
            removed += before - pool.size();
        }
        return removed;
    }

    private static int totalSize() {
        int total = 0;
        for (List<BlockState> pool : DefaultModelData.USABLE_STATES.values()) {
            total += pool == null ? 0 : pool.size();
        }
        return total;
    }

    private static int poolSize(BlockModelType type) {
        List<BlockState> states = DefaultModelData.USABLE_STATES.get(type);
        return states == null ? 0 : states.size();
    }

    /**
     * Offers every state of a leaf block except the one real leaves are sent as.
     * <p>
     * Polymer maps a taken leaf state back to {@code persistent=true, distance=7} when a real one turns up
     * in the world, so that state is the one thing that must stay untouched - it is what every leaf on the
     * server ends up being drawn as. Its waterlogged twin goes with it for the same reason.
     */
    private static void addLeaves(Block block) {
        BlockState kept = block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState keptFlooded = kept.setValue(LeavesBlock.WATERLOGGED, true);

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (state == kept || state == keptFlooded) {
                continue;
            }
            BlockModelType type = state.getValue(LeavesBlock.WATERLOGGED)
                ? BlockModelType.LEAVES_WATERLOGGED
                : BlockModelType.LEAVES;
            List<BlockState> pool = DefaultModelData.USABLE_STATES.get(type);
            if (pool != null) {
                pool.add(state);
            }
        }
    }
}
