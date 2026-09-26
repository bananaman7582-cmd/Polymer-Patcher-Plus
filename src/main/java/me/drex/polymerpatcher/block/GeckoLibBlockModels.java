package me.drex.polymerpatcher.block;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibBone;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibModel;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibModelInstance;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Blocks GeckoLib draws, drawn from their GeckoLib model.
 * <p>
 * A GeckoLib block - Sculk Horde's soul harvester and sculk summoner are two - has no shape in its block
 * model at all, or says outright that it is not drawn as a block; its renderer draws the model file. A
 * server has no renderer running, so it used to be sent as air: nothing to see, and nothing a client can
 * aim at to open, use or break.
 * <p>
 * Its renderer can be built here all the same (GeckoLib's block renderer asks its context for nothing it
 * cannot do without), and from it the model file is read and baked exactly as for a GeckoLib mob. The
 * block is then a barrier - solid and clickable, but hiding nothing behind it - with the model standing
 * in it at rest, turned as GeckoLib turns it for the way the block faces. Its animation is not played.
 * <p>
 * The renderer is found by name, the way a block entity renderer is always named after its block
 * ({@code soul_harvester} → {@code SoulHarvesterBlockRenderer}), anywhere in the mod's own jar.
 */
public final class GeckoLibBlockModels {

    private GeckoLibBlockModels() {
    }

    private static final String[] ENDINGS = {"BlockRenderer", "BlockEntityRenderer", "Renderer"};

    private static final Map<Block, GeckoLibModelInstance<?, ?, ?>> READY = new HashMap<>();

    public static void setup() {
        if (!FabricLoader.getInstance().isModLoaded("geckolib")) {
            return;
        }
        Map<String, Map<String, String>> classesByMod = new HashMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !PolymerPatcher.PATCHED_MODS.contains(id.getNamespace()) || !(block instanceof EntityBlock)) {
                continue;
            }
            try {
                if (!invisible(block) && !CitadelBlockModels.drawsNothing(id)) {
                    continue;
                }
                if (CitadelBlockModels.isDrawnHere(block.defaultBlockState())) {
                    continue;
                }
                Map<String, String> classes = classesByMod.computeIfAbsent(id.getNamespace(), GeckoLibBlockModels::classesOf);
                String camel = camel(id.getPath());
                for (String ending : ENDINGS) {
                    String name = classes.get(camel + ending);
                    if (name != null && load(block, name)) {
                        break;
                    }
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not read what {} is supposed to look like", id, e);
            }
        }

        if (!READY.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} GeckoLib block(s) will be drawn from their model: {}", READY.size(),
                READY.keySet().stream().map(BuiltInRegistries.BLOCK::getKey).toList());
        }
    }

    private static boolean invisible(Block block) {
        try {
            return block.defaultBlockState().getRenderShape() == RenderShape.INVISIBLE;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean load(Block block, String rendererName) throws Throwable {
        Class<?> type = me.drex.polymerpatcher.dump.ClientOnlyClasses.loadQuietly(rendererName);
        if (type == null) {
            return false;
        }
        Class<?> contextType = Class.forName("net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider$Context",
            false, GeckoLibBlockModels.class.getClassLoader());
        Constructor<?> contextConstructor = contextType.getDeclaredConstructors()[0];
        Object context = contextConstructor.newInstance(new Object[contextConstructor.getParameterCount()]);
        Constructor<?> constructor = type.getDeclaredConstructor(contextType);
        constructor.setAccessible(true);
        Object renderer = constructor.newInstance(context);
        if (!GeckoLibModel.isGeoRenderer(renderer)) {
            return false;
        }

        GeckoLibModel.Baked baked = GeckoLibModel.load(renderer);
        if (baked == null) {
            return false;
        }
        var roots = GeckoLibBone.resolve(renderer, baked.handle());
        if (roots.isEmpty()) {
            return false;
        }
        GeckoLibModelInstance instance = GeckoLibModelInstance.create(renderer, baked, roots);
        AnimatedEntities.GECKOLIB_MODELS.add(instance);
        READY.put(block, instance);
        return true;
    }

    /** Every class in a mod's jar by simple name, to find a renderer wherever the mod keeps it. */
    private static Map<String, String> classesOf(String modId) {
        Map<String, String> found = new HashMap<>();
        ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
        if (mod == null) {
            return found;
        }
        for (Path root : mod.getRootPaths()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.forEach(file -> {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (name.endsWith("Renderer.class") && !name.contains("$")) {
                        String full = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                        found.putIfAbsent(full.substring(full.lastIndexOf('.') + 1), full);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        return found;
    }

    private static String camel(String path) {
        StringBuilder camel = new StringBuilder();
        for (String word : path.split("_")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return camel.toString();
    }

    public static boolean isDrawnHere(BlockState state) {
        return READY.containsKey(state.getBlock());
    }

    @Nullable
    public static ElementHolder modelFor(BlockState state, net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        GeckoLibModelInstance<?, ?, ?> instance = READY.get(state.getBlock());
        return instance == null ? null : new Model(instance, state, level, pos);
    }

    /** Renderers whose animation could not be run here, drawn at rest instead; said once each. */
    private static final java.util.Set<Class<?>> STILL = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The model standing on the floor of the block, animated the way GeckoLib animates it.
     * <p>
     * It is put up at rest first, turned for the way the block faces. While anybody is watching, each
     * tick runs GeckoLib's own render pass on the block entity - the same pass that poses a GeckoLib mob -
     * and moves every piece to where the pass put it. The block entity's animations are driven by its own
     * state on the server, which is the real one, so what they show is what is happening. A renderer that
     * cannot run here leaves the model at rest rather than taking the block with it.
     */
    private static final class Model extends BlockModel {

        private final GeckoLibModelInstance<?, ?, ?> instance;
        private final net.minecraft.server.level.ServerLevel level;
        private final net.minecraft.core.BlockPos pos;
        private final java.util.Map<Integer, ItemDisplayElement> pieces = new java.util.HashMap<>();
        private final java.util.Map<Integer, Matrix4f> placed = new java.util.HashMap<>();
        private static final Matrix4f HIDDEN = new Matrix4f().scale(0.0F);

        private Model(GeckoLibModelInstance<?, ?, ?> instance, BlockState state,
                      net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
            this.instance = instance;
            this.level = level;
            this.pos = pos.immutable();
            PoseStack poseStack = new PoseStack();
            // A holder sits at the middle of its block; GeckoLib draws from the middle of the floor
            poseStack.translate(0.0F, -0.5F, 0.0F);
            Direction facing = facing(state);
            if (facing != null) {
                switch (facing) {
                    case SOUTH -> poseStack.mulPose(new Quaternionf().rotateY((float) Math.PI));
                    case WEST -> poseStack.mulPose(new Quaternionf().rotateY((float) (Math.PI / 2)));
                    case EAST -> poseStack.mulPose(new Quaternionf().rotateY((float) (-Math.PI / 2)));
                    case UP -> poseStack.mulPose(new Quaternionf().rotateX((float) (Math.PI / 2)));
                    case DOWN -> poseStack.mulPose(new Quaternionf().rotateX((float) (-Math.PI / 2)));
                    default -> {
                    }
                }
            }
            for (GeckoLibBone root : instance.roots()) {
                root.visit(instance.renderer(), poseStack, (piece, matrix) -> place(piece.id(), matrix));
            }
        }

        private void place(int id, Matrix4f matrix) {
            ItemDisplayElement element = ItemDisplayElementUtil.createSimple(
                ItemDisplayElementUtil.getModel(instance.modelPath(id)).get());
            element.setItemDisplayContext(ItemDisplayContext.FIXED);
            element.setTeleportDuration(0);
            element.setInterpolationDuration(1);
            element.setViewRange(me.drex.polymerpatcher.config.ConfigManager.config().blocks.displayViewRange);
            element.setDisplaySize(3.0F, 3.0F);
            element.setTransformation(matrix);
            pieces.put(id, element);
            placed.put(id, new Matrix4f(matrix));
            addElement(element);
        }

        @Override
        protected void onTick() {
            super.onTick();
            Object renderer = instance.renderer();
            if (getWatchingPlayers().isEmpty() || STILL.contains(renderer.getClass())) {
                return;
            }
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return;
            }
            try {
                Object renderState = renderStateFor(renderer, blockEntity);
                if (renderState == null) {
                    throw new IllegalStateException("no render state");
                }
                java.util.List<net.minecraft.server.level.ServerPlayer> watching = new java.util.ArrayList<>();
                for (var connection : getWatchingPlayers()) {
                    watching.add(connection.getPlayer());
                }
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                PoseStack poseStack = new PoseStack();
                // GeckoLib's pass starts from the corner of the block and moves to the middle of its floor
                poseStack.translate(-0.5F, -0.5F, -0.5F);
                me.drex.polymerpatcher.util.ClientParticleReplay.runRethrowing(level, watching, () ->
                    GeckoLibModel.renderPosed(renderer, renderState, poseStack, 1.0F, 1.0F, () -> {
                        for (GeckoLibBone root : instance.roots()) {
                            root.visit(renderer, poseStack, (piece, matrix) -> {
                                seen.add(piece.id());
                                move(piece.id(), matrix);
                            });
                        }
                    }));
                for (Integer id : pieces.keySet()) {
                    if (!seen.contains(id)) {
                        move(id, HIDDEN);
                    }
                }
            } catch (Throwable e) {
                if (STILL.add(renderer.getClass())) {
                    PolymerPatcher.LOGGER.warn("{} could not be animated here and is drawn at rest",
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()), e);
                }
            }
        }

        private void move(int id, Matrix4f matrix) {
            ItemDisplayElement element = pieces.get(id);
            Matrix4f previous = placed.get(id);
            if (element == null || previous == null || previous.equals(matrix, 0.00001F)) {
                return;
            }
            previous.set(matrix);
            element.setTransformation(matrix);
            element.startInterpolationIfDirty();
        }

        @Nullable
        private static Object renderStateFor(Object renderer, Object blockEntity) throws Exception {
            Object state = null;
            java.lang.reflect.Method extract = null;
            for (Class<?> type = renderer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (state == null && method.getName().equals("createRenderState") && parameters.length == 2
                        && parameters[0].isInstance(blockEntity)) {
                        method.setAccessible(true);
                        state = method.invoke(renderer, blockEntity, null);
                    }
                    if (extract == null && method.getName().equals("extractRenderState") && parameters.length == 5
                        && parameters[0].isInstance(blockEntity)) {
                        extract = method;
                    }
                }
            }
            if (state == null) {
                for (java.lang.reflect.Method method : renderer.getClass().getMethods()) {
                    if (method.getName().equals("createRenderState") && method.getParameterCount() == 0) {
                        state = method.invoke(renderer);
                        break;
                    }
                }
            }
            if (state != null && extract != null) {
                extract.setAccessible(true);
                extract.invoke(renderer, blockEntity, state, 0.0F, net.minecraft.world.phys.Vec3.ZERO, null);
            }
            return state;
        }

        @Nullable
        private static Direction facing(BlockState state) {
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
            if (state.hasProperty(BlockStateProperties.FACING)) {
                return state.getValue(BlockStateProperties.FACING);
            }
            return null;
        }
    }
}
