package me.drex.polymerpatcher.mixin.client;

import me.drex.polymerpatcher.client.ServerMinecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers a model that asks for the game client while this mod is posing it.
 * <p>
 * See {@link ServerMinecraft} for why: a model reaching for the partial tick mid-pose otherwise throws
 * and costs its mob every animation it has. Both hooks are gated on a model actually being posed, so
 * everything else on the server still sees the null that means "no client here".
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "getInstance", at = @At("RETURN"), cancellable = true)
    private static void polymerPatcher$standInWhilePosing(CallbackInfoReturnable<Minecraft> cir) {
        if (cir.getReturnValue() != null || !ServerMinecraft.rendering()) {
            return;
        }

        Minecraft standIn = ServerMinecraft.get();
        if (standIn != null) {
            cir.setReturnValue(standIn);
        }
    }

    /**
     * The stand-in has no delta tracker of its own - nothing on it was ever initialised - so it is
     * answered with one reporting no partial tick. That is the honest value here: models are posed
     * once per whole tick, which is exactly what a partial tick of zero describes.
     */
    @Inject(method = "getDeltaTracker", at = @At("HEAD"), cancellable = true)
    private void polymerPatcher$standInDeltaTracker(CallbackInfoReturnable<DeltaTracker> cir) {
        if ((Object) this == ServerMinecraft.peek()) {
            cir.setReturnValue(DeltaTracker.ZERO);
        }
    }
}
