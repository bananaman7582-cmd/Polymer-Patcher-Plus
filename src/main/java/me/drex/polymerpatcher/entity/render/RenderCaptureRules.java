package me.drex.polymerpatcher.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Extension points around the generic captured-renderer pipeline.
 *
 * <p>The capture engine understands blocks, items, model parts and textures without knowing a mod.
 * A compat package may register the few semantic facts which cannot be inferred (for example, that a
 * named shader pass is an invisible water mask). New mods can use the same engine without adding more
 * mod-name checks to its core classes.</p>
 */
public final class RenderCaptureRules {
    private static boolean initialized;

    private RenderCaptureRules() {
    }

    /** Rules implied by vanilla entity/item capabilities rather than by a particular mod. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Captured third-person held-item geometry reaches an item display with the opposite local
        // forward direction. This first showed up on custom skeleton bows, but it is a property of the
        // shared hand render path: limiting the turn to bows left every other mob holding its items
        // backwards. Restrict it to the two hand contexts so projectile/item renderers are untouched.
        registerItemTransform((entity, stack, context, transform) -> {
            if (context == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
                transform.rotateY((float) Math.PI);
            }
        });
    }

    @FunctionalInterface public interface ItemRule {
        ItemStack apply(Entity entity, ItemStack stack);
    }
    @FunctionalInterface public interface BlockRule {
        void apply(Entity entity, BlockState state, Matrix4f transform);
    }
    @FunctionalInterface public interface ItemTransformRule {
        void apply(Entity entity, ItemStack stack, ItemDisplayContext context, Matrix4f transform);
    }
    @FunctionalInterface public interface BoxFilter {
        boolean skip(Entity entity, @Nullable RenderType renderType);
    }
    @FunctionalInterface public interface TextureRule {
        Identifier apply(@Nullable RenderType renderType, Identifier texture);
    }
    @FunctionalInterface public interface EntityTextureRule {
        Identifier apply(Entity entity, Identifier texture);
    }
    @FunctionalInterface public interface StaticBlockRule {
        boolean apply(PoseStack pose, BlockState state);
    }
    @FunctionalInterface public interface StaticModelRule {
        boolean apply(Object model, BlockState state);
    }
    @FunctionalInterface public interface ExtraTextureRule {
        @Nullable Identifier apply(String entityId, Identifier texture);
    }
    @FunctionalInterface public interface FlatTextureRule {
        Identifier apply(Entity entity, Identifier texture);
    }
    @FunctionalInterface public interface FlatTransformRule {
        void apply(Entity entity, Matrix4f transform);
    }
    @FunctionalInterface public interface OuterTextureRule {
        Identifier apply(Entity entity, Identifier texture);
    }
    @FunctionalInterface public interface ExtraModelTextureRule {
        @Nullable Identifier apply(Entity entity, Identifier texture);
    }
    @FunctionalInterface public interface RideOffsetRule {
        @Nullable Vec3 apply(Entity vehicle, Entity passenger);
    }
    @FunctionalInterface public interface BrightnessRule {
        @Nullable Brightness apply(Entity entity);
    }

    private static final List<ItemRule> ITEM_RULES = new CopyOnWriteArrayList<>();
    private static final List<BlockRule> BLOCK_RULES = new CopyOnWriteArrayList<>();
    private static final List<ItemTransformRule> ITEM_TRANSFORM_RULES = new CopyOnWriteArrayList<>();
    private static final List<BoxFilter> BOX_FILTERS = new CopyOnWriteArrayList<>();
    private static final List<TextureRule> TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<EntityTextureRule> ENTITY_TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<StaticBlockRule> STATIC_BLOCK_RULES = new CopyOnWriteArrayList<>();
    private static final List<StaticModelRule> STATIC_MODEL_RULES = new CopyOnWriteArrayList<>();
    private static final List<ExtraTextureRule> EXTRA_TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<FlatTextureRule> FLAT_TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<FlatTransformRule> FLAT_TRANSFORM_RULES = new CopyOnWriteArrayList<>();
    private static final List<OuterTextureRule> OUTER_TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<ExtraModelTextureRule> EXTRA_MODEL_TEXTURE_RULES = new CopyOnWriteArrayList<>();
    private static final List<RideOffsetRule> RIDE_OFFSET_RULES = new CopyOnWriteArrayList<>();
    private static final List<BrightnessRule> BRIGHTNESS_RULES = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ResourcePackBuilder>> ASSET_GENERATORS = new CopyOnWriteArrayList<>();

    public static void registerItem(ItemRule rule) { ITEM_RULES.add(rule); }
    public static void registerBlock(BlockRule rule) { BLOCK_RULES.add(rule); }
    public static void registerItemTransform(ItemTransformRule rule) { ITEM_TRANSFORM_RULES.add(rule); }
    public static void registerBoxFilter(BoxFilter filter) { BOX_FILTERS.add(filter); }
    public static void registerTexture(TextureRule rule) { TEXTURE_RULES.add(rule); }
    public static void registerEntityTexture(EntityTextureRule rule) { ENTITY_TEXTURE_RULES.add(rule); }
    public static void registerStaticBlock(StaticBlockRule rule) { STATIC_BLOCK_RULES.add(rule); }
    public static void registerStaticModel(StaticModelRule rule) { STATIC_MODEL_RULES.add(rule); }
    public static void registerExtraTexture(ExtraTextureRule rule) { EXTRA_TEXTURE_RULES.add(rule); }
    public static void registerFlatTexture(FlatTextureRule rule) { FLAT_TEXTURE_RULES.add(rule); }
    public static void registerFlatTransform(FlatTransformRule rule) { FLAT_TRANSFORM_RULES.add(rule); }
    public static void registerOuterTexture(OuterTextureRule rule) { OUTER_TEXTURE_RULES.add(rule); }
    public static void registerExtraModelTexture(ExtraModelTextureRule rule) { EXTRA_MODEL_TEXTURE_RULES.add(rule); }
    public static void registerRideOffset(RideOffsetRule rule) { RIDE_OFFSET_RULES.add(rule); }
    public static void registerBrightness(BrightnessRule rule) { BRIGHTNESS_RULES.add(rule); }
    public static void registerAssets(Consumer<ResourcePackBuilder> generator) { ASSET_GENERATORS.add(generator); }

    public static ItemStack item(Entity entity, ItemStack stack) {
        ItemStack current = stack;
        for (ItemRule rule : ITEM_RULES) {
            current = rule.apply(entity, current);
        }
        return current;
    }

    public static void block(Entity entity, BlockState state, Matrix4f transform) {
        for (BlockRule rule : BLOCK_RULES) {
            rule.apply(entity, state, transform);
        }
    }

    public static void itemTransform(Entity entity, ItemStack stack, ItemDisplayContext context,
                                     Matrix4f transform) {
        for (ItemTransformRule rule : ITEM_TRANSFORM_RULES) {
            rule.apply(entity, stack, context, transform);
        }
    }

    public static boolean skipBox(Entity entity, @Nullable RenderType renderType) {
        for (BoxFilter filter : BOX_FILTERS) {
            if (filter.skip(entity, renderType)) {
                return true;
            }
        }
        return false;
    }

    public static Identifier texture(@Nullable RenderType renderType, Identifier texture) {
        Identifier current = texture;
        for (TextureRule rule : TEXTURE_RULES) {
            current = rule.apply(renderType, current);
        }
        return current;
    }

    /** Per-entity texture selection after a renderer has submitted a model part. */
    public static Identifier entityTexture(Entity entity, Identifier texture) {
        Identifier current = texture;
        for (EntityTextureRule rule : ENTITY_TEXTURE_RULES) {
            current = rule.apply(entity, current);
        }
        return current;
    }

    public static boolean staticBlock(PoseStack pose, BlockState state) {
        for (StaticBlockRule rule : STATIC_BLOCK_RULES) {
            if (rule.apply(pose, state)) {
                return true;
            }
        }
        return false;
    }

    /** Gives renderer-specific static models the same state-dependent pose their renderer would. */
    public static boolean staticModel(Object model, BlockState state) {
        for (StaticModelRule rule : STATIC_MODEL_RULES) {
            if (rule.apply(model, state)) {
                return true;
            }
        }
        return false;
    }

    public static List<Identifier> extraTextures(String entityId, Identifier texture) {
        List<Identifier> result = new ArrayList<>();
        for (ExtraTextureRule rule : EXTRA_TEXTURE_RULES) {
            Identifier extra = rule.apply(entityId, texture);
            if (extra != null && !extra.equals(texture) && !result.contains(extra)) {
                result.add(extra);
            }
        }
        return result;
    }

    public static Identifier flatTexture(Entity entity, Identifier texture) {
        Identifier current = texture;
        for (FlatTextureRule rule : FLAT_TEXTURE_RULES) {
            current = rule.apply(entity, current);
        }
        return current;
    }

    public static void flatTransform(Entity entity, Matrix4f transform) {
        for (FlatTransformRule rule : FLAT_TRANSFORM_RULES) {
            rule.apply(entity, transform);
        }
    }

    public static Identifier outerTexture(Entity entity, Identifier texture) {
        Identifier current = texture;
        for (OuterTextureRule rule : OUTER_TEXTURE_RULES) {
            current = rule.apply(entity, current);
        }
        return current;
    }

    public static List<Identifier> extraModelTextures(Entity entity, Identifier texture) {
        List<Identifier> result = new ArrayList<>();
        for (ExtraModelTextureRule rule : EXTRA_MODEL_TEXTURE_RULES) {
            Identifier extra = rule.apply(entity, texture);
            if (extra != null && !extra.equals(texture) && !result.contains(extra)) {
                result.add(extra);
            }
        }
        return result;
    }

    public static @Nullable Vec3 rideOffset(Entity vehicle, Entity passenger) {
        for (RideOffsetRule rule : RIDE_OFFSET_RULES) {
            Vec3 offset = rule.apply(vehicle, passenger);
            if (offset != null) {
                return offset;
            }
        }
        return null;
    }

    /** Allows a renderer compat layer to preserve a deliberate light override from the native renderer. */
    public static @Nullable Brightness brightness(Entity entity) {
        for (BrightnessRule rule : BRIGHTNESS_RULES) {
            Brightness brightness = rule.apply(entity);
            if (brightness != null) {
                return brightness;
            }
        }
        return null;
    }

    public static void generateAssets(ResourcePackBuilder builder) {
        for (Consumer<ResourcePackBuilder> generator : ASSET_GENERATORS) {
            generator.accept(builder);
        }
    }
}
