package me.drex.polymerpatcher.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * The pieces of {@code LivingEntityRenderer#submit} that a model has to be put through before it is
 * drawn.
 * <p>
 * A vanilla model reaches {@link me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector} with
 * all of this already applied to the stack it is handed. A Citadel model never reaches the collector
 * at all - it records its own vertices and submits them finished - so
 * {@link me.drex.polymerpatcher.entity.citadel.CitadelEntityModel} has to walk the same steps itself
 * to start from where the model expects to be.
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Invoker
    void invokeSetupRotations(LivingEntityRenderState renderState, PoseStack poseStack, float bodyRot, float scale);

    @Invoker
    void invokeScale(LivingEntityRenderState renderState, PoseStack poseStack);

    @Invoker
    RenderType invokeGetRenderType(LivingEntityRenderState renderState, boolean bodyVisible, boolean translucent, boolean glowing);

    @Invoker
    boolean invokeIsBodyVisible(LivingEntityRenderState renderState);

    @Invoker
    float invokeGetWhiteOverlayProgress(LivingEntityRenderState renderState);

    /**
     * The extra passes a renderer draws on top of its model - eyes, glow, saddles, the item a mob is
     * carrying. A Citadel mob never reaches the collector through the usual route, so nothing was ever
     * asking these to draw.
     */
    @Accessor("layers")
    List<RenderLayer<LivingEntityRenderState, ?>> polymer_patcher$layers();
}
