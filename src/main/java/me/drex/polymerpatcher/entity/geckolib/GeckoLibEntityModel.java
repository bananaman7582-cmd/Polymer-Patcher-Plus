package me.drex.polymerpatcher.entity.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Stands in for a mob GeckoLib draws, which the ordinary path cannot.
 * <p>
 * The problem is the Citadel one: a GeckoLib renderer turns its bones into vertices itself and submits
 * them already finished, so by the time anything reaches
 * {@link me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector} there is nothing left to say
 * which bone was which. The answer is the same too - run the renderer's own pass, then read the bones
 * off it directly.
 * <p>
 * The difference is where that reading has to happen. GeckoLib puts a bone's animated pose on it only
 * for the duration of one callback and takes it off again afterwards, so the walk goes inside that
 * callback; outside it, every bone is back in the pose its model file gave it and the mob would stand
 * perfectly still.
 */
public class GeckoLibEntityModel<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> extends SimpleEntityModel<Entity, RenderState, Model> {
    private final GeckoLibModelInstance<Entity, RenderState, Model> model;

    public GeckoLibEntityModel(Entity entity, GeckoLibModelInstance<Entity, RenderState, Model> model) {
        super(entity, (net.minecraft.client.renderer.entity.EntityRenderer<Entity, RenderState>) model.renderer());
        this.model = model;
    }

    @Override
    protected void render(PoseStack poseStack) {
        // Built through GeckoLib's own two-argument form: its no-argument one returns null on purpose,
        // and handing that to extractRenderState is what failed every GeckoLib mob on its first tick
        @SuppressWarnings("unchecked")
        RenderState renderState = (RenderState) GeckoLibModel.createRenderState(renderer, entity, 0);
        if (renderState == null) {
            // Reported rather than passed over: with no state there is nothing to pose, so the mob is
            // invisible from here on, and a silent return is what made that look like nothing happening
            throw new IllegalStateException("GeckoLib renderer " + renderer.getClass().getName()
                + " built no render state: it has no two-argument createRenderState, or every one it has returned null");
        }

        // A renderer picks its texture per entity, and the generated models are filed by texture, so
        // which one the mob is wearing this tick decides which models its pieces are shown as
        Identifier location = GeckoLibModel.textureLocation(renderer, renderState);
        Identifier texture = location != null ? GeckoLibModel.strip(location) : model.texture();

        float[] scale = GeckoLibModel.scale(renderer);

        // Whether the mob is meant to be flashing white-red this tick, which is a thing the render state
        // already worked out from its hurt and death timers. The item every piece is shown as carries
        // that tint, so a hurt mob was drawn as if nothing had touched it until this was read across
        boolean redOverlay = renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living
            && living.hasRedOverlay;

        // Debug probe: does GeckoLib's pipeline have controllers and controller states this tick?
        GeckoLibModel.diagnosePipelineOnce(renderer, renderState);

        activeElementIndex = 0;
        // Pose fingerprints are diagnostics, not part of rendering. Hashing every bone and updating
        // the diagnostic maps on production servers adds work to every GeckoLib model tick for logs
        // that are not enabled there.
        long[] poseHash = me.drex.polymerpatcher.PolymerPatcher.LOGGER.isDebugEnabled() ? new long[1] : null;
        boolean posed = GeckoLibModel.renderPosed(renderer, renderState, poseStack, scale[0], scale[1], () -> {
            for (GeckoLibBone root : model.roots()) {
                root.visit(renderer, poseStack, (piece, matrix) -> {
                    if (poseHash != null) {
                        poseHash[0] = poseHash[0] * 31 + hashMatrix(matrix);
                    }
                    updateItemDisplayElement(createItemStack(model.modelPath(texture, piece.id()), redOverlay, false), matrix);
                });
            }
        });
        if (poseHash != null) {
            GeckoLibModel.notePose(renderer.getClass().getName(), poseHash[0]);
        }

        // Nothing posed means nothing to show this tick, which the trailing sweep in onTick turns into
        // every one of the mob's elements going away
        if (!posed) {
            activeElementIndex = 0;
            return;
        }

        // A pass that ran without drawing anything is a mob that is there and invisible, which looks
        // from the outside exactly like one that was never registered at all
        if (activeElementIndex == 0) {
            throw new IllegalStateException("GeckoLib posed " + renderer.getClass().getName()
                + " without producing a single bone; its model has " + model.roots().size() + " root bone(s)");
        }
    }

    /**
     * A cheap fingerprint of one piece's placement, so poses across ticks can be told apart. Only the
     * translation and first basis vector are folded in - plenty to prove a moving bone.
     */
    private static long hashMatrix(Matrix4f matrix) {
        return Float.floatToIntBits(matrix.m03()) ^ Float.floatToIntBits(matrix.m13()) ^ Float.floatToIntBits(matrix.m23())
            ^ (Float.floatToIntBits(matrix.m00()) << 1) ^ (Float.floatToIntBits(matrix.m01()) << 2) ^ (Float.floatToIntBits(matrix.m02()) << 3);
    }
}
