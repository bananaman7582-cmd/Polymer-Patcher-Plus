package me.drex.polymerpatcher.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix4f;

/**
 * Fills in and applies the parts of a render state that decide which way a mob is pointing.
 * <p>
 * Both jobs exist for the same reason: a renderer is free to skip them, because on a client the
 * renderer does its own drawing afterwards and can rotate the mob whenever it likes. Here the render
 * state and the pose stack <i>are</i> the mob - they are what gets turned into matrices and sent - so
 * anything a renderer leaves out is simply gone.
 * <p>
 * Alex's Mobs leaves out the rotation entirely: its renderers override
 * {@code setupRotations} with an empty body and turn their mobs from inside their own drawing code,
 * which is the very code this mod replaces. Every one of its mobs therefore faced a single fixed
 * direction no matter which way it walked, and none of them fell over when killed.
 */
public final class ServerRenderStates {

    /** How far a dying mob is tipped over once it has fallen all the way. */
    private static final float FLIP_DEGREES = 90.0F;

    private ServerRenderStates() {
    }

    /**
     * Overwrites the rotation and death fields of {@code state} from the entity it describes.
     * <p>
     * These are read straight off the entity rather than trusted from the state, which is both more
     * reliable and fresher - the renderer's own extraction interpolates towards the current tick from
     * the previous one, and with no partial tick to interpolate by it lands on the previous tick's
     * value.
     */
    public static void applyRotations(Entity entity, EntityRenderState state) {
        if (!(state instanceof LivingEntityRenderState living) || !(entity instanceof LivingEntity alive)) {
            return;
        }

        // The body's own facing, which is what the mob is turned to; the head is then given as the
        // difference from it, exactly as the game states it
        float bodyRot = alive.yBodyRot;
        living.bodyRot = bodyRot;
        living.yRot = Mth.wrapDegrees(alive.getYHeadRot() - bodyRot);
        living.xRot = alive.getXRot();

        // Counts up from the killing blow and is what tips a dying mob onto its side
        living.deathTime = alive.deathTime > 0 ? (float) alive.deathTime : 0.0F;
    }

    /**
     * Keeps the walking figure for one mob between ticks.
     * <p>
     * A mob's stride is a running total rather than a reading, so it has to be carried from tick to
     * tick. One of these belongs to each mob being drawn.
     */
    public static final class Walk {
        private float position;
        private float speed;
        private double x;
        private double y;
        private double z;
        private boolean started;

        /** Track stride at the game's tick rate, independent of how often a model is rendered. */
        public void observe(Entity entity) {
            observe(entity.getX(), entity.getY(), entity.getZ());
        }

        /** Coordinates are accepted separately so the stride calculation can be checked without a world. */
        public void observe(double x, double y, double z) {
            if (started) {
                double dx = x - this.x;
                double dy = y - this.y;
                double dz = z - this.z;
                // Match the previous one-tick speed cap. A teleport should not become a sprint.
                speed = (float) Math.min(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4.0, 1.0);
                position += speed;
            }
            this.x = x;
            this.y = y;
            this.z = z;
            this.started = true;
        }

        /**
         * Fills in the walking figures if the renderer left them at nothing.
         * <p>
         * The game works these out as a mob moves itself, and a mob that is put where it should be
         * instead - which is how Alex's Mobs drags a centipede's segments along behind its head - never
         * moves itself at all, so on a server they stay at nothing and its legs never move. A client
         * never sees that, because it works the same figures out from where the mob was last frame
         * against where it is now. So do the same, from where it was last tick.
         * <p>
         * Only when the renderer gave nothing: a mob that already walks properly is left alone.
         */
        public void apply(EntityRenderState state) {
            if (!(state instanceof LivingEntityRenderState living)) {
                return;
            }
            if (started && living.walkAnimationSpeed == 0.0F) {
                living.walkAnimationSpeed = speed;
                living.walkAnimationPos = position;
            }
        }
    }

    /**
     * Turns {@code poseStack} to face the way the mob is facing, and tips it over if it is dying.
     * <p>
     * The same steps {@code LivingEntityRenderer#setupRotations} takes, kept here for the renderers
     * that do not take them.
     */
    public static void setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot) {
        boolean sleeping = state.hasPose(Pose.SLEEPING);

        if (!sleeping) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
        }

        if (state.deathTime > 0.0F) {
            float progress = Math.min(1.0F, Mth.sqrt((state.deathTime - 1.0F) / 20.0F * 1.6F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(progress * FLIP_DEGREES));
        } else if (state.isAutoSpinAttack) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F - state.xRot));
            poseStack.mulPose(Axis.YP.rotationDegrees(state.ageInTicks * -75.0F));
        } else if (sleeping) {
            Direction bed = state.bedOrientation;
            poseStack.mulPose(Axis.YP.rotationDegrees(bed != null ? bed.toYRot() : bodyRot));
            poseStack.mulPose(Axis.ZP.rotationDegrees(FLIP_DEGREES));
            poseStack.mulPose(Axis.YP.rotationDegrees(270.0F));
        } else if (state.isUpsideDown) {
            poseStack.translate(0.0F, state.boundingBoxHeight + 0.1F, 0.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        }
    }

    /**
     * Runs the renderer's own rotation step, falling back to {@link #setupRotations} if it did nothing.
     * <p>
     * A renderer that hands the stack back exactly as it found it has not rotated the mob, so the
     * standard behaviour is put back rather than leaving it pointing nowhere. One that did rotate is
     * left alone, keeping whatever tilt or flourish it wanted.
     */
    public static void rotate(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, Runnable rendererRotations) {
        Matrix4f before = new Matrix4f(poseStack.last().pose());
        rendererRotations.run();
        if (poseStack.last().pose().equals(before, 1.0E-6F)) {
            setupRotations(state, poseStack, bodyRot);
        }
    }
}
