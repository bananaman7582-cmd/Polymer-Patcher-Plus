package me.drex.polymerpatcher.mixin.borrowedecho;

import me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-spawns the wire stand-in when Borrowed Echo changes between display/player/creature forms. */
@Mixin(targets = "com.borrowedecho.entity.BorrowedEchoEntity", remap = false)
public abstract class BorrowedEchoEntityMixin {
    @Unique private BorrowedEchoCompat.Presentation polymer_patcher$presentationBefore;

    @Inject(method = "setEntityDisguise", at = @At("HEAD"), require = 0)
    private void polymer_patcher$beforeDisguise(String disguise, CallbackInfo ci) {
        polymer_patcher$presentationBefore = BorrowedEchoCompat.presentation((Entity) (Object) this);
    }

    @Inject(method = "setEntityDisguise", at = @At("RETURN"), require = 0)
    private void polymer_patcher$afterDisguise(String disguise, CallbackInfo ci) {
        BorrowedEchoCompat.refreshTrackingIfChanged((Entity) (Object) this, polymer_patcher$presentationBefore);
    }

    @Inject(method = "setTrueForm", at = @At("HEAD"), require = 0)
    private void polymer_patcher$beforeTrueForm(boolean trueForm, CallbackInfo ci) {
        polymer_patcher$presentationBefore = BorrowedEchoCompat.presentation((Entity) (Object) this);
    }

    @Inject(method = "setTrueForm", at = @At("RETURN"), require = 0)
    private void polymer_patcher$afterTrueForm(boolean trueForm, CallbackInfo ci) {
        BorrowedEchoCompat.refreshTrackingIfChanged((Entity) (Object) this, polymer_patcher$presentationBefore);
    }

    // isTransforming/isBrokenHead are computed from EncounterPhase rather than stored by either
    // setter above. @Coerce keeps that optional mod class out of our method descriptor and jar.
    @Inject(method = "setPhase", at = @At("HEAD"), require = 0)
    private void polymer_patcher$beforePhase(@Coerce Object phase, int limit, CallbackInfo ci) {
        polymer_patcher$presentationBefore = BorrowedEchoCompat.presentation((Entity) (Object) this);
    }

    @Inject(method = "setPhase", at = @At("RETURN"), require = 0)
    private void polymer_patcher$afterPhase(@Coerce Object phase, int limit, CallbackInfo ci) {
        BorrowedEchoCompat.refreshTrackingIfChanged((Entity) (Object) this, polymer_patcher$presentationBefore);
    }
}
