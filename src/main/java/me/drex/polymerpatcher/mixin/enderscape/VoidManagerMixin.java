package me.drex.polymerpatcher.mixin.enderscape;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.manager.VoidManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VoidManager.class)
public abstract class VoidManagerMixin {
    @Shadow
    public static float getVoidTicksPercentage(Entity entity) {
        throw new AssertionError();
    }

    @Inject(method = "tickTail", at = @At("TAIL"))
    private static void polymerPatcher$emulateOverlay(Entity entity, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && getVoidTicksPercentage(player) > 0.45F) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 99, true, false, false));
        }
    }

    @Redirect(method = "tickVoidedParticles", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private static void polymerPatcher$sendParticles(Level level, ParticleOptions particle, double x, double y,
                                                      double z, double dx, double dy, double dz) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(particle, x, y, z, 0, dx, dy, dz, 1);
        }
    }
}
