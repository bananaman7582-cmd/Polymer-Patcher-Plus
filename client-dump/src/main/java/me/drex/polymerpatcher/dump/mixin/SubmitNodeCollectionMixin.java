package me.drex.polymerpatcher.dump.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
    @Inject(method = "submitModel", at = @At("HEAD"))
    public <S> void captureTextures(
        Model<? super S> model, S object, PoseStack poseStack, RenderType renderType, int i, int j, int k,
        @Nullable TextureAtlasSprite textureAtlasSprite, int l,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay, CallbackInfo ci
    ) {
        RenderRegistryGenerator.captureTexture(renderType);
    }

    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    public void captureCustomGeometryTextures(
        PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer,
        CallbackInfo ci
    ) {
        RenderRegistryGenerator.captureTexture(renderType);
    }
}
