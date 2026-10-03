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
@Mixin(targets = "doctor4t.liminalpools.common.entity.GrandgousierEntity")
public abstract class GrandgousierAnimationMixin extends Entity {
    @Shadow public AnimationState swimAnimationState;
    @Shadow public AnimationState chaseAnimationState;
    @Shadow public AnimationState biteAnimationState;
    @Shadow public int biteTimer;
    @Shadow public abstract boolean isChasing();

    private GrandgousierAnimationMixin() { super(null, null); }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymerPatcher$animateOnServer(CallbackInfo ci) {
        if (level().isClientSide()) return;
        if (biteTimer > 0) {
            swimAnimationState.stop();
            chaseAnimationState.stop();
            biteAnimationState.startIfStopped(tickCount);
            if (--biteTimer == 0) biteAnimationState.stop();
        } else if (isChasing()) {
            swimAnimationState.stop();
            chaseAnimationState.startIfStopped(tickCount);
        } else {
            chaseAnimationState.stop();
            swimAnimationState.startIfStopped(tickCount);
        }
    }
}
