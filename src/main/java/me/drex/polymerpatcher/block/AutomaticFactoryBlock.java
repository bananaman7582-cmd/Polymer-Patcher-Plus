package me.drex.polymerpatcher.block;

import com.mojang.datafixers.util.Pair;
import eu.pb4.factorytools.api.block.FactoryBlock;
import eu.pb4.factorytools.api.block.model.SignModel;
import eu.pb4.factorytools.api.block.model.generic.BSMMParticleBlock;
import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.BlockStateAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateMultiPartDefinition;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.resources.ResourcePackGenerator;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public record AutomaticFactoryBlock(
    Map<BlockState, BlockState> mappedStates,
    Set<BlockState> requiresElementHolder
) implements FactoryBlock, PolymerTexturedBlock, BSMMParticleBlock {

    private static final Map<Class<? extends Block>, Set<BlockState>> BLOCK_STATE_BLOCK_CACHE = new HashMap<>();
    private static final Map<ShapeKey, Set<BlockState>> COLLISION_BLOCK_STATE_CACHE = new HashMap<>();
    private static final Map<ShapeKey, Set<BlockModelType>> COLLISION_BLOCK_MODEL_CACHE = new HashMap<>();
    private static final Map<ShapeKey, CollisionModelMatch> CLOSEST_COLLISION_MODEL_CACHE = new HashMap<>();
    private static final double SHAPE_DISTANCE_EPSILON = 1e-9;

    /**
     * The box each carrier draws around itself when looked at, which is not the box it stops you with.
     * <p>
     * Collision alone cannot tell these apart: a torch, a plant and a mangrove propagule all stop you
     * with nothing at all, so by that measure any of them will do. What the player sees is the outline,
     * and the propagule's is a column the full height of the block - which is why Enderscape's torches
     * were being drawn with a hitbox reaching from the floor to the ceiling.
     */
    private static final Map<BlockModelType, ShapeKey> MODEL_TYPE_OUTLINE = new EnumMap<>(BlockModelType.class);

    /**
     * Which carriers the game shifts sideways by a little, worked out from the position they are at.
     * <p>
     * Saplings, grass and flowers are all placed off-centre on purpose, so a field of them does not look
     * like a grid. A carrier that does this drags whatever is drawn on it along too, and the propagule
     * standing in for a torch is a sapling - so every torch sat somewhere different in its block, and
     * none of them in the middle.
     */
    private static final Map<BlockModelType, Boolean> MODEL_TYPE_OFFSET = new EnumMap<>(BlockModelType.class);

    /**
     * Carriers that hurt whoever stands in them.
     * <p>
     * Polymer picks the vanilla blocks it hides models inside, and it has a great many spare states of
     * {@code minecraft:fire} to use - which is fine as a container and very much not fine to stand in.
     * Enderscape's void torches were being carried by it, so walking into a torch set the player on
     * fire. A stand-in is meant to be a shape wearing a texture and nothing else; whatever else it does
     * to the player is a bug however good the shape is.
     * <p>
     * Judged from the carrier the game hands back rather than by name, so anything else that burns,
     * freezes or pricks is caught the same way.
     */
    private static final Map<BlockModelType, Boolean> MODEL_TYPE_HARMFUL = new EnumMap<>(BlockModelType.class);

    /** Vanilla blocks that do something to a player standing in or on them. */
    private static final Set<Block> HURTS = Set.of(
        Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW,
        Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POINTED_DRIPSTONE, Blocks.LAVA
    );

    // Special block types
    private static final Set<BlockModelType> BIOME_COLOR = Set.of(BlockModelType.BIOME_COLORED_LEAVES, BlockModelType.BIOME_COLORED_LEAVES_WATERLOGGED, BlockModelType.BIOME_PLANT);
    private static final Set<BlockModelType> TRANSPARENT = new HashSet<>() {{
        addAll(
            Set.of(
                BlockModelType.LEAVES, BlockModelType.LEAVES_WATERLOGGED,
                BlockModelType.BIOME_COLORED_LEAVES, BlockModelType.BIOME_COLORED_LEAVES_WATERLOGGED,
                BlockModelType.PLANT, BlockModelType.BIOME_PLANT,
                BlockModelType.VINES, BlockModelType.KELP,
                BlockModelType.CACTUS, BlockModelType.TRIPWIRE,
                BlockModelType.TRIPWIRE_FLAT
            )
        );
        for (BlockModelType modelType : BlockModelType.values()) {
            String name = modelType.name();
            if (name.contains("CHAIN_")) {
                add(modelType);
            } else if (name.contains("LANTERN")) {
                add(modelType);
            } else if (name.contains("_DOOR")) {
                add(modelType);
            } else if (name.contains("_TRAPDOOR")) {
                add(modelType);
            } else if (name.contains("CAMPFIRE")) {
                add(modelType);
            } else if (name.contains("SCULK_SENSOR_BLOCK")) {
                add(modelType);
            } else if (name.contains("_SCAFFOLDING")) {
                add(modelType);
            } else if (name.contains("BARS_")) {
                add(modelType);
            }
        }
    }};
    private static final Set<BlockModelType> CLIMBABLE = Set.of(BlockModelType.VINES);

    /**
     * Carriers that do something when a player right-clicks them.
     * <p>
     * A stand-in is only ever meant to be a shape wearing a texture; it is not meant to answer the
     * player. An open fence gate has no collision at all, which makes it score exactly as well as a
     * plant or a torch by every measure this used to apply - so Enderscape's torches were handed one,
     * and clicking a torch swung the gate for a moment before the server put it back. A door, a
     * trapdoor, a button and a shelf all misbehave the same way.
     * <p>
     * So a carrier that answers is only used when nothing quieter fits.
     */
    private static final Set<BlockModelType> INTERACTIVE = new HashSet<>() {{
        for (BlockModelType modelType : BlockModelType.values()) {
            String name = modelType.name();
            // DOOR catches TRAPDOOR too. A bed is slept in and a campfire is cooked on, so both answer a
            // click every bit as much as a gate does - and a modded block carried by one of them was
            // setting the player's spawn, or eating whatever they were holding
            if (name.contains("GATE") || name.contains("DOOR") || name.contains("SHELF")
                || name.contains("PRESSURE_PLATE") || name.contains("SCULK_SENSOR")
                || name.contains("BED_") || name.contains("CAMPFIRE")) {
                add(modelType);
            }
        }
    }};

    /**
     * Carriers the client draws with a renderer of its own rather than from the blockstate model.
     * <p>
     * A skull is a block entity, and the client draws it by running its skull renderer - which reaches
     * for a profile and a skin and pays no attention to the model the pack put on that state. So a
     * modded plant carried by one is not a plant wearing the wrong texture, it is an actual severed
     * head standing in a flower bed, which is what Enderscape's magnia sprouts were doing.
     * <p>
     * Not forbidden, because a carrier is still better than no stand-in at all - just asked for last.
     */
    private static final Set<BlockModelType> SELF_DRAWN = new HashSet<>() {{
        for (BlockModelType modelType : BlockModelType.values()) {
            if (modelType.name().contains("HEAD") && !modelType.name().contains("BED_")) {
                add(modelType);
            }
        }
    }};

    /** How many states each kind of carrier had before any of them were handed out. */
    private static final Map<BlockModelType, Integer> POOL_CAPACITY = new EnumMap<>(BlockModelType.class);

    /** How many block states asked each kind for a carrier and were told there were none left. */
    private static final Map<BlockModelType, Integer> TURNED_AWAY = new EnumMap<>(BlockModelType.class);

    /** How many block states got a carrier, but not the one that would have suited them best. */
    private static final java.util.concurrent.atomic.AtomicInteger SETTLED_FOR_SECOND_BEST = new java.util.concurrent.atomic.AtomicInteger();

    private static final Set<BlockModelType> WATERLOGGED = new HashSet<>() {{
        for (BlockModelType modelType : BlockModelType.values()) {
            if (modelType.name().contains("_WATERLOGGED")) {
                add(modelType);
            }
        }
        add(BlockModelType.KELP);
    }};

    static {
        var world = PolymerCommonUtils.getFakeWorld();

        for (Map.Entry<ResourceKey<Block>, Block> entry : BuiltInRegistries.BLOCK.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey().identifier())).toList()) {
            Block block = entry.getValue();
            ResourceKey<Block> resourceKey = entry.getKey();
            // Asked of the game's own jar rather than of the name. Everything gathered here is a
            // candidate to be shown to a player in place of something they have not got, so a block that
            // only calls itself the game's own is the one thing that must never end up in this list -
            // handing it out means sending a block state number to a client that has no such block, and
            // the client ends the connection rather than read it. FallDrop Backport registers a hundred
            // blocks under "minecraft", and its stairs were being offered as a stand-in for other mods'
            if (!RegistryPatcher.isVanillaBlock(resourceKey.identifier())) {
                continue;
            }
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                // Some mods append states to real vanilla blocks. They still have a minecraft id and
                // asset, but their numeric state ids do not exist on an unmodified client, so they can
                // never be carriers. Sending one used to disconnect the client while reading a chunk.
                if (!BlockSyncCheck.isClientReadableWorldState(state)) {
                    continue;
                }
                try {
                    VoxelShape shape = state.getCollisionShape(world, BlockPos.ZERO);
                    ShapeKey key = ShapeKey.of(shape);

                    COLLISION_BLOCK_STATE_CACHE.computeIfAbsent(key, (x) -> new LinkedHashSet<>()).add(state);
                } catch (Exception ignored) {
                }
            }
            if (BlockSyncCheck.isClientReadableWorldState(block.defaultBlockState())) {
                BLOCK_STATE_BLOCK_CACHE.computeIfAbsent(block.getClass(), (x) -> new LinkedHashSet<>()).add(block.defaultBlockState());
            }
        }

        // Taken before the loop below, because asking for an empty carrier of a kind spends one of its
        // states. Without this there is nothing to compare what is left against, and "none left" reads
        // the same whether the pool held four states or eight hundred
        for (BlockModelType blockModel : BlockModelType.values()) {
            try {
                POOL_CAPACITY.put(blockModel, PolymerBlockResourceUtils.getBlocksLeft(blockModel));
            } catch (Throwable ignored) {
            }
        }

        for (BlockModelType blockModel : BlockModelType.values()) {
            BlockState mappedStateCandidate = requestReadableEmpty(blockModel);
            if (mappedStateCandidate == null) {
                continue;
            }
            VoxelShape shape = mappedStateCandidate.getCollisionShape(PolymerCommonUtils.getFakeWorld(), BlockPos.ZERO);
            ShapeKey key = ShapeKey.of(shape);
            // Heads are never offered. A skull is a block entity: the client draws it by running its
            // skull renderer, which takes no notice of the model the pack put on that state - even an
            // empty carrier of that type comes out as an actual severed head. A small centred box is a
            // shape almost nothing else has, so wherever a modded block had one the head was not the
            // worst candidate but the only one, and no amount of asking for it last could help. Left
            // out here instead, so such a block is matched to the nearest shape that can be drawn.
            if (!SELF_DRAWN.contains(blockModel)) {
                COLLISION_BLOCK_MODEL_CACHE.computeIfAbsent(key, (x) -> new LinkedHashSet<>()).add(blockModel);
            }

            try {
                MODEL_TYPE_OUTLINE.put(blockModel, ShapeKey.of(mappedStateCandidate.getShape(world, BlockPos.ZERO)));
                MODEL_TYPE_OFFSET.put(blockModel, mappedStateCandidate.hasOffsetFunction());
                MODEL_TYPE_HARMFUL.put(blockModel, HURTS.contains(mappedStateCandidate.getBlock()));
            } catch (Exception ignored) {
                // A carrier that will not say costs only its own place in the ordering below
            }
        }
    }

    public static AutomaticFactoryBlock create(Identifier id, Block originalBlock) {
        // Do not spend a scarce textured carrier on a block whose own render contract says every
        // placed state is invisible. Its inventory/particle model may still exist in the pack.
        if (originalBlock.getStateDefinition().getPossibleStates().stream()
            .allMatch(AutomaticFactoryBlock::isRendererInvisible)) {
            return new AutomaticFactoryBlock(Map.of(), Set.of());
        }
        // Some models have multiple block states that use the same state model variants
        // We don't want to waste blockstates for that, so we remember them
        // The boolean is used for waterlogged blockstates
        Map<ModelVariantKey, BlockState> modelVariantCache = new HashMap<>();

        List<Pair<BlockStatePredicate, List<StateModelVariant>>> parsedVariants = new ArrayList<>();
        try {
            BlockStateAsset blockStateAsset = ResourceHelper.decodeBlockState(id);
            blockStateAsset.variants().ifPresent(variants ->
                BlockStateModelManager.parseVariants(originalBlock, variants,
                    (predicate, models) -> parsedVariants.add(new Pair<>(predicate, models))));

            // A multipart file does not necessarily describe a model made from several pieces. Double-height
            // plants commonly use multipart merely as a switch: exactly one entry matches each state. Those
            // used to be rejected wholesale and drawn as one item-display entity per placed block. That is
            // especially expensive for world-generation plants: Dungeons and Taverns' Nether places roughly
            // thirty-eight thousand nether-wart fluff blocks in this world alone.
            //
            // Where exactly one part matches a state it is semantically the same as a normal variant, so turn
            // it into an exact-state variant and let Polymer put it on a real block carrier. Genuine composite
            // multipart states (walls, fences, connected panes, and so on) still take the display path because
            // a carrier's weighted model list cannot combine several models into one.
            blockStateAsset.multipart().ifPresent(parts -> addSimpleMultipartVariants(originalBlock, parts, parsedVariants));
            if (blockStateAsset.variants().isEmpty() && blockStateAsset.multipart().isEmpty()) {
                NO_VARIANTS.incrementAndGet();
            }
        } catch (Exception e) {
            // Counted rather than announced per block. Most of these are simply blocks described with
            // "multipart" instead of "variants" - walls, fences, panes - which is an ordinary way to
            // write a blockstate and not a fault; they are drawn by a display instead. Saying so for
            // every one of them filled the log with warnings about nothing going wrong
            NO_VARIANTS.incrementAndGet();
            PolymerPatcher.LOGGER.debug("Could not read the variants of {}", id, e);
        }

        Map<BlockState, BlockState> mappedStates = new HashMap<>();
        Set<BlockState> requiresElementHolder = new HashSet<>();
        originalBlock.getStateDefinition().getPossibleStates().forEach(state -> {
                BlockState mappedState = findMatchingState(id, state, parsedVariants, modelVariantCache, requiresElementHolder);
                // A dry source sent as a wet vanilla carrier creates real client-side water. Keep this
                // invariant at the boundary as well as in every allocator path, so a new fallback can
                // never quietly turn stairs, slabs or leaves into water sources again.
                if (!CarrierStateSafety.isWaterSafeCarrier(state, mappedState)) {
                    PolymerPatcher.LOGGER.debug("Rejected waterlogged carrier {} for dry blockstate {}", mappedState, state);
                    requiresElementHolder.add(state);
                    mappedState = null;
                }
                mappedStates.put(state, mappedState);
            }
        );
        count(id, mappedStates, requiresElementHolder);
        return new AutomaticFactoryBlock(mappedStates, requiresElementHolder);
    }

    private static void addSimpleMultipartVariants(
        Block block,
        List<StateMultiPartDefinition> parts,
        List<Pair<BlockStatePredicate, List<StateModelVariant>>> parsedVariants
    ) {
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            StateMultiPartDefinition only = null;
            boolean composite = false;
            for (StateMultiPartDefinition part : parts) {
                if (!part.when().map(condition -> multipartMatches(state, condition)).orElse(true)) {
                    continue;
                }
                if (only != null) {
                    composite = true;
                    break;
                }
                only = part;
            }
            if (only == null || composite) {
                continue;
            }

            BlockStatePredicate exact = BlockStatePredicate.forBlock(block);
            for (Property.Value<?> value : state.getValues().toList()) {
                requireExactValue(exact, value);
            }
            parsedVariants.add(new Pair<>(exact, only.apply()));
        }
    }

    private static boolean multipartMatches(BlockState state, StateMultiPartDefinition.Condition condition) {
        if (condition instanceof StateMultiPartDefinition.KeyValueCondition keyValues) {
            for (var test : keyValues.tests().entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(test.getKey());
                if (property == null || !multipartTermsMatch(state, property, test.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (condition instanceof StateMultiPartDefinition.CombinedCondition combined) {
            return switch (combined.operation()) {
                case AND -> combined.terms().stream().allMatch(term -> multipartMatches(state, term));
                case OR -> combined.terms().stream().anyMatch(term -> multipartMatches(state, term));
            };
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean multipartTermsMatch(
        BlockState state,
        Property property,
        StateMultiPartDefinition.KeyValueCondition.Terms terms
    ) {
        String actual = property.getName((Comparable) state.getValue(property));
        return terms.entries().stream().anyMatch(term -> term.negated()
            ? !actual.equals(term.value())
            : actual.equals(term.value()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void requireExactValue(BlockStatePredicate predicate, Property.Value<?> value) {
        Property property = value.property();
        Comparable expected = value.value();
        predicate.where(property, candidate -> expected.equals(candidate));
    }

    /** How each block state ended up being shown, so the log can say it rather than be guessed at. */
    private static final java.util.concurrent.atomic.AtomicInteger ON_A_CARRIER = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ON_A_DISPLAY = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger UNMAPPED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger NO_VARIANTS = new java.util.concurrent.atomic.AtomicInteger();
    private static final Set<Identifier> UNMAPPED_BLOCKS = new LinkedHashSet<>();

    private static void count(Identifier id, Map<BlockState, BlockState> mappedStates, Set<BlockState> requiresElementHolder) {
        for (Map.Entry<BlockState, BlockState> entry : mappedStates.entrySet()) {
            if (entry.getValue() == null) {
                UNMAPPED.incrementAndGet();
                synchronized (UNMAPPED_BLOCKS) {
                    UNMAPPED_BLOCKS.add(id);
                }
            } else if (requiresElementHolder.contains(entry.getKey())) {
                ON_A_DISPLAY.incrementAndGet();
            } else {
                ON_A_CARRIER.incrementAndGet();
            }
        }
    }

    /**
     * Says how the server's modded blocks are being shown.
     * <p>
     * Worth saying out loud because the three answers look very different in game and there is no other
     * way to tell them apart. A state on a carrier is a real block wearing the right model. A state on a
     * display is drawn by an entity instead, because the carrier types it needed were all spoken for -
     * it looks right but costs more. A state with neither is a barrier: solid, untextured, and the thing
     * a player reports as "that block has no texture".
     */
    public static void reportMapping() {
        int unmapped = UNMAPPED.get();
        PolymerPatcher.LOGGER.info("Modded block states: {} on a vanilla carrier, {} drawn by a display, {} with no stand-in at all ({} block(s) described without variants)",
            ON_A_CARRIER.get(), ON_A_DISPLAY.get(), unmapped, NO_VARIANTS.get());

        if (!SHORT_SIGHTED.isEmpty()) {
            java.util.Map<String, Integer> byBlock = new java.util.TreeMap<>();
            for (BlockState state : SHORT_SIGHTED) {
                byBlock.merge(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())), 1, Integer::sum);
            }
            PolymerPatcher.LOGGER.info("{} block(s) had no carrier left and are drawn as short-sighted displays "
                + "(blocks.fallbackDisplayViewRange); if an area is slow, these are the likeliest cause: {}", byBlock.size(), byBlock);
        }

        if (unmapped > 0) {
            synchronized (UNMAPPED_BLOCKS) {
                PolymerPatcher.LOGGER.warn("These blocks have states a vanilla client is shown as a barrier: {}", UNMAPPED_BLOCKS);
            }
        }

        reportCarrierBudget();
    }

    /**
     * Says where the carriers went, and which kinds ran out.
     * <p>
     * A block drawn by a display is not a block: it is an entity standing where a block should be, seen
     * only as far as entities are seen and paid for one at a time. A cave made of them looks empty from
     * across the room and costs a frame rate to stand in. That happens for one reason - the kind of
     * carrier that block needed was already spent - and which kind that was has never been written down
     * anywhere, so there has been no way to tell a shortage that matters from one that does not.
     * <p>
     * A type with nothing left is where the losses are. A type with plenty left is capacity going
     * unused, and worth knowing about before anybody argues over how to divide what is scarce.
     */
    private static void reportCarrierBudget() {
        record Pool(BlockModelType type, int capacity, int left, int turnedAway) {
        }

        List<Pool> pools = new ArrayList<>();
        int totalCapacity = 0;
        int totalLeft = 0;

        for (BlockModelType type : BlockModelType.values()) {
            int left;
            try {
                left = PolymerBlockResourceUtils.getBlocksLeft(type);
            } catch (Throwable e) {
                continue;
            }
            int capacity = POOL_CAPACITY.getOrDefault(type, left);
            totalCapacity += capacity;
            totalLeft += left;
            pools.add(new Pool(type, capacity, left, TURNED_AWAY.getOrDefault(type, 0)));
        }

        PolymerPatcher.LOGGER.info("Carriers: {} of {} vanilla block states spent, {} left across {} kinds; {} state(s) had to settle for a carrier that was not their best fit",
            totalCapacity - totalLeft, totalCapacity, totalLeft, pools.size(), SETTLED_FOR_SECOND_BEST.get());

        // Ordered by what the shortage actually cost rather than by whether there is a zero in the
        // column. A kind with nothing left that nothing else wanted is not a shortage; a kind that
        // turned away four thousand states is the whole problem, and the two used to read alike
        List<Pool> pressed = new ArrayList<>(pools);
        pressed.removeIf(pool -> pool.turnedAway() == 0);
        pressed.sort(Comparator.comparingInt(Pool::turnedAway).reversed());

        if (!pressed.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Pool pool : pressed.subList(0, Math.min(pressed.size(), 24))) {
                lines.add(pool.type().name() + " held " + pool.capacity() + ", " + pool.left() + " left, turned away " + pool.turnedAway());
            }
            PolymerPatcher.LOGGER.info("Carrier kinds that ran short ({} of {}), worst first: {}", pressed.size(), pools.size(), lines);
            PolymerPatcher.LOGGER.info("A state turned away by every kind that suited it is drawn by a display instead, "
                + "which is what makes a modded cave look empty at a distance and cost a frame rate to stand in.");
        }

        List<String> spare = new ArrayList<>();
        for (Pool pool : pools) {
            if (pool.left() >= 64) {
                spare.add(pool.type().name() + "=" + pool.left());
            }
        }
        if (!spare.isEmpty()) {
            PolymerPatcher.LOGGER.info("Carrier kinds still with room: {}", spare);
        }
    }

    private static BlockState findMatchingState(
        Identifier id, BlockState originalState,
        List<Pair<BlockStatePredicate, List<StateModelVariant>>> parsedVariants,
        Map<ModelVariantKey, BlockState> modelVariantCache,
        Set<BlockState> requiresElementHolder
    ) {
        // A block can hold water without a waterlogged property - kelp-like blocks keep water in their
        // fluid state instead. Its stand-in has to hold the water too, or each one is an air pocket
        Boolean waterLogged = originalState.getValueOrElse(BlockStateProperties.WATERLOGGED, false)
            || (originalState.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER);

        VoxelShape collisionShape = originalState.getCollisionShape(PolymerCommonUtils.getFakeWorld(), BlockPos.ZERO);

        ShapeKey collisionKey = ShapeKey.of(collisionShape);

        Block block = originalState.getBlock();
        BlockState result = switch (block) {
            case SignBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            case ButtonBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            case PressurePlateBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            case FenceGateBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            case FenceBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            case WallBlock ignored -> findMatchingBlockClass(id, originalState, requiresElementHolder);
            default -> null;
        };
        if (result != null) {
            return result;
        }

        result = findMatchingBlockModelType(id, originalState, parsedVariants, modelVariantCache,
            requiresElementHolder, collisionKey, collisionShape.isEmpty(), waterLogged);
        if (result != null) {
            return result;
        }

/*        Set<BlockState> mappedStateCandidates = COLLISION_BLOCK_STATE_CACHE.get(collisionKey);
        if (mappedStateCandidates != null) {
            ResourcePackGenerator.expandBlockModel(id);
            requiresElementHolder.add(originalState);
            return pickBlockStateCandidate(id, originalState, mappedStateCandidates).trySetValue(BlockStateProperties.WATERLOGGED, waterLogged);
        }*/
        requiresElementHolder.add(originalState);
        return null;
    }

    private static BlockState findMatchingBlockModelType(
        Identifier id, BlockState originalState,
        List<Pair<BlockStatePredicate, List<StateModelVariant>>> parsedVariants,
        Map<ModelVariantKey, BlockState> modelVariantCache,
        Set<BlockState> requiresElementHolder, ShapeKey collisionKey,
        boolean collisionEmpty, boolean waterLogged
    ) {
        CollisionModelMatch collisionMatch = findClosestBlockModelTypes(collisionKey);
        if (collisionMatch != null) {
            Set<BlockModelType> blockModelTypeCandidates = collisionMatch.candidates();
            for (var parsedVariant : parsedVariants) {
                if (parsedVariant.getFirst().test(originalState)) {
                    Set<BlockModelType> blockModelTypes = pickBlockModelTypeCandidate(id, originalState, blockModelTypeCandidates);
                    boolean firstChoice = true;
                    for (BlockModelType blockModelType : blockModelTypes) {
                        ModelVariantKey key = new ModelVariantKey(parsedVariant.getSecond(), waterLogged, blockModelType);
                        BlockState cachedState = modelVariantCache.get(key);
                        if (cachedState != null) {
                            PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> {} (cached {}, shape distance {})", originalState, cachedState, blockModelType, collisionMatch.distance());
                            return cachedState;
                        }

                        int blocksLeft = PolymerBlockResourceUtils.getBlocksLeft(blockModelType);
                        if (blocksLeft > 0) {
                            PolymerBlockModel[] models = parsedVariant.getSecond().stream()
                                .map(x -> new PolymerBlockModel(x.model(), x.x(), x.y(), x.uvlock(), x.weigth()))
                                .toArray(PolymerBlockModel[]::new);
                            BlockState state = requestReadableBlock(blockModelType, models);
                            if (state != null) {
                                if (!firstChoice) {
                                    SETTLED_FOR_SECOND_BEST.incrementAndGet();
                                }
                                modelVariantCache.put(key, state);
                                PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> {} ({} {} blocks left, shape distance {})", originalState, state, blockModelType, blocksLeft, collisionMatch.distance());
                                return state;
                            }
                        }
                        // Counted per state rather than per block, because what matters is how much a
                        // pool running dry actually cost - a kind with nothing left and nothing wanting
                        // it is not a shortage, and a kind that turned away four thousand states is
                        TURNED_AWAY.merge(blockModelType, 1, Integer::sum);
                        firstChoice = false;
                    }
                }
            }
            // Slabs and collision-free decoration are the two shapes most often repeated by the thousand
            // in terrain. Once their small model-bearing carrier pools are spent, one display per block is
            // much worse than a same-shaped vanilla approximation: it made whole modded dimensions fall to
            // zero FPS. This used to name one mod's slabs and one of its plants. The reason is structural,
            // though, so apply it to every mod while preserving the exact custom model whenever a carrier
            // was available above.
            // A plant, vine or anything else you walk through, with every carrier that fitted it gone. This
            // used to hand back the nearest vanilla block with the same collision, which is how willow vines
            // became acacia saplings and ivy became mushrooms - cheap, and plainly wrong. It is drawn as what it
            // is now, by a display that is kept short-sighted so a forest of them costs little (see
            // BlockConfig.fallbackDisplayViewRange), over a structure void: invisible, nothing to walk into,
            // and a small box in the middle to aim at, so it can still be seen through and broken
            if (collisionEmpty && !originalState.canOcclude() && !(originalState.getBlock() instanceof SlabBlock)) {
                requiresElementHolder.add(originalState);
                SHORT_SIGHTED.add(originalState);
                PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> display over a structure void (no carrier left)", originalState);
                // Under water the display stands in the water rather than in a void cut out of it
                return waterLogged ? Blocks.WATER.defaultBlockState() : Blocks.STRUCTURE_VOID.defaultBlockState();
            }
            if (originalState.getBlock() instanceof SlabBlock && me.drex.polymerpatcher.config.ConfigManager.config().blocks.vanillaSlabsWhenOutOfCarriers) {
                BlockState vanilla = closestSafeVanillaState(originalState, collisionKey);
                if (vanilla != null) {
                    PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> {} (high-volume terrain fallback)", originalState, vanilla);
                    return vanilla;
                }
            }
            // Nothing above could be had, so the block is drawn by a display and what is underneath it is
            // only a shape to bump into. A last resort may relax cosmetic/interaction preferences, but it
            // must not put actual client-side water in a dry block. If no dry carrier remains, returning
            // null deliberately falls back to a barrier underneath the display; an imperfect collision
            // box is much less destructive than flooding an entire stair build on vanilla clients.
            Set<BlockModelType> blockModelTypes = pickBlockModelTypeCandidate(id, originalState, blockModelTypeCandidates);
            if (blockModelTypes.isEmpty()) {
                blockModelTypes = lastResortCandidates(originalState, blockModelTypeCandidates);
            }
            for (BlockModelType blockModelType : blockModelTypes) {
                requiresElementHolder.add(originalState);
                var state = requestReadableEmpty(blockModelType);
                if (state == null) {
                    continue;
                }
                PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> {} ({} no variants left, shape distance {})", originalState, state, blockModelType, collisionMatch.distance());
                if (blockModelType == BlockModelType.FULL_BLOCK) {
                    return Blocks.BARRIER.defaultBlockState();
                }
                return state;
            }
        }
        return null;
    }

    /** Spend and discard server-only additions until the carrier library returns a real client state. */
    /** States drawn by a display only because the carriers ran out, which are drawn nearer; see above. */
    private static final Set<BlockState> SHORT_SIGHTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Nullable
    private static BlockState requestReadableBlock(BlockModelType type, PolymerBlockModel[] models) {
        int attempts = Math.max(1, PolymerBlockResourceUtils.getBlocksLeft(type));
        while (attempts-- > 0) {
            BlockState state = PolymerBlockResourceUtils.requestBlock(type, models);
            if (state == null || BlockSyncCheck.isClientReadableWorldState(state)) {
                return state;
            }
        }
        return null;
    }

    /** Equivalent guard for shape-only carriers used underneath display-rendered blocks. */
    @Nullable
    private static BlockState requestReadableEmpty(BlockModelType type) {
        int attempts = Math.max(1, PolymerBlockResourceUtils.getBlocksLeft(type));
        while (attempts-- > 0) {
            BlockState state = PolymerBlockResourceUtils.requestEmpty(type);
            if (state == null || BlockSyncCheck.isClientReadableWorldState(state)) {
                return state;
            }
        }
        return null;
    }

    /** A visible, non-harmful vanilla block with the same collision, closest to the source map colour. */
    @Nullable
    private static BlockState closestSafeVanillaState(BlockState original, ShapeKey collisionKey) {
        Set<BlockState> exact = COLLISION_BLOCK_STATE_CACHE.get(collisionKey);
        if (exact == null || exact.isEmpty()) {
            return null;
        }

        Set<BlockState> safe = new LinkedHashSet<>();
        for (BlockState candidate : exact) {
            Block block = candidate.getBlock();
            if (candidate.isAir() || block instanceof LiquidBlock || HURTS.contains(block)
                || candidate.hasBlockEntity() || block instanceof ButtonBlock || block instanceof PressurePlateBlock
                || block instanceof FenceGateBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || !CarrierStateSafety.isWaterSafeCarrier(original, candidate)) {
                continue;
            }
            safe.add(candidate);
        }
        if (safe.isEmpty()) {
            return null;
        }

        BlockState picked = pickBlockStateCandidate(BuiltInRegistries.BLOCK.getKey(original.getBlock()), original, safe);
        for (Property.Value<?> value : original.getValues().toList()) {
            picked = copyProperty(picked, (Property) value.property(), value.value());
        }
        return picked;
    }

    /**
     * Finds the Polymer model type backed by the closest vanilla collision shape. Exact matches still
     * win, while unusual modded shapes now get a useful approximation instead of always becoming a
     * full barrier cube on vanilla clients.
     */
    private static CollisionModelMatch findClosestBlockModelTypes(ShapeKey collisionKey) {
        Set<BlockModelType> exact = COLLISION_BLOCK_MODEL_CACHE.get(collisionKey);
        if (exact != null) {
            return new CollisionModelMatch(exact, 0);
        }

        return CLOSEST_COLLISION_MODEL_CACHE.computeIfAbsent(collisionKey, key -> {
            double closestDistance = Double.POSITIVE_INFINITY;
            Set<BlockModelType> closestCandidates = new LinkedHashSet<>();

            for (Map.Entry<ShapeKey, Set<BlockModelType>> entry : COLLISION_BLOCK_MODEL_CACHE.entrySet()) {
                double distance = key.distanceTo(entry.getKey());
                if (distance + SHAPE_DISTANCE_EPSILON < closestDistance) {
                    closestDistance = distance;
                    closestCandidates.clear();
                    closestCandidates.addAll(entry.getValue());
                } else if (Math.abs(distance - closestDistance) <= SHAPE_DISTANCE_EPSILON) {
                    closestCandidates.addAll(entry.getValue());
                }
            }

            return closestCandidates.isEmpty()
                ? null
                : new CollisionModelMatch(Collections.unmodifiableSet(new LinkedHashSet<>(closestCandidates)), closestDistance);
        });
    }

    private record ModelVariantKey(List<StateModelVariant> variants, boolean waterlogged, BlockModelType blockModelType) {
    }

    private record CollisionModelMatch(Set<BlockModelType> candidates, double distance) {
    }

    private static BlockState findMatchingBlockClass(
        Identifier id, BlockState originalState,
        Set<BlockState> requiresElementHolder
    ) {
        Set<BlockState> mappedStateCandidates = candidatesFor(originalState.getBlock().getClass());
        if (mappedStateCandidates != null) {
            ResourcePackGenerator.expandBlockModel(id);
            requiresElementHolder.add(originalState);
            BlockState pickedBlockState = pickBlockStateCandidate(id, originalState, mappedStateCandidates);

            // getValues() hands back a stream of property/value pairs in 26.2 rather than a map
            for (Property.Value<?> value : originalState.getValues().toList()) {
                pickedBlockState = copyProperty(pickedBlockState, (Property) value.property(), value.value());
            }
            PolymerPatcher.LOGGER.debug("Mapping blockstate {} -> {} (matching block class)", originalState, pickedBlockState);
            return pickedBlockState;
        }
        return null;
    }


    /**
     * The vanilla blocks worth standing in for one of this kind, found by what the block <em>is</em>
     * rather than by the exact class it happens to be.
     * <p>
     * The lookup used to be by exact class, which works only while a mod builds its content out of the
     * game's own classes. A mod that writes its own - and one adding a sign has every reason to - found
     * nothing, and fell through to being matched on collision shape alone. That is how Enderscape's
     * hanging signs ended up as copper bars and chains: the right size and shape to bump into, and not
     * a sign, so there was nothing to write on and nothing that behaved like one.
     * <p>
     * So a class with no entry of its own is answered with its nearest ancestor's. A mod's hanging sign
     * still extends the game's hanging sign, and that is the thing worth showing.
     */
    @Nullable
    private static Set<BlockState> candidatesFor(Class<?> blockClass) {
        Set<BlockState> exact = BLOCK_STATE_BLOCK_CACHE.get(blockClass);
        if (exact != null) {
            return exact;
        }

        // Up the chain, so the closest kind wins: a wall hanging sign before a sign before a block.
        //
        // With a floor, though. The game's own signs are CeilingHangingSignBlock and the rest; SignBlock
        // itself is abstract and never appears as a key, so a mod writing its own sign class walked
        // straight past it, past BaseEntityBlock, and landed on Block - which IS a key, because stone
        // and dirt and planks are all plain Blocks. That handed a hanging sign a full cube to stand in:
        // the wrong size to walk into, and a whole block's worth of cracks appearing underneath the sign
        // being drawn on top of it. Past a certain height every class is "a block" and the answer stops
        // meaning anything, so the walk stops there and the shape match is used instead
        for (Class<?> type = blockClass.getSuperclass(); type != null && Block.class.isAssignableFrom(type); type = type.getSuperclass()) {
            if (tooGeneralToMeanAnything(type)) {
                break;
            }

            Set<BlockState> inherited = BLOCK_STATE_BLOCK_CACHE.get(type);
            if (inherited != null) {
                return inherited;
            }
        }

        return null;
    }

    /** Classes so far up the tree that "is one of these" says nothing about what the block looks like. */
    private static boolean tooGeneralToMeanAnything(Class<?> type) {
        return type == Block.class
            || type == BaseEntityBlock.class
            || type == net.minecraft.world.level.block.state.BlockBehaviour.class;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(
        BlockState state,
        Property<T> property,
        Comparable<?> value
    ) {
        // Tried rather than set. The stand-in is no longer always the same class as the block it
        // stands for - a mod's own sign is answered with the game's - so it may not have every
        // property the original does, and insisting would throw away the whole mapping over one
        return state.trySetValue(property, property.getValueClass().cast(value));
    }

    /** A carrier that answers a right-click, which a stand-in is never meant to do. */
    private static final int PENALTY_INTERACTIVE = 1000;
    /** A waterlogged block on a dry carrier: the water it should be standing in is missing. */
    private static final int PENALTY_NO_WATER = 300;
    /** A carrier the game shifts off-centre, dragging whatever is drawn on it along. */
    private static final int PENALTY_WANDERS = 100;
    /** A see-through block on a solid carrier: neighbours are culled behind it and light stops. */
    private static final int PENALTY_SEE_THROUGH_ON_SOLID = 60;
    /** A solid block on a see-through carrier: nothing is culled against it, so more is drawn. */
    private static final int PENALTY_SOLID_ON_SEE_THROUGH = 30;
    /** A block that should take its colour from the biome, on a carrier that cannot give it one. */
    private static final int PENALTY_LOST_TINT = 50;
    /** A block that wants no tint on a carrier that tints - only felt if its model asks to be tinted. */
    private static final int PENALTY_UNWANTED_TINT = 20;
    /** A carrier that can be climbed when the block cannot, or the other way about. */
    private static final int PENALTY_CLIMBABLE = 20;
    /** How much being drawn on the wrong outline can ever count for, against the faults above. */
    private static final double OUTLINE_PENALTY_CAP = 10.0;

    /**
     * Every carrier that could stand in for this block, best first.
     * <p>
     * This used to narrow rather than order: each preference threw away the candidates that failed it and
     * whatever survived the last one was the answer. Two things went wrong with that.
     * <p>
     * A preference that would have emptied the set was skipped in silence, so on those blocks it had no
     * effect at all. That is why a dry modded stair was being drawn standing in water: the stairs pools
     * hold four states each and were spent, so "not waterlogged" would have left nothing, so it was passed
     * over, and the waterlogged twin was handed out instead.
     * <p>
     * And narrowing to a single carrier meant that when its pool ran dry there was nowhere left to go, so
     * the block fell to a display entity even while an almost-as-good pool sat untouched beside it. The
     * see-through full-cube pool has been empty since the thirteenth modded block that wanted one, while
     * the biome-tinted leaf pool - the same shape, the same collision, a hundred and twenty-eight states -
     * had gone practically unused, because a block that wants no tint was forbidden from a carrier that
     * can give one. It only actually gives one to a model that asks to be tinted, and few do.
     * <p>
     * So every candidate is kept and scored, and the caller walks the list until it finds one with room.
     * Two rules stay absolute rather than scored: a carrier that hurts whoever stands in it is never used,
     * and a dry block never gets a waterlogged carrier, because the client fills it with water and no
     * amount of correct shading hides that. A waterlogged block on a dry carrier is only missing water,
     * which is a far quieter fault, so that one stays a preference.
     */
    private static Set<BlockModelType> pickBlockModelTypeCandidate(Identifier id, BlockState original, Set<BlockModelType> mappedBlockModelCandidates) {
        Block block = original.getBlock();
        RenderRegistry.BlockInfo blockInfo = PolymerPatcher.getRenderRegistry().blockInfoByBlock.get(block);
        boolean biomeColor = blockInfo != null && blockInfo.biomeColor() != null;
        // TODO check tag?
        boolean climbable = false;
        // Render dumps know whether a model uses a cutout/translucent layer, but a missing or old dump
        // must not turn a non-occluding plant into a solid carrier which culls the ground underneath it.
        // The block's own occlusion property is server-side and works for every mod.
        boolean transparent = (blockInfo != null && blockInfo.transparent()) || !original.canOcclude();
        boolean waterLogged = original.getValueOrElse(BlockStateProperties.WATERLOGGED, false)
            || (original.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER);
        boolean wanders = original.hasOffsetFunction();
        ShapeKey outline = outlineOf(original);

        record Scored(BlockModelType type, double penalty) {
        }

        List<Scored> scored = new ArrayList<>();
        for (BlockModelType candidate : mappedBlockModelCandidates) {
            if (MODEL_TYPE_HARMFUL.getOrDefault(candidate, false)) {
                continue;
            }
            boolean carrierHoldsWater = WATERLOGGED.contains(candidate);
            if (carrierHoldsWater && !waterLogged) {
                continue;
            }
            // Climbing is the client's to decide - it climbs whatever its own block says can be climbed -
            // so a block that cannot be climbed, carried on weeping vines, could be climbed by anyone who
            // walked into it. A penalty was not enough: once The Sift filled the plant carriers, the vines
            // came out best anyway. The other way round only loses a ladder's grip, and stays a penalty
            if (!climbable && CLIMBABLE.contains(candidate)) {
                continue;
            }
            // An open gate is the one carrier with no collision that is not a plant, so once the plants ran
            // out every walk-through block landed on one - and the client swings a gate itself the moment it
            // is clicked, so clicking any of them turned it into something else until the server answered.
            // A penalty did not stop it for the same reason it did not stop the vines. Real gates are matched
            // by their class before this, so a gate here is only ever carrying something that is not one
            if (candidate.name().startsWith("GATE_") && !(original.getBlock() instanceof FenceGateBlock)) {
                continue;
            }

            double penalty = 0;
            if (waterLogged && !carrierHoldsWater) {
                penalty += PENALTY_NO_WATER;
            }
            if (INTERACTIVE.contains(candidate)) {
                penalty += PENALTY_INTERACTIVE;
            }
            if (MODEL_TYPE_OFFSET.getOrDefault(candidate, false) != wanders) {
                penalty += PENALTY_WANDERS;
            }
            boolean carrierSeeThrough = TRANSPARENT.contains(candidate);
            if (transparent && !carrierSeeThrough) {
                penalty += PENALTY_SEE_THROUGH_ON_SOLID;
            } else if (!transparent && carrierSeeThrough) {
                penalty += PENALTY_SOLID_ON_SEE_THROUGH;
            }
            boolean carrierTints = BIOME_COLOR.contains(candidate);
            if (biomeColor && !carrierTints) {
                penalty += PENALTY_LOST_TINT;
            } else if (!biomeColor && carrierTints) {
                penalty += PENALTY_UNWANTED_TINT;
            }
            if (climbable != CLIMBABLE.contains(candidate)) {
                penalty += PENALTY_CLIMBABLE;
            }
            if (outline != null) {
                ShapeKey carrierOutline = MODEL_TYPE_OUTLINE.get(candidate);
                penalty += carrierOutline == null
                    ? OUTLINE_PENALTY_CAP
                    : Math.min(outline.distanceTo(carrierOutline), OUTLINE_PENALTY_CAP);
            }

            scored.add(new Scored(candidate, penalty));
        }

        // Stable, so carriers nothing here separates keep the order the shape match gave them
        scored.sort(Comparator.comparingDouble(Scored::penalty));

        Set<BlockModelType> ordered = new LinkedHashSet<>();
        for (Scored entry : scored) {
            ordered.add(entry.type());
        }
        return ordered;
    }

    /**
     * What to stand a display on when every carrier that could have been used was ruled out.
     * <p>
     * Cosmetic preferences can be relaxed here, but wet-for-dry cannot: waterlogging is client behaviour,
     * not a visual imperfection. Wet sources may still use dry carriers if that is all that remains.
     */
    private static Set<BlockModelType> lastResortCandidates(BlockState original, Set<BlockModelType> candidates) {
        boolean waterLogged = original.getValueOrElse(BlockStateProperties.WATERLOGGED, false)
            || (original.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER);

        List<BlockModelType> ordered = new ArrayList<>(candidates);
        if (!waterLogged) {
            ordered.removeIf(WATERLOGGED::contains);
        }
        ordered.sort(Comparator.comparingInt(candidate ->
            (MODEL_TYPE_HARMFUL.getOrDefault(candidate, false) ? 2 : 0)
                + (WATERLOGGED.contains(candidate) != waterLogged ? 1 : 0)));
        return new LinkedHashSet<>(ordered);
    }

    @Nullable
    private static ShapeKey outlineOf(BlockState state) {
        try {
            return ShapeKey.of(state.getShape(PolymerCommonUtils.getFakeWorld(), BlockPos.ZERO));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Which vanilla block of the right kind to stand underneath this one.
     * <p>
     * A wall, a fence, a sign and a button are matched by what they are rather than by their shape,
     * because their shape is not the point - a wall has to connect to its neighbours like a wall, and
     * only a real one does. The model on top is drawn by a display, so the vanilla block underneath is
     * only there to be walked into and connected to.
     * <p>
     * It is still seen, though. The display draws the modded model over it and the two are never quite
     * the same size, so the block underneath shows at the edges - and it used to be whichever sorted
     * first, which for every wall on the server was andesite. That is why gingerbread walls had grey
     * stone poking out of them.
     * <p>
     * Nothing here can stop it being seen, but it can be made to matter less: of the blocks that would
     * all do the job equally, take whichever is nearest the modded one in colour. Grey behind
     * gingerbread becomes brown behind gingerbread, and an edge that was obvious stops being.
     */
    private static BlockState pickBlockStateCandidate(Identifier id, BlockState original, Set<BlockState> mappedStateCandidates) {
        if (original.getBlock() instanceof FlowerPotBlock) {
            return pickBlockStateCandidate0(blockState -> blockState.is(Blocks.FLOWER_POT), mappedStateCandidates);
        }

        int wanted = mapColourOf(original);
        if (wanted < 0) {
            return mappedStateCandidates.iterator().next();
        }

        BlockState closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (BlockState candidate : mappedStateCandidates) {
            int colour = mapColourOf(candidate);
            if (colour < 0) {
                continue;
            }
            int distance = colourDistance(wanted, colour);
            // Strictly nearer, so candidates nothing separates keep the order they already had
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = candidate;
            }
        }

        return closest != null ? closest : mappedStateCandidates.iterator().next();
    }

    /** The colour the game itself says a block is, or -1 if it will not say. */
    private static int mapColourOf(BlockState state) {
        try {
            return state.getMapColor(PolymerCommonUtils.getFakeWorld(), BlockPos.ZERO).col;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int colourDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static BlockState pickBlockStateCandidate0(Predicate<BlockState> predicate, Set<BlockState> mappedStateCandidates) {
        for (BlockState mappedStateCandidate : mappedStateCandidates) {
            if (predicate.test(mappedStateCandidate)) {
                return mappedStateCandidate;
            }
        }
        return mappedStateCandidates.iterator().next();
    }


    @Override
    public BlockState getPolymerBlockState(BlockState blockState, PacketContext packetContext) {
        // A client-only renderer can still have an exact vanilla semantic carrier. Compat packages
        // register that fact here instead of teaching the global allocator mod or block names.
        BlockState presentationCarrier = BlockPresentationRules.carrier(blockState);
        if (presentationCarrier != null) {
            return presentationCarrier;
        }
        // RenderShape.INVISIBLE is an explicit semantic promise from the block, not an absent model.
        // Alex's Caves' Ambersol light is the first conspicuous example: it deliberately has a cube
        // model in its assets for particles/items, while its placed block renderer returns INVISIBLE.
        // Letting automatic model capture ignore that promise painted every invisible light as a cube.
        // Drawn from its GeckoLib model: something solid to click, hiding nothing behind it. Before the
        // invisible check, because a GeckoLib block usually says it is not drawn as a block at all
        if (GeckoLibBlockModels.isDrawnHere(blockState)) {
            return Blocks.BARRIER.defaultBlockState();
        }
        if (isRendererInvisible(blockState)) {
            if (blockState.getFluidState().is(FluidTags.WATER)) {
                return Blocks.WATER.defaultBlockState();
            }
            if (blockState.getFluidState().is(FluidTags.LAVA)) {
                return Blocks.LAVA.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }
        // Renderer-only hollow blocks are drawn by displays. A solid carrier underneath them culls the
        // top face of the ground below; looking through a crucible's open bottom then looks straight out
        // of the world. Barrier keeps the required solid interaction without occluding neighbour faces.
        if (CitadelBlockModels.isDrawnHere(blockState)) {
            return Blocks.BARRIER.defaultBlockState();
        }
        BlockState mappedState = mappedStates.get(blockState);
        if (mappedState != null && CarrierStateSafety.isWaterSafeCarrier(blockState, mappedState)) return mappedState;
        return Blocks.BARRIER.defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        ElementHolder semantic = BlockPresentationRules.holder(world, pos, initialBlockState);
        if (semantic != null) {
            return semantic;
        }
        ElementHolder geckoLib = GeckoLibBlockModels.modelFor(initialBlockState, world, pos);
        if (geckoLib != null) {
            return geckoLib;
        }
        if (isRendererInvisible(initialBlockState)) {
            return AmbientBlockEffects.supports(initialBlockState)
                ? BlockStateModel.effectsOnly() : null;
        }
        // Before the shape-based answers below, because this is about what the block contains rather
        // than what it looks like, and a capsid still needs its own model underneath
        ElementHolder showcase = ShowcaseBlocks.modelFor(initialBlockState);
        if (showcase != null) {
            return showcase;
        }

        // A block whose shape is only known to its own renderer, drawn from the model that renderer keeps
        ElementHolder drawnElsewhere = CitadelBlockModels.modelFor(initialBlockState);
        if (drawnElsewhere != null) {
            return drawnElsewhere;
        }
        if (requiresElementHolder.contains(initialBlockState)) {
            BlockUsage.shown(BuiltInRegistries.BLOCK.getKey(initialBlockState.getBlock()));
            if (initialBlockState.getBlock() instanceof SignBlock) {
                return new SignModel(initialBlockState, pos);
            }
            return SHORT_SIGHTED.contains(initialBlockState)
                ? BlockStateModel.fallback(initialBlockState)
                : BlockStateModel.forBlock(initialBlockState);
        }
        if (AmbientBlockEffects.supports(initialBlockState)) {
            // Reached only when the block was NOT drawn by a display above, which means it has a real
            // carrier and the client is already drawing it correctly. All that is wanted here is
            // somewhere to run the flame or the drip from - drawing it again lands a second copy of
            // the model on top of the first, and two identical surfaces at the same depth flicker
            return BlockStateModel.effectsOnly();
        }
        return null;
    }

    @Override
    public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        return BlockPresentationRules.ticks(initialBlockState)
            || AmbientBlockEffects.supports(initialBlockState)
            || ShowcaseBlocks.showsWhatIsInside(initialBlockState)
            || GeckoLibBlockModels.isDrawnHere(initialBlockState);
    }

    /** True only where the actual model is a display, so the client cannot crack it itself. */
    public boolean usesServerTimedBreakOverlay(BlockState state) {
        return !isRendererInvisible(state) && requiresElementHolder.contains(state);
    }

    private static boolean isRendererInvisible(BlockState state) {
        try {
            return state.getRenderShape() == RenderShape.INVISIBLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean showCustomMiningStageMarker(BlockState state, BlockPos pos, ServerPlayer player) {
        return usesServerTimedBreakOverlay(state);
    }

    @Override
    public boolean isIgnoringBlockInteractionPlaySoundExceptedEntity(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult) {
        return true;
    }
}
