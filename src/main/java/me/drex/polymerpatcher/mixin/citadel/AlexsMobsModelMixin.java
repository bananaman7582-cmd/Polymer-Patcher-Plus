package me.drex.polymerpatcher.mixin.citadel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.drex.polymerpatcher.entity.citadel.CitadelDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The moment one of AlexsMobs' Citadel models is drawn, handed to whoever asked for it.
 * <p>
 * Citadel is shaded into each mod that uses it, so there is no one class to name - each copy lives
 * under its own package and needs naming separately. The config's plugin leaves this out entirely
 * when the mod is not installed.
 * <p>
 * This does nothing unless a renderer is being run deliberately, on the thread running it. Nothing
 * else on a server draws these, so every other call costs one field read and carries on untouched.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.citadel.client.model.basic.BasicEntityModel", remap = false)
public abstract class AlexsMobsModelMixin {

    @Inject(
        method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void polymer_patcher$takeItInstead(
        PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
        float red, float green, float blue, float alpha, CallbackInfo callback
    ) {
        if (CitadelDraw.take(this, poseStack, buffer)) {
            callback.cancel();
        }
    }
}
