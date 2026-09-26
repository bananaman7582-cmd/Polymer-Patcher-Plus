package me.drex.polymerpatcher.mixin.client;

import me.drex.polymerpatcher.client.ServerMinecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Omits client-only name-tag visibility checks while models are posed on the server. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(
        method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void polymer_patcher$skipNameTag(LivingEntity entity, double distance,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (ServerMinecraft.rendering()) {
            // ServerSubmitNodeCollector intentionally does not emit name tags. Reaching vanilla's
            // visibility test only asks Minecraft.getInstance().player, which the headless stand-in
            // cannot have and which made Underminers fail before their model was ever posed.
            cir.setReturnValue(false);
        }
    }
}
