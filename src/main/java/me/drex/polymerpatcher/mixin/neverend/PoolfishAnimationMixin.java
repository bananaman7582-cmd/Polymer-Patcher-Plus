package me.drex.polymerpatcher.mixin.neverend;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "doctor4t.liminalpools.common.entity.PoolfishEntity")
public abstract class PoolfishAnimationMixin extends Entity {
    @Shadow public AnimationState idleWaterAnimationState;
    @Shadow public AnimationState swimAnimationState;

    private PoolfishAnimationMixin() { super(null, null); }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymerPatcher$animateOnServer(CallbackInfo ci) {
        if (level().isClientSide()) return;
        if (getDeltaMovement().multiply(1.0, 0.0, 1.0).length() >= 0.01) {
            idleWaterAnimationState.stop();
            swimAnimationState.startIfStopped(tickCount);
        } else {
            swimAnimationState.stop();
            idleWaterAnimationState.startIfStopped(tickCount);
        }
    }
}
