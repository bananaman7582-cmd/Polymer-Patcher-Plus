package me.drex.polymerpatcher.entity.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.client.rendering.CubeConsumer;
import me.drex.polymerpatcher.client.rendering.NoOpVertexConsumer;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import me.drex.polymerpatcher.mixin.client.render.RenderSetupAccessor;
import me.drex.polymerpatcher.mixin.client.render.RenderTypeAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class ServerSubmitNodeCollector<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> implements SubmitNodeCollector {
    private final SimpleEntityModel<Entity, RenderState, Model> entityModel;
    public static final ThreadLocal<SimpleEntityModel> ACTIVE_ENTITY = new ThreadLocal<>();

    /**
     * While quiet, everything submitted the ordinary way is ignored.
     * <p>
     * Used while a renderer is being run only to see what it draws by hand. Whatever it submits
     * normally has already been drawn by the caller, and taking it a second time would draw the mob
     * twice over itself.
     */
    public boolean quiet;

    public ServerSubmitNodeCollector(SimpleEntityModel<Entity, RenderState, Model> entityModel) {
        this.entityModel = entityModel;
    }

    @Override
    public OrderedSubmitNodeCollector order(int i) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float f, List<EntityRenderState.ShadowPiece> list) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 vec3, int i, Component component, boolean bl, int j, CameraRenderState cameraRenderState) {
    }

    @Override
    public void submitText(PoseStack poseStack, float f, float g, FormattedCharSequence formattedCharSequence, boolean bl, Font.DisplayMode displayMode, int i, int j, int k, int l) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf quaternionf) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S object, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords, int tintedColor, @Nullable TextureAtlasSprite textureAtlasSprite, int l, ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
        if (quiet) {
            return;
        }
        RenderSetup renderSetup = ((RenderTypeAccessor) renderType).getState();
        Map<String, RenderSetup.TextureBinding> textures = ((RenderSetupAccessor) (Object) renderSetup).polymer_patcher$textures();
        RenderPipeline pipeline = ((RenderSetupAccessor) (Object) renderSetup).polymer_patcher$pipeline();
        var translucent = RenderSetups.isTranslucent(pipeline);

        // The mob's own texture, not whichever of the render type's samplers a map happened to hand
        // back first - the light map is in there too, and a model filed under it does not exist
        Identifier texture = RenderSetups.mainTexture(textures);
        if (texture == null) {
            return;
        }

        // Posed here, from the state handed in alongside it, because that is what a client does with
        // the pair - it keeps them until the drawing pass and poses the model then. Going straight to
        // renderToBuffer instead drew whatever pose the model happened to be left in.
        //
        // The mob's own model never showed it, because it is posed by hand before being submitted. Its
        // extra layers are not: the overlay a Variants & Ventures zombie wears, and every piece of
        // armour, were drawn in the pose their model file gave them - head straight ahead, limbs still,
        // never turning with the body underneath.
        try {
            model.setupAnim(object);
        } catch (Throwable e) {
            // A layer that will not pose is still worth drawing in whatever pose it is in
            PolymerPatcher.LOGGER.debug("Failed to pose {} before drawing it", model.getClass().getName(), e);
        }

        // Put back rather than cleared. A renderer that draws straight to a buffer sets one of these
        // around its whole pass, and clearing here would take that one away the first time the mob
        // submitted a model the ordinary way - so everything drawn afterwards fell on the floor
        CubeConsumer previous = CubeConsumer.CONSUMER.get();
        CubeConsumer.CONSUMER.set((part, matrix4f, hidden) -> entityModel.updateModelPart(part, matrix4f, overlayCoords, texture, translucent, hidden));
        try {
            model.renderToBuffer(poseStack, NoOpVertexConsumer.INSTANCE, lightCoords, overlayCoords, tintedColor);
        } finally {
            if (previous == null) {
                CubeConsumer.CONSUMER.remove();
            } else {
                CubeConsumer.CONSUMER.set(previous);
            }
        }
    }

    @Override
    public void submitModelPart(ModelPart modelPart, PoseStack poseStack, @NotNull RenderType renderType, int i, int j, @Nullable TextureAtlasSprite textureAtlasSprite, int k, ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay, int l) {
    }

    /**
     * A block being carried by an entity - a falling block, an enderman's handful of dirt.
     * <p>
     * There used to be a {@code submitBlock} taking the state directly; 26.2 folded that into the
     * moving-block path, which carries the same state on the render state it is handed.
     */
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int i) {
        entityModel.updateBlock(movingBlockRenderState.blockState, poseStack.last().pose());
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> list, int[] is, int i, int j, int k) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> list, int i) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape voxelShape, RenderType renderType, int i, float f, boolean bl) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext itemDisplayContext, int i, int j, int k, int[] is, List<BakedQuad> list, ItemStackRenderState.FoilType foilType) {
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer customGeometryRenderer) {
        // Where a Citadel model's finished vertices arrive, already flattened out of their parts.
        // Nothing can be recovered from them, which is why CitadelEntityModel walks the model itself
        // rather than waiting for it to be submitted
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState quadParticleRenderState) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState, boolean bl) {
    }
}
