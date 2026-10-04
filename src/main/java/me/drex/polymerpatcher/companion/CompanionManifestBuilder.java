package me.drex.polymerpatcher.companion;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.block.AutomaticFactoryBlock;
import me.drex.polymerpatcher.block.CitadelBlockModels;
import me.drex.polymerpatcher.block.GeckoLibBlockModels;
import me.drex.polymerpatcher.block.BlockPresentationRules;
import me.drex.polymerpatcher.block.fluid.ModdedFluidBlock;
import me.drex.polymerpatcher.block.fluid.ModdedFluids;
import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads, off the real blocks, everything the companion needs to build its own copy of each.
 * <p>
 * Only blocks this mod patched are included: a block some other mod made with Polymer itself has its
 * own idea of how it is shown, which nothing here can switch off for a player who has a real copy. The
 * game's own blocks are never included - the client already has them.
 */
public final class CompanionManifestBuilder {

    private CompanionManifestBuilder() {
    }

    /**
     * @param drawnByClient every state whose model the companion draws itself, so a display drawing it
     *                      would only draw it a second time
     */
    public record Built(CompanionManifest manifest, byte[] bytes, String hash, Set<Block> blocks,
                        Set<Fluid> fluids, Set<BlockState> drawnByClient) {
    }

    private static final BlockPos ORIGIN = BlockPos.ZERO;

    public static Built build(MinecraftServer server) {
        Tables tables = new Tables();
        List<CompanionManifest.BlockEntry> blocks = new ArrayList<>();
        List<CompanionManifest.FluidEntry> fluids = new ArrayList<>();
        Set<Block> blockSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Fluid> fluidSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BlockState> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
        int skipped = 0;
        Map<String, Integer> leftOut = new java.util.TreeMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (RegistryPatcher.isVanillaBlock(id)) {
                continue;
            }
            var overlay = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block);
            try {
                if (overlay instanceof ModdedFluidBlock && block instanceof LiquidBlock liquid) {
                    CompanionManifest.FluidEntry fluid = fluidEntry(server, id, liquid);
                    if (fluid != null) {
                        // The block of a fluid is still a block, and is described like one; only how its
                        // surface is drawn is the fluid's own business
                        CompanionManifest.BlockEntry entry = blockEntry(id, block, tables, drawn);
                        blocks.add(entry);
                        blockSet.add(block);
                        fluids.add(fluid);
                        FlowingFluid flowing = liquidFluid(liquid);
                        if (flowing != null) {
                            fluidSet.add(flowing.getSource());
                            fluidSet.add(flowing.getFlowing());
                        }
                    }
                    continue;
                }
                if (!(overlay instanceof AutomaticFactoryBlock)) {
                    leftOut.merge(overlay == null ? "not patched" : overlay.getClass().getSimpleName(), 1, Integer::sum);
                    continue;
                }
                if (!worthCopying(block)) {
                    leftOut.merge("shown through a vanilla block's renderer", 1, Integer::sum);
                    continue;
                }
                blocks.add(blockEntry(id, block, tables, drawn));
                blockSet.add(block);
            } catch (Throwable e) {
                skipped++;
                PolymerPatcher.LOGGER.debug("Left {} out of the companion manifest", id, e);
            }
        }
        if (!leftOut.isEmpty()) {
            PolymerPatcher.LOGGER.info("Modded blocks the companion does not copy, by reason: {}", leftOut);
        }
        if (skipped > 0) {
            PolymerPatcher.LOGGER.info("{} block(s) could not be described for the companion and are shown to it as to anybody else", skipped);
        }

        CompanionManifest manifest = new CompanionManifest(List.copyOf(tables.shapes), List.copyOf(tables.sounds),
            List.copyOf(blocks), List.copyOf(fluids));
        byte[] bytes = manifest.toBytes();
        return new Built(manifest, bytes, CompanionManifest.hashOf(bytes), Set.copyOf(blockSet), Set.copyOf(fluidSet),
            Collections.unmodifiableSet(drawn));
    }

    /**
     * Whether a client copy would be better than what the client already gets.
     * <p>
     * A block shown through a stand-in vanilla block whose own renderer does the drawing - Neverend's
     * portal is shown as the End portal - would lose that picture if the client swapped in a copy of the
     * real block, which has no renderer at all. Those stay exactly as they are.
     */
    private static boolean worthCopying(Block block) {
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (BlockPresentationRules.carrier(state) != null) {
                return false;
            }
        }
        return true;
    }

    private static CompanionManifest.BlockEntry blockEntry(Identifier id, Block block, Tables tables, Set<BlockState> drawn) {
        List<Property<?>> properties = new ArrayList<>(block.getStateDefinition().getProperties());
        List<CompanionManifest.PropertyEntry> propertyEntries = new ArrayList<>(properties.size());
        List<Map<Object, Integer>> valueIndices = new ArrayList<>(properties.size());
        for (Property<?> property : properties) {
            List<String> names = new ArrayList<>();
            Map<Object, Integer> indices = new HashMap<>();
            for (Object value : property.getPossibleValues()) {
                indices.put(value, names.size());
                names.add(nameOf(property, value));
            }
            propertyEntries.add(new CompanionManifest.PropertyEntry(property.getName(), List.copyOf(names)));
            valueIndices.add(indices);
        }

        BlockState defaultState = block.defaultBlockState();
        boolean hasModel = ResourceHelper.getAsset(id.getNamespace(), "blockstates/" + id.getPath() + ".json") != null;
        Set<BlockState> withoutModel = hasModel ? statesWithoutModel(id, block) : Set.of();

        List<CompanionManifest.StateEntry> states = new ArrayList<>();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            int[] values = new int[properties.size()];
            for (int i = 0; i < properties.size(); i++) {
                values[i] = valueIndices.get(i).get(state.getValue(properties.get(i)));
            }

            int flags = 0;
            if (ask(state::useShapeForLightOcclusion, false)) flags |= CompanionManifest.STATE_SHAPE_LIGHT_OCCLUSION;
            if (ask(state::propagatesSkylightDown, false)) flags |= CompanionManifest.STATE_SKYLIGHT;
            if (hasModel && !withoutModel.contains(state) && drawsItsOwnModel(state)) {
                flags |= CompanionManifest.STATE_DRAW_MODEL;
                drawn.add(state);
            }
            if (ask(() -> state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, ORIGIN), false)) flags |= CompanionManifest.STATE_REDSTONE_CONDUCTOR;
            if (ask(() -> state.isSuffocating(EmptyBlockGetter.INSTANCE, ORIGIN), false)) flags |= CompanionManifest.STATE_SUFFOCATING;
            if (ask(() -> state.isViewBlocking(EmptyBlockGetter.INSTANCE, ORIGIN), false)) flags |= CompanionManifest.STATE_VIEW_BLOCKING;
            if (ask(() -> state.emissiveRendering(), false)) flags |= CompanionManifest.STATE_EMISSIVE;
            if (ask(state::isSolid, false)) flags |= CompanionManifest.STATE_SOLID;

            CollisionContext empty = CollisionContext.empty();
            VoxelShape outline = shape(() -> state.getShape(EmptyBlockGetter.INSTANCE, ORIGIN, empty), Shapes.block());
            VoxelShape collision = shape(() -> state.getCollisionShape(EmptyBlockGetter.INSTANCE, ORIGIN, empty), outline);
            VoxelShape visual = shape(() -> state.getVisualShape(EmptyBlockGetter.INSTANCE, ORIGIN, empty), collision);
            VoxelShape interaction = shape(() -> state.getInteractionShape(EmptyBlockGetter.INSTANCE, ORIGIN), Shapes.empty());
            VoxelShape occlusion = shape(state::getOcclusionShape, Shapes.empty());

            states.add(new CompanionManifest.StateEntry(values,
                clamp(ask(state::getLightEmission, 0)), clamp(ask(state::getLightDampening, 0)), flags,
                tables.shape(outline), tables.shape(collision), tables.shape(visual), tables.shape(interaction),
                tables.shape(occlusion), tables.sound(ask(state::getSoundType, SoundType.STONE))));
        }

        int blockFlags = 0;
        if (ask(defaultState::requiresCorrectToolForDrops, false)) blockFlags |= CompanionManifest.BLOCK_REQUIRES_TOOL;
        if (ask(block::hasDynamicShape, false)) blockFlags |= CompanionManifest.BLOCK_DYNAMIC_SHAPE;
        if (ask(() -> defaultState.skipRendering(defaultState, Direction.UP), false)) blockFlags |= CompanionManifest.BLOCK_SKIPS_OWN_FACES;
        if (!ask(defaultState::shouldSpawnTerrainParticles, true)) blockFlags |= CompanionManifest.BLOCK_NO_TERRAIN_PARTICLES;
        if (ask(defaultState::canBeReplaced, false)) blockFlags |= CompanionManifest.BLOCK_REPLACEABLE;
        if (ask(defaultState::canOcclude, true)) blockFlags |= CompanionManifest.BLOCK_CAN_OCCLUDE;
        if (ask(defaultState::hasOffsetFunction, false)) {
            blockFlags |= offsetsVertically(defaultState) ? CompanionManifest.BLOCK_OFFSET_XYZ : CompanionManifest.BLOCK_OFFSET_XZ;
        }

        return new CompanionManifest.BlockEntry(id, List.copyOf(propertyEntries),
            ask(() -> defaultState.getDestroySpeed(EmptyBlockGetter.INSTANCE, ORIGIN), 1.0F),
            ask(block::getExplosionResistance, 1.0F), ask(block::getFriction, 0.6F), ask(block::getSpeedFactor, 1.0F),
            ask(block::getJumpFactor, 1.0F), offset(block, "getMaxHorizontalOffset", 0.25F),
            offset(block, "getMaxVerticalOffset", 0.2F), blockFlags, tintOf(block), List.copyOf(states));
    }

    /**
     * Whether the companion's copy can draw this state from the block's own model, leaving nothing for a
     * display to do.
     * <p>
     * Not when the block is drawn by a renderer - GeckoLib, Citadel - whose picture only exists as the
     * server's displays, nor when the block has said it is not drawn as a block at all; those copies are
     * invisible and the displays carry on as before.
     */
    private static boolean drawsItsOwnModel(BlockState state) {
        try {
            if (state.getRenderShape() != RenderShape.MODEL) {
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
        if (state.getBlock() instanceof SignBlock) {
            return false;
        }
        return !GeckoLibBlockModels.isDrawnHere(state) && !CitadelBlockModels.isDrawnHere(state);
    }

    /**
     * States the block's own model file has nothing for.
     * <p>
     * A file of variants names the states it covers, and some mods leave a few out. The client would draw
     * those as the purple and black "missing" box, so they are left to the server's displays instead. A
     * multipart file always gives every state something, even if it is nothing.
     */
    private static Set<BlockState> statesWithoutModel(Identifier id, Block block) {
        try {
            var asset = ResourceHelper.decodeBlockState(id);
            if (asset.variants().isEmpty()) {
                return Set.of();
            }
            List<java.util.function.Predicate<BlockState>> covered = new ArrayList<>();
            eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager.parseVariants(block, asset.variants().get(),
                (predicate, models) -> covered.add(predicate));
            Set<BlockState> missing = Collections.newSetFromMap(new IdentityHashMap<>());
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (covered.stream().noneMatch(predicate -> predicate.test(state))) {
                    missing.add(state);
                }
            }
            return missing;
        } catch (Throwable e) {
            return Set.of();
        }
    }

    /** Whether a block's offset moves it up and down as well as sideways. */
    private static boolean offsetsVertically(BlockState state) {
        try {
            for (int i = 0; i < 16; i++) {
                Vec3 offset = state.getOffset(new BlockPos(i * 31, 0, i * 17));
                if (offset.y != 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static float offset(Block block, String method, float fallback) {
        try {
            Method found = BlockBehaviour.class.getDeclaredMethod(method);
            found.setAccessible(true);
            return (float) found.invoke(block);
        } catch (Throwable e) {
            return fallback;
        }
    }

    /** How the client tints this block by biome, as the client dump recorded. */
    private static int tintOf(Block block) {
        try {
            RenderRegistry.BlockInfo info = PolymerPatcher.getRenderRegistry().blockInfoByBlock.get(block);
            if (info == null || info.biomeColor() == null) {
                return CompanionManifest.TINT_NONE;
            }
            Class<?> biomeColors = Class.forName("net.minecraft.client.renderer.BiomeColors");
            for (var field : biomeColors.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(null) != info.biomeColor()) {
                    continue;
                }
                String name = field.getName();
                if (name.contains("DRY_FOLIAGE")) return CompanionManifest.TINT_DRY_FOLIAGE;
                if (name.contains("FOLIAGE")) return CompanionManifest.TINT_FOLIAGE;
                if (name.contains("GRASS")) return CompanionManifest.TINT_GRASS;
                if (name.contains("WATER")) return CompanionManifest.TINT_WATER;
            }
        } catch (Throwable ignored) {
            // No client classes, or no dump: drawn untinted, which is what the carrier did anyway
        }
        return CompanionManifest.TINT_NONE;
    }

    private static CompanionManifest.FluidEntry fluidEntry(MinecraftServer server, Identifier id, LiquidBlock liquid) {
        // The companion builds its fluid block with just the one depth property a fluid block has; a
        // modded one carrying more than that is described as an ordinary block instead
        if (liquid.getStateDefinition().getProperties().size() != 1
            || !liquid.getStateDefinition().getProperties().contains(LiquidBlock.LEVEL)) {
            return null;
        }
        ModdedFluids.Skin skin = ModdedFluids.skinOf(liquid);
        FlowingFluid fluid = liquidFluid(liquid);
        if (skin == null || fluid == null) {
            return null;
        }
        ServerLevel level = server.overworld();
        int flags = 0;
        if (askFluid(() -> (boolean) invoke(fluid, FlowingFluid.class, "canConvertToSource", new Class<?>[]{ServerLevel.class}, level), false)) {
            flags |= CompanionManifest.FLUID_CONVERTS_TO_SOURCE;
        }
        // The same choice ModdedFluidBlock makes for a client without the mod, so a companion player
        // swims exactly where everybody else does
        var blocks = me.drex.polymerpatcher.config.ConfigManager.config().blocks;
        if (blocks.moddedFluidsAsWater && !blocks.fluidsKeptAsThemselves.contains(id.toString())) {
            flags |= CompanionManifest.FLUID_WATER_PHYSICS;
        }
        return new CompanionManifest.FluidEntry(id,
            BuiltInRegistries.FLUID.getKey(fluid.getSource()), BuiltInRegistries.FLUID.getKey(fluid.getFlowing()),
            skin.still(), skin.flowing(), -1,
            askFluid(() -> fluid.getTickDelay(level), 5),
            askFluid(() -> (int) invoke(fluid, FlowingFluid.class, "getSlopeFindDistance", new Class<?>[]{net.minecraft.world.level.LevelReader.class}, level), 4),
            askFluid(() -> (int) invoke(fluid, FlowingFluid.class, "getDropOff", new Class<?>[]{net.minecraft.world.level.LevelReader.class}, level), 1),
            askFluid(() -> (float) invoke(fluid, Fluid.class, "getExplosionResistance", new Class<?>[0]), 100.0F),
            flags);
    }

    private static FlowingFluid liquidFluid(LiquidBlock liquid) {
        return liquid.defaultBlockState().getFluidState().getType() instanceof FlowingFluid flowing ? flowing : null;
    }

    private static Object invoke(Object target, Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        // The method is protected and overridden: found on the declaring class, called on the fluid
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private interface Risky<T> {
        T get() throws Exception;
    }

    private static <T> T askFluid(Risky<T> question, T fallback) {
        try {
            T answer = question.get();
            return answer == null ? fallback : answer;
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static <T> T ask(Supplier<T> question, T fallback) {
        try {
            T answer = question.get();
            return answer == null ? fallback : answer;
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static VoxelShape shape(Supplier<VoxelShape> question, VoxelShape fallback) {
        return ask(question, fallback);
    }

    private static int clamp(int light) {
        return Math.max(0, Math.min(15, light));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String nameOf(Property property, Object value) {
        return property.getName((Comparable) value);
    }

    /** Shapes and sounds are shared by many states; each distinct one is written once and referred to. */
    private static final class Tables {
        final List<double[]> shapes = new ArrayList<>();
        final List<CompanionManifest.Sound> sounds = new ArrayList<>();
        private final Map<VoxelShape, Integer> shapeIndex = new IdentityHashMap<>();
        private final Map<ShapeKey, Integer> shapeByBoxes = new HashMap<>();
        private final Map<CompanionManifest.Sound, Integer> soundIndex = new HashMap<>();

        private record ShapeKey(double[] boxes) {
            @Override
            public boolean equals(Object other) {
                return other instanceof ShapeKey key && Arrays.equals(boxes, key.boxes);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(boxes);
            }
        }

        Tables() {
            shape(Shapes.empty());
            shape(Shapes.block());
        }

        int shape(VoxelShape shape) {
            Integer known = shapeIndex.get(shape);
            if (known != null) {
                return known;
            }
            List<AABB> boxes = shape.toAabbs();
            double[] flat = new double[boxes.size() * 6];
            for (int i = 0; i < boxes.size(); i++) {
                AABB box = boxes.get(i);
                flat[i * 6] = box.minX;
                flat[i * 6 + 1] = box.minY;
                flat[i * 6 + 2] = box.minZ;
                flat[i * 6 + 3] = box.maxX;
                flat[i * 6 + 4] = box.maxY;
                flat[i * 6 + 5] = box.maxZ;
            }
            int index = shapeByBoxes.computeIfAbsent(new ShapeKey(flat), key -> {
                shapes.add(flat);
                return shapes.size() - 1;
            });
            shapeIndex.put(shape, index);
            return index;
        }

        int sound(SoundType type) {
            CompanionManifest.Sound sound = new CompanionManifest.Sound(type.getVolume(), type.getPitch(),
                location(type.getBreakSound()), location(type.getStepSound()), location(type.getPlaceSound()),
                location(type.getHitSound()), location(type.getFallSound()));
            return soundIndex.computeIfAbsent(sound, key -> {
                sounds.add(sound);
                return sounds.size() - 1;
            });
        }

        private static Identifier location(SoundEvent event) {
            return event.location();
        }
    }

    /** Every block the manifest will copy, for counting in a report. */
    static Set<Identifier> ids(Built built) {
        Set<Identifier> ids = new HashSet<>();
        built.manifest().blocks.forEach(entry -> ids.add(entry.id()));
        return ids;
    }
}
