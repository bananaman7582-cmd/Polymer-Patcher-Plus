package me.drex.polymerpatcher.entity.citadel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;

/**
 * Catches a Citadel model at the moment its renderer draws it.
 * <p>
 * A Citadel model is not made of the parts the game's own models are made of, so nothing watching
 * those ever sees one. Everywhere else this mod handles that by posing the model itself and walking
 * it - which works while the renderer's own transforms can be reproduced, and that is only true of
 * the mobs whose renderers follow the game's own shape.
 * <p>
 * A renderer that is not a living one does its own thing entirely: a spear turns itself on two axes
 * and shifts along one, a worm segment lands somewhere else again, and there are dozens of them. The
 * way out is not to guess any of it. The renderer is allowed to run exactly as it would, and the
 * model is taken at the instant it would have been drawn - with whatever pose the renderer had built
 * by then, which is by definition the right one.
 */
public final class CitadelDraw {

    private CitadelDraw() {
    }

    /** What to do with something the moment its renderer draws it. */
    public interface Sink {
        /**
         * @param model     the Citadel model, already posed by its renderer
         * @param poseStack where the renderer had got to when it drew it
         */
        void drawn(Object model, PoseStack poseStack);

        /**
         * The same, with the render type the renderer had chosen for it.
         * <p>
         * A renderer that draws more than one model does not necessarily draw them all with the same
         * picture: a gummy bear's innards are a different colour from the bear, and the chest on a boat
         * is a different file from the boat. Drawn with the model's own texture they came out wearing
         * the wrong one. Null where nothing recorded it, which is the answer for anything drawn outside
         * a buffer this mod handed over.
         */
        default void drawn(Object model, PoseStack poseStack, @Nullable RenderType renderType) {
            drawn(model, poseStack);
        }

        /**
         * One piece of a model, drawn without the rest of it.
         * <p>
         * A renderer that wants a head and no body reaches past the model and draws the head's box. The
         * piece knows which model it belongs to, so it can be placed exactly like any other; what it
         * does not bring is the rest of the model, which is the point of it.
         */
        default void drewBox(Object box, PoseStack poseStack, @Nullable RenderType renderType) {
        }

        /**
         * A whole block drawn as part of something - the blocks a magnetron is built out of, a block
         * being flung or crushed. Ignored by default, because most things draw no blocks.
         */
        default void drewBlock(net.minecraft.world.level.block.state.BlockState state, PoseStack poseStack) {
        }

        /**
         * A whole item drawn as part of something. A teletor's magnetic weapon is the case that found
         * this: the entity is the weapon it carries, drawn as the item itself rather than as any model
         * of its own, so nothing watching for models ever saw it and the weapon was never there.
         */
        default void drewItem(net.minecraft.world.item.ItemStack stack, PoseStack poseStack) {
        }
    }

    /**
     * Set only while a renderer is being run on purpose, and only on that thread. Everywhere else this
     * is empty and the mixin gets out of the way, so a model drawn for any other reason is untouched.
     */
    public static final ThreadLocal<Sink> ACTIVE = ThreadLocal.withInitial(() -> null);

    /** Whether anything is listening, asked before doing any work in the hot path. */
    public static boolean listening() {
        return ACTIVE.get() != null;
    }

    /**
     * Whether the patch that catches these actually attached.
     * <p>
     * It only does where a mod carrying Citadel is installed. Without it, running a renderer to see
     * what it draws by hand would draw nothing and cost a full pose for the privilege.
     */
    public static boolean canCatch() {
        return CAN_CATCH;
    }

    private static volatile boolean CAN_CATCH;

    /**
     * Hands a block to whoever is listening, and says whether it was taken.
     */
    public static boolean takeBlock(net.minecraft.world.level.block.state.BlockState state, PoseStack poseStack) {
        Sink sink = ACTIVE.get();
        if (sink == null) {
            return false;
        }
        sink.drewBlock(state, poseStack);
        return true;
    }

    /**
     * Hands an item to whoever is listening, and says whether it was taken.
     */
    public static boolean takeItem(net.minecraft.world.item.ItemStack stack, PoseStack poseStack) {
        Sink sink = ACTIVE.get();
        if (sink == null) {
            return false;
        }
        sink.drewItem(stack, poseStack);
        return true;
    }

    /**
     * Hands over a piece of a model that is being drawn on its own.
     * <p>
     * Called from inside the piece's own drawing method, which this mod put there - see
     * {@link CitadelBoxPatch}. It fires for every piece of every model that is drawn, including all the
     * pieces of a model being drawn whole, so the first thing it does is the only thing it does on
     * nearly every call: find that nobody is listening and return.
     */
    public static void takeBox(Object box, PoseStack poseStack, Object buffer) {
        Sink sink = ACTIVE.get();
        if (sink == null) {
            return;
        }

        sink.drewBox(box, poseStack, buffer == null ? null : BOUND.get().get(buffer));
    }

    /** Said by the patch itself, once, as it attaches. */
    public static void attached() {
        CAN_CATCH = true;
    }

    /**
     * How far through a motion a model has been told it is, at the furthest, since this was last reset.
     * <p>
     * Every Citadel model is posed through the same few helpers, and each takes the same first number:
     * nothing at the start of a motion, one at the end of it. A mod caps that number itself, so the tick
     * at which it first reaches one is the tick the motion finishes - which is not written down anywhere
     * else, and cannot be worked out from the shape. See
     * {@link me.drex.polymerpatcher.item.HeldItemMotion}.
     */
    public static void noteProgress(float progress) {
        if (!HEARD) {
            HEARD = true;
            me.drex.polymerpatcher.PolymerPatcher.LOGGER.info("Models say how far through a motion they are; item motions can be measured");
        }

        Sink sink = ACTIVE.get();
        if (sink != null && Float.isFinite(progress)) {
            float[] furthest = PROGRESS.get();
            furthest[0] = Math.max(furthest[0], progress);
        }
    }

    /** The furthest through any motion this model said it was, since the last {@link #forgetProgress}. */
    public static float progressSeen() {
        return PROGRESS.get()[0];
    }

    public static void forgetProgress() {
        PROGRESS.get()[0] = 0.0F;
    }

    private static final ThreadLocal<float[]> PROGRESS = ThreadLocal.withInitial(() -> new float[1]);

    /** Said once, so that a patch that quietly did not attach is not mistaken for a model that never moves. */
    private static volatile boolean HEARD;

    /**
     * Hands a model to whoever is listening, and says whether it was taken.
     * <p>
     * Taken means the caller should not draw it: the geometry has been read off the pose it was about
     * to use, and the buffer it would have gone into is not one a server has any use for.
     */
    public static boolean take(Object model, PoseStack poseStack) {
        return take(model, poseStack, null);
    }

    public static boolean take(Object model, PoseStack poseStack, @Nullable Object buffer) {
        Sink sink = ACTIVE.get();
        if (sink == null) {
            return false;
        }
        sink.drawn(model, poseStack, buffer == null ? null : BOUND.get().get(buffer));
        return true;
    }

    /**
     * Remembers what a buffer was asked for, so that whatever is drawn into it can be given the same
     * picture. Kept by identity and only while a renderer is being run on purpose; the map is dropped
     * with the run, so nothing here outlives the tick that made it.
     */
    public static void boundFor(Object buffer, RenderType renderType) {
        if (buffer != null && renderType != null) {
            BOUND.get().put(buffer, renderType);
        }
    }

    /** Called when a run ends, so a buffer from one tick is never read as another tick's. */
    public static void forgetBuffers() {
        BOUND.get().clear();
    }

    private static final ThreadLocal<java.util.Map<Object, RenderType>> BOUND =
        ThreadLocal.withInitial(java.util.IdentityHashMap::new);
}
