package me.drex.polymerpatcher.mixin.enderscape;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.entity.rustle.Rustle;
import net.penumbra.enderscape.entity.rustle.RustleConversionPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Rustle.class)
public abstract class RustleMixin extends Animal {
    @Shadow public abstract RustleConversionPhase getConversionPhase();
    @Shadow protected abstract void soloConversionAnimation(AnimationState state);
    @Shadow @Final public AnimationState conversionBeginAnimationState;
    @Shadow @Final public AnimationState conversionAnimationState;
    @Shadow @Final public AnimationState conversionEndAnimationState;
    @Shadow @Final private static EntityDataAccessor<RustleConversionPhase> CONVERSION_PHASE;

    protected RustleMixin(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (CONVERSION_PHASE.equals(data)) {
            switch (this.getConversionPhase()) {
                case BEGINNING -> this.soloConversionAnimation(this.conversionBeginAnimationState);
                case CONVERTING -> this.soloConversionAnimation(this.conversionAnimationState);
                case ENDING -> this.soloConversionAnimation(this.conversionEndAnimationState);
            }
        }
        super.onSyncedDataUpdated(data);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isClientSide()Z"))
    private boolean polymerPatcher$runAnimationStateLogic(Level level) {
        return true;
    }
}
