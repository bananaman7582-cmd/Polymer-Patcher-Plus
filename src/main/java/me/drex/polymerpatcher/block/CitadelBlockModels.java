package me.drex.polymerpatcher.block;

import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.entity.citadel.CitadelModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModelInstance;
import me.drex.polymerpatcher.entity.citadel.CitadelModels;
import me.drex.polymerpatcher.entity.citadel.CitadelPart;
import net.minecraft.core.Direction;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Blocks whose shape is drawn by the mod's own block renderer rather than by a model.
 * <p>
 * A block entity renderer is client code, and a dedicated server runs none - so a block that leans on
 * one for its appearance has nothing to show. Alex's Caves' beholder is the clearest case: the block
 * itself is a plain base, and the eye that makes it a beholder is a Citadel model drawn by its
 * renderer. Without the mod there is a base and no eye.
 * <p>
 * That model is sitting in a static field on the renderer, of exactly the kind this mod already reads
 * for mobs, and so is the texture beside it. Both are read out, the model's parts are written into the
 * pack the same way a mob's are, and the block draws them itself at the pose the model file gives them,
 * turned to face the way the block faces.
 * <p>
 * Read rather than described: nothing here names a field or a texture, so adding another block is one
 * line naming the block and its renderer.
 */
public final class CitadelBlockModels {

    private CitadelBlockModels() {
    }

    /**
     * Blocks drawn this way, and the renderer that knows what they look like.
     * <p>
     * Neither half is written down. A block that draws nothing of its own says so in its model file -
     * no shape, and no parent to inherit one from - and a block entity renderer is named after the
     * block it draws, which is a convention every mod in this family keeps. So the blocks are found by
     * asking those two questions of every block a patched mod adds.
     * <p>
     * Alex's Caves has five: the beholder, the conversion crucible, the copper valve, the gobthumper
     * and the siren light. Only the first was ever listed here by hand, and the other four were
     * invisible - a crucible you could walk up to, use, and never see.
     */
    private static Map<Identifier, String> drawnBy() {
        Map<Identifier, String> found = new HashMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !PolymerPatcher.PATCHED_MODS.contains(id.getNamespace())
                || !(block instanceof net.minecraft.world.level.block.EntityBlock) || !drawsNothing(id)) {
                continue;
            }

            String renderer = rendererFor(id);
            if (renderer != null) {
                found.put(id, renderer);
            }
        }

        return found;
    }

    /**
     * Whether this block's own model has no shape in it at all.
     * <p>
     * That is what a block whose appearance lives in a renderer looks like from the outside: a model
     * naming a texture for its particles and nothing else. A block with a shape of its own is left
     * alone, renderer or no renderer - plenty have both, and drawing the model as well would double it.
     */
    static boolean drawsNothing(Identifier block) {
        // A blockstate is allowed to point at a model with a different name. Alex's Caves' assembled
        // Nuclear Furnace is the important example: nuclear_furnace.json does not exist as a model;
        // its blockstate points at active_nuclear_furnace, whose empty geometry is replaced by the
        // block-entity renderer. Looking only for models/block/<block id>.json skipped the renderer and
        // made the complete 2x2x2 structure disappear.
        try (java.io.InputStream stream = ResourceHelper.getAsset(block.getNamespace(),
            "blockstates/" + block.getPath() + ".json").get()) {
            com.google.gson.JsonElement state = com.google.gson.JsonParser.parseString(
                new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            java.util.Set<Identifier> models = new java.util.LinkedHashSet<>();
            collectModels(state, models);
            if (!models.isEmpty()) {
                for (Identifier model : models) {
                    if (!modelDrawsNothing(model, new java.util.HashSet<>())) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Throwable ignored) {
            // Older/simple packs may expose only the conventional same-name model; try it below.
        }

        return modelDrawsNothing(
            Identifier.fromNamespaceAndPath(block.getNamespace(), "block/" + block.getPath()),
            new java.util.HashSet<>());
    }

    /** Finds model references in variants as well as multipart apply clauses. */
    private static void collectModels(com.google.gson.JsonElement element, java.util.Set<Identifier> into) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                collectModels(child, into);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        com.google.gson.JsonObject object = element.getAsJsonObject();
        com.google.gson.JsonElement model = object.get("model");
        if (model != null && model.isJsonPrimitive()) {
            try {
                into.add(Identifier.parse(model.getAsString()));
            } catch (RuntimeException ignored) {
            }
        }
        for (var entry : object.entrySet()) {
            if (!"model".equals(entry.getKey())) {
                collectModels(entry.getValue(), into);
            }
        }
    }

    /** Whether a model and its parent chain contribute no cuboids. */
    private static boolean modelDrawsNothing(Identifier modelId, java.util.Set<Identifier> seen) {
        if (!seen.add(modelId)) {
            return false;
        }
        try (java.io.InputStream stream = ResourceHelper.getAsset(modelId.getNamespace(),
            "models/" + modelId.getPath() + ".json").get()) {
            com.google.gson.JsonObject model = com.google.gson.JsonParser.parseString(
                new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();

            if (model.has("elements")) {
                return model.get("elements").isJsonArray() && model.getAsJsonArray("elements").isEmpty();
            }

            com.google.gson.JsonElement parent = model.get("parent");
            if (parent == null || !parent.isJsonPrimitive()) {
                return false;
            }
            Identifier parentId = Identifier.parse(parent.getAsString());
            if (Identifier.fromNamespaceAndPath("minecraft", "block/block").equals(parentId)) {
                return true;
            }
            return modelDrawsNothing(parentId, seen);
        } catch (Throwable e) {
            return false;
        }
    }

    /** The class that draws this block, named after it, or null where the mod has no such class. */
    @Nullable
    private static String rendererFor(Identifier block) {
        String models = me.drex.polymerpatcher.item.CitadelItemModels.modelPackage(block.getNamespace());
        String root = models == null || !models.endsWith(MODEL_PACKAGE) ? null
            : models.substring(0, models.length() - MODEL_PACKAGE.length());
        if (root == null) {
            return null;
        }

        StringBuilder camel = new StringBuilder();
        for (String word : block.getPath().split("_")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }

        for (String where : RENDERER_PACKAGES) {
            for (String ending : RENDERER_ENDINGS) {
                String name = root + where + camel + ending;
                if (CitadelBlockModels.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null) {
                    return name;
                }
            }
        }

        return null;
    }

    private static final String MODEL_PACKAGE = ".client.model";

    private static final String[] RENDERER_PACKAGES = {
        ".client.render.blockentity.", ".client.renderer.blockentity.", ".client.render.tile."
    };

    private static final String[] RENDERER_ENDINGS = {"BlockRenderer", "BlockEntityRenderer", "Renderer"};

    private static final Map<Identifier, ReadyModel> READY = new HashMap<>();

    /**
     * Reads each block's model out of its renderer, and lists it for the pack to write.
     */
    public static void setup() {
        for (var entry : drawnBy().entrySet()) {
            Identifier block = entry.getKey();

            try {
                Class<?> renderer = me.drex.polymerpatcher.dump.ClientOnlyClasses.loadQuietly(entry.getValue());
                if (renderer == null) {
                    continue;
                }
                Object model = staticOfKind(renderer, CitadelModel::isCitadelModel);
                Identifier texture = (Identifier) staticOfKind(renderer, found -> found instanceof Identifier);

                if (model == null || texture == null) {
                    PolymerPatcher.LOGGER.debug("{} draws {}, but kept no model and texture this could read", entry.getValue(), block);
                    continue;
                }

                // A renderer names the file it binds (textures/entity/foo.png), while every generated
                // model and the atlas name the sprite inside that file (entity/foo). Passing the former
                // through made both the generated model path and its texture reference invalid: the
                // conversion crucible and copper valve had geometry, but every piece pointed at a
                // non-existent double textures/...png path and therefore drew nothing.
                texture = AnimatedEntities.spriteForm(texture);

                CitadelModels.Resolved resolved = CitadelModels.resolve(model);
                if (resolved.roots().isEmpty()) {
                    continue;
                }

                CitadelModelInstance<?, ?, ?> instance = CitadelModelInstance.create(null, resolved, texture);
                AnimatedEntities.CITADEL_MODELS.add(instance);
                READY.put(block, new ReadyModel(instance, model));
                PolymerPatcher.LOGGER.debug("{} will be drawn with the model its renderer keeps", block);
            } catch (Throwable e) {
                // One block drawn as its plain base, which is what happened before this existed
                PolymerPatcher.LOGGER.debug("Could not read what {} is supposed to look like", block, e);
            }
        }

        if (!READY.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} block(s) will be drawn with the model only their own renderer knows about: {}",
                READY.size(), READY.keySet());
        }
    }

    /** Whether this block is one drawn from its renderer's model. */
    public static boolean isDrawnHere(BlockState state) {
        return instanceFor(state) != null;
    }

    /** A model drawing this block's real shape, or nothing where it is not one of them. */
    @Nullable
    public static ElementHolder modelFor(BlockState state) {
        ReadyModel ready = instanceFor(state);
        return ready == null ? null : new Model(ready, state);
    }

    @Nullable
    private static ReadyModel instanceFor(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : READY.get(id);
    }

    private record ReadyModel(CitadelModelInstance<?, ?, ?> instance, Object handle) {
    }

    /** The first static field on a class holding something of the kind asked for. */
    @Nullable
    private static Object staticOfKind(Class<?> type, java.util.function.Predicate<Object> wanted) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(null);
                    if (value != null && wanted.test(value)) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    /** Draws the model once, at rest, turned to face the way the block faces. */
    private static final class Model extends BlockModel {

        private final CitadelModelInstance<?, ?, ?> instance;

        private Model(ReadyModel ready, BlockState state) {
            this.instance = ready.instance();

            // Some renderer-owned models use block state inside setupAnim instead of rotating the
            // pose stack. Let compat apply that state before the resolved live parts are visited.
            me.drex.polymerpatcher.entity.render.RenderCaptureRules.staticModel(ready.handle(), state);

            PoseStack poseStack = new PoseStack();
            if (!me.drex.polymerpatcher.entity.render.RenderCaptureRules.staticBlock(poseStack, state)) {
                // Standing upright and the right way round: a model file describes its shape upside down
                // by the game's reckoning, which every renderer corrects before drawing
                poseStack.translate(0.0F, 0.5F, 0.0F);
                poseStack.mulPose(new Quaternionf().rotateY(facingAngle(state)));
                poseStack.scale(-1.0F, -1.0F, 1.0F);
            }

            for (CitadelPart root : instance.roots()) {
                root.visit(poseStack, this::place);
            }
        }

        private void place(CitadelPart part, Matrix4f matrix) {
            if (part.id < 0) {
                return;
            }
            ItemDisplayElement element = ItemDisplayElementUtil.createSimple(
                ItemDisplayElementUtil.getModel(instance.modelPath(part.id)).get());
            element.setItemDisplayContext(ItemDisplayContext.FIXED);
            element.setTeleportDuration(0);
            element.setViewRange(me.drex.polymerpatcher.config.ConfigManager.config().blocks.displayViewRange);
            // These are stationary block models. Giving their displays a real bounding box lets the
            // vanilla renderer cull a crucible, valve or beholder that is outside the camera frustum.
            element.setDisplaySize(3.0F, 3.0F);
            element.setTransformation(matrix);
            addElement(element);
        }

        private static float facingAngle(BlockState state) {
            Direction facing = null;
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            } else if (state.hasProperty(BlockStateProperties.FACING)) {
                facing = state.getValue(BlockStateProperties.FACING);
            }
            return facing == null ? 0.0F : (float) Math.toRadians(-facing.toYRot());
        }
    }
}
