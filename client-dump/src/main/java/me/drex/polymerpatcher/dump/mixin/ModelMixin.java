package me.drex.polymerpatcher.dump.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures models custom armor renderers draw directly into a vertex buffer. */
@Mixin(Model.class)
public abstract class ModelMixin {
    @Inject(method = "renderToBuffer", at = @At("HEAD"))
    private void polymerPatcher$captureArmorModel(PoseStack poseStack, VertexConsumer vertexConsumer,
                                                   int light, int overlay, int color, CallbackInfo ci) {
        RenderRegistryGenerator.captureArmorModel((Model<?>) (Object) this);
    }
}
