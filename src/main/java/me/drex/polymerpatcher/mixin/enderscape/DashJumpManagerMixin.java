package me.drex.polymerpatcher.mixin.enderscape;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.manager.DashJumpManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DashJumpManager.class)
public abstract class DashJumpManagerMixin {
    @Redirect(method = "createDashJumpParticles", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private static void polymerPatcher$sendParticles(Level level, ParticleOptions particle, double x, double y,
                                                      double z, double dx, double dy, double dz) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(particle, x, y, z, 0, dx, dy, dz, 1);
        }
    }
}
