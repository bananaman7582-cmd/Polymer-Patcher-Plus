package me.drex.polymerpatcher.dump.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class DumpSubmitNodeCollector implements SubmitNodeCollector {
    public static final SubmitNodeCollector INSTANCE = new DumpSubmitNodeCollector();

    private DumpSubmitNodeCollector() {
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
    public <S> void submitModel(Model<? super S> model, S object, PoseStack poseStack, RenderType renderType, int i, int j, int k, @Nullable TextureAtlasSprite textureAtlasSprite, int l, ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
        RenderRegistryGenerator.captureArmorModel(model);
        RenderRegistryGenerator.captureTexture(renderType);
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int i) {

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
        // Where a mod that draws its own geometry ends up - a Citadel model records its vertices and
        // submits them finished rather than handing its model over. Nothing usable comes back out of
        // the geometry itself, but the render type still names the texture it was drawn with, which is
        // the only thing the dump needs from it
        RenderRegistryGenerator.captureTexture(renderType);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState quadParticleRenderState) {

    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState, boolean bl) {

    }
}
