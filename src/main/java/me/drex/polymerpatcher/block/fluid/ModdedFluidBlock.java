package me.drex.polymerpatcher.block.fluid;

import eu.pb4.factorytools.api.block.FactoryBlock;
import eu.pb4.factorytools.api.block.model.generic.BSMMParticleBlock;
import eu.pb4.factorytools.api.util.LazyItemStack;
import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.stream.IntStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws one modded fluid, at whatever depth it happens to be.
 * <p>
 * Every depth is sent as a dry, no-collision vanilla carrier whose resource-pack model is the fluid's
 * own surface. A water carrier cannot be made invisible to a vanilla client: its blue surface leaks
 * through translucent soda/acid, and using a display above it creates one entity per cell in a lake.
 * Tripwire states solve both problems: they retain the server's authoritative fluid behaviour while
 * the client renders the entire pool as ordinary chunk geometry. See {@link FluidModels}.
 * <p>
 * This is the same trick Enderscape's void lachryma needed, written once for any fluid rather than
 * once per mod.
 */
public final class ModdedFluidBlock implements FactoryBlock, PolymerTexturedBlock, BSMMParticleBlock {

    /** The wrapper for each real fluid block, used by the per-player water-physics bridge. */
    private static final Map<Block, ModdedFluidBlock> BY_BLOCK =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private final LazyItemStack[] models;
    private final LazyItemStack[] occupiedModels;

    /** A chunk-batched, dry and non-colliding carrier for each value of LiquidBlock.LEVEL. */
    private final @Nullable BlockState[] carriers;

    /** This fluid's own name, for the setting that says which are left as they were. */
    private final Identifier fluid;

    public ModdedFluidBlock(Identifier fluid, Block block) {
        this.fluid = fluid;
        this.models = IntStream.rangeClosed(0, BlockStateProperties.MAX_LEVEL_15)
            .mapToObj(level -> ItemDisplayElementUtil.getModel(ModdedFluids.modelFor(fluid, level)))
            .toArray(LazyItemStack[]::new);
        this.occupiedModels = IntStream.rangeClosed(0, BlockStateProperties.MAX_LEVEL_15)
            .mapToObj(level -> ItemDisplayElementUtil.getModel(ModdedFluids.occupiedModelFor(fluid, level)))
            .toArray(LazyItemStack[]::new);
        this.carriers = new BlockState[BlockStateProperties.MAX_LEVEL_15 + 1];
        // A carrier is a real block, drawn with one model per state wherever it stands - it cannot show a
        // different slice of the texture depending on where it is. A fluid whose texture is spread across
        // many blocks (BlockConfig.fluidWorldTiles) therefore takes no carriers and is drawn by displays,
        // which pick their slice by position. Leaving the tripwire carriers for the fluids that can use them
        for (int level = 0; level < carriers.length && ModdedFluids.worldTiles(fluid) <= 1; level++) {
            try {
                BlockModelType type = dryCarrierType();
                if (type == null) {
                    break;
                }
                carriers[level] = PolymerBlockResourceUtils.requestBlock(type,
                    new PolymerBlockModel(ModdedFluids.modelFor(fluid, level), 0, 0, false, 1));
            } catch (Throwable ignored) {
                // An unallocated level still has the display fallback below.
            }
        }

        BY_BLOCK.put(block, this);
    }

    /** The wrapper belonging to this real block, or null when it is not a custom fluid. */
    public static @Nullable ModdedFluidBlock forBlock(Block block) {
        return BY_BLOCK.get(block);
    }

    /** Whether this fluid should be given local vanilla water physics for this player. */
    public boolean needsWaterPhysics(net.minecraft.server.level.ServerPlayer player) {
        return ConfigManager.config().blocks.moddedFluidsAsWater
            && !ConfigManager.config().blocks.fluidsKeptAsThemselves.contains(fluid.toString())
            && !me.drex.polymerpatcher.util.NativeClients.has(player, fluid.getNamespace())
            // A client with its own copy of this fluid is given water movement in it by the companion
            && !me.drex.polymerpatcher.companion.CompanionServer.hasBlock(player, blockOf());
    }

    /** Water at the same depth, used only locally to give an unmodded client swimming physics. */
    public BlockState physicsCarrier(BlockState actual) {
        int level = level(actual);
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
    }

    /** The same-depth skin, including inward faces for a camera which is inside this cell. */
    LazyItemStack occupiedModel(BlockState actual, BlockPos pos, int open) {
        int level = level(actual);
        if (ModdedFluids.worldTiles(fluid) > 1) {
            // One model per place in the square already; a version per open side of each would be thousands
            return tiled(ModdedFluids.occupiedModelFor(fluid, level), occupiedModels[level], pos);
        }
        return tiledStacks.computeIfAbsent(ModdedFluids.occupiedModelFor(fluid, level, open),
            ItemDisplayElementUtil::getModel);
    }

    /** Copies already handed out, so the same place always gets the very same stack. */
    private final Map<Identifier, LazyItemStack> tiledStacks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * This block's own copy of a model, where the fluid's texture is spread across a square of blocks - see
     * {@code BlockConfig.fluidWorldTiles}. Everywhere else the one model every block shares.
     */
    private LazyItemStack tiled(Identifier model, LazyItemStack whole, BlockPos pos) {
        int tiles = ModdedFluids.worldTiles(fluid);
        if (tiles <= 1) {
            return whole;
        }
        Identifier id = ModdedFluids.tiled(model, Math.floorMod(pos.getX(), tiles), Math.floorMod(pos.getZ(), tiles));
        return tiledStacks.computeIfAbsent(id, ItemDisplayElementUtil::getModel);
    }

    int level(BlockState state) {
        int level = state.hasProperty(LiquidBlock.LEVEL) ? state.getValue(LiquidBlock.LEVEL) : 0;
        return Math.min(level, BlockStateProperties.MAX_LEVEL_15);
    }

    @Nullable
    private static BlockModelType dryCarrierType() {
        if (PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.TRIPWIRE_FLAT) > 0) {
            return BlockModelType.TRIPWIRE_FLAT;
        }
        if (PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.TRIPWIRE) > 0) {
            return BlockModelType.TRIPWIRE;
        }
        return null;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        if (ConfigManager.config().blocks.fluidsKeptAsThemselves.contains(fluid.toString())) {
            return Blocks.AIR.defaultBlockState();
        }

        int level = level(state);
        BlockState carrier = carriers[level];
        if (carrier != null) {
            return carrier;
        }

        // No dry carrier remains. Air plus a display is visually correct and, unlike water, cannot
        // visibly mix blue into a translucent custom liquid. The carrier path above is expected for
        // every level; this exists only as a resource-exhaustion fallback.
        return Blocks.AIR.defaultBlockState();
    }

    /** Block tags naming this fluid's block, for a companion client that has a copy of it. */
    @Override
    public boolean canSyncRawToClient(PacketContext context) {
        return me.drex.polymerpatcher.companion.CompanionServer.tagsReady(context, blockOf());
    }

    @Override
    public boolean overridePlayerCollisionsWithPolymer(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state,
                                                       net.minecraft.server.level.ServerPlayer player) {
        return !me.drex.polymerpatcher.companion.CompanionServer.hasBlock(player, state.getBlock());
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        int level = initialBlockState.hasProperty(LiquidBlock.LEVEL) ? initialBlockState.getValue(LiquidBlock.LEVEL) : 0;
        if (carriers[Math.min(level, carriers.length - 1)] != null) {
            // The carrier draws the fluid; all this holder ever shows is a wall where a side is exposed
            return ConfigManager.config().blocks.fluidEdgeWalls ? new EdgeModel(world, pos, initialBlockState) : null;
        }
        return new Model(world, pos, initialBlockState);
    }

    /** A holder that can be told its neighbours changed, so its walls can follow. */
    private interface EdgeAware {
        void refreshEdges();
    }

    /**
     * The walls a full block of this fluid needs on its sides, and nowhere else.
     * <p>
     * The game draws a fluid's side wherever the block beside it is not the same fluid, or is the same
     * fluid standing lower - which a block model cannot know, so a lake's surface was drawn without sides
     * at all and every step down to the flow beside it was a slit through to the ground. Each exposed side
     * gets one wall from the neighbour's surface (or the ground) up to this one's. The inside of a lake has
     * nothing exposed and shows nothing. Only full blocks need these: flowing and falling fluid already
     * draws its own sides.
     */
    private final class Edges {
        private final ServerLevel world;
        private final BlockPos pos;
        private final ElementHolder holder;
        private final java.util.EnumMap<net.minecraft.core.Direction, ItemDisplayElement> shown =
            new java.util.EnumMap<>(net.minecraft.core.Direction.class);
        private final java.util.EnumMap<net.minecraft.core.Direction, Integer> keys =
            new java.util.EnumMap<>(net.minecraft.core.Direction.class);

        private Edges(ServerLevel world, BlockPos pos, ElementHolder holder) {
            this.world = world;
            this.pos = pos;
            this.holder = holder;
        }

        void refresh(BlockState state) {
            boolean source = state.getBlock() == blockOf() && level(state) == 0;
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                int wanted = source ? wallFor(pos.relative(side)) : -1;
                Integer current = keys.get(side);
                if (current != null && current == wanted) {
                    continue;
                }
                ItemDisplayElement old = shown.remove(side);
                if (old != null) {
                    holder.removeElement(old);
                }
                keys.put(side, wanted);
                if (wanted < 0) {
                    continue;
                }
                ItemDisplayElement wall = ItemDisplayElementUtil.createSimple(
                    ItemDisplayElementUtil.getModel(ModdedFluids.edgeWallFor(fluid, wanted, side)).get());
                wall.setViewRange(ConfigManager.config().blocks.displayViewRange);
                wall.setDisplaySize(1.5F, 1.5F);
                wall.setOffset(new Vec3(0, -0.5, 0));
                wall.setTranslation(new Vector3f(0, 0.5F, 0));
                shown.put(side, wall);
                holder.addElement(wall);
            }
        }

        /**
         * -1 for no wall; 0 for a wall to the ground; 1 to 7 for a wall down to the surface of the flow at
         * that level. Nothing is loaded to find out: a neighbour in a chunk not yet here gets no wall.
         */
        private int wallFor(BlockPos next) {
            var chunk = world.getChunkSource().getChunkNow(next.getX() >> 4, next.getZ() >> 4);
            if (chunk == null) {
                return -1;
            }
            BlockState beside = chunk.getBlockState(next);
            if (beside.getBlock() == blockOf()) {
                int level = level(beside);
                return level >= 1 && level <= 7 ? level : -1;
            }
            if (beside.canOcclude() && beside.isCollisionShapeFullBlock(world, next)) {
                return -1;
            }
            return 0;
        }

        /** Tells the full blocks of this fluid beside this one to look again. */
        void neighboursChanged() {
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                var chunk = world.getChunkSource().getChunkNow(next.getX() >> 4, next.getZ() >> 4);
                if (chunk == null) {
                    continue;
                }
                BlockAwareAttachment attachment = BlockAwareAttachment.get(chunk, next);
                if (attachment != null && attachment.holder() != holder && attachment.holder() instanceof EdgeAware aware) {
                    aware.refreshEdges();
                }
            }
        }
    }

    private @Nullable Block block;

    private Block blockOf() {
        if (block == null) {
            for (var entry : BY_BLOCK.entrySet()) {
                if (entry.getValue() == this) {
                    block = entry.getKey();
                }
            }
        }
        return block;
    }

    /** A block of fluid drawn by its carrier, holding only the walls its exposed sides need. */
    private final class EdgeModel extends BlockModel implements EdgeAware {
        private final Edges edges;

        @Override
        public boolean startWatching(net.minecraft.server.network.ServerGamePacketListenerImpl player) {
            // The companion draws this fluid as a real one, walls and all
            return !me.drex.polymerpatcher.companion.CompanionServer.hasBlock(player.getPlayer(), blockOf()) && super.startWatching(player);
        }

        private EdgeModel(ServerLevel world, BlockPos pos, BlockState state) {
            this.edges = new Edges(world, pos.immutable(), this);
            edges.refresh(state);
            edges.neighboursChanged();
        }

        @Override
        public void refreshEdges() {
            edges.refresh(blockState());
        }

        @Override
        public void notifyUpdate(HolderAttachment.UpdateType updateType) {
            super.notifyUpdate(updateType);
            if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
                edges.refresh(blockState());
                edges.neighboursChanged();
            }
        }

        @Override
        protected void onAttachmentRemoved(HolderAttachment attachment) {
            super.onAttachmentRemoved(attachment);
            edges.neighboursChanged();
        }
    }

    private final class Model extends BlockModel implements EdgeAware {
        private final ItemDisplayElement element = ItemDisplayElementUtil.createSimple();

        @Override
        public boolean startWatching(net.minecraft.server.network.ServerGamePacketListenerImpl player) {
            return !me.drex.polymerpatcher.companion.CompanionServer.hasBlock(player.getPlayer(), blockOf()) && super.startWatching(player);
        }
        private final @Nullable Edges edges;

        /** What is being shown, so that a recheck that changes nothing sends nothing. */
        private @Nullable LazyItemStack showing;

        private final ServerLevel world;
        private final BlockPos pos;
        private boolean attached;

        private Model(ServerLevel world, BlockPos pos, BlockState state) {
            this.world = world;
            this.pos = pos.immutable();
            // A lake of acid is thousands of these, and a display with no size is drawn every frame in every
            // direction. Given a size the client culls the ones out of view; moved to the bottom of the block
            // with the model raised back by the same amount, the box covers the whole block and nothing moves
            element.setViewRange(ConfigManager.config().blocks.displayViewRange);
            element.setDisplaySize(1.5F, 1.5F);
            element.setOffset(new Vec3(0, -0.5, 0));
            element.setTranslation(new Vector3f(0, 0.5F, 0));
            this.edges = ConfigManager.config().blocks.fluidEdgeWalls ? new Edges(world, this.pos, this) : null;
            refresh(state);
            refreshVisibility();
            refreshBelow();
            if (edges != null) {
                edges.refresh(state);
                edges.neighboursChanged();
            }
        }

        @Override
        public void refreshEdges() {
            if (edges != null) {
                edges.refresh(blockState());
            }
        }

        @Override
        public void notifyUpdate(HolderAttachment.UpdateType updateType) {
            super.notifyUpdate(updateType);
            if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
                refresh(blockState());
                refreshVisibility();
                if (edges != null) {
                    edges.refresh(blockState());
                    edges.neighboursChanged();
                }
            }
        }

        @Override
        protected void onAttachmentRemoved(HolderAttachment attachment) {
            super.onAttachmentRemoved(attachment);
            // When this cell disappears, the surface immediately below it becomes exposed again.
            refreshBelow();
            if (edges != null) {
                edges.neighboursChanged();
            }
        }

        /**
         * A vertical flow is built bottom-first. Notify the already-existing cell below when this one
         * appears or disappears, otherwise it keeps the top face it chose before there was fluid above
         * and waterfalls look like stacks of separate cubes.
         */
        private void refreshBelow() {
            BlockPos below = pos.below();
            var chunk = world.getChunkSource().getChunkNow(below.getX() >> 4, below.getZ() >> 4);
            if (chunk == null) {
                return;
            }
            BlockAwareAttachment attachment = BlockAwareAttachment.get(chunk, below);
            if (attachment != null && attachment.holder() != this) {
                attachment.holder().notifyUpdate(BlockAwareAttachment.BLOCK_STATE_UPDATE);
            }
        }

        /**
         * A deep pool used to create a display for every cell in its volume. Only its exposed top layer
         * can be seen; cells with the same liquid immediately above keep the water physics carrier but do
         * not send an entity. getChunkNow is deliberate: this runs while attachments are being created,
         * and loading/querying a neighbouring chunk from here previously re-entered attachment creation
         * and could hang the server.
         */
        private void refreshVisibility() {
            BlockPos above = pos.above();
            var chunk = world.getChunkSource().getChunkNow(above.getX() >> 4, above.getZ() >> 4);
            boolean submerged = chunk != null && chunk.getBlockState(above).getBlock() == blockState().getBlock();
            if (submerged == attached) {
                if (submerged) {
                    removeElement(element);
                } else {
                    addElement(element);
                }
                attached = !submerged;
            }
        }

        /**
         * Picks the shape this block is now, and sends it only if it is not the one already being shown.
         */
        private void refresh(BlockState state) {
            LazyItemStack wanted = wanted(state);
            if (wanted == showing) {
                return;
            }

            showing = wanted;
            element.setItem(wanted.get());
            element.tick();
        }

        /**
         * The surface at this block's own depth. This intentionally does not inspect the block above.
         * Attachment creation happens while chunks are entering the world; asking another position for
         * its state or attachment from here can recursively create the neighbouring attachment and hang
         * the server on a chunk containing a large fluid pool. Matching the carrier height does not need
         * that neighbour query and still hides the water surface which prompted this renderer.
         */
        private LazyItemStack wanted(BlockState state) {
            int level = Math.min(state.hasProperty(LiquidBlock.LEVEL) ? state.getValue(LiquidBlock.LEVEL) : 0,
                models.length - 1);
            return tiled(ModdedFluids.modelFor(fluid, level), models[level], pos);
        }
    }
}
