package me.drex.polymerpatcher.mixin.enderscape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.penumbra.enderscape.entity.magnia.MagniaAffected;
import net.penumbra.enderscape.particle.MagniaParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MagniaAffected.class)
public abstract class MagniaAffectedMixin {
    /** Recreates the client-only attraction trail with a vanilla particle sent by the server. */
    @Overwrite
    public static void sendEntityEffectParticles(ServerLevel level, Entity entity,
                                                  MagniaParticleOptions options, float chance) {
        if (level == null || entity == null || !entity.isAlive()
            || (entity.getDeltaMovement().lengthSqr() <= 0.02 && entity.getRandom().nextInt(12) != 0)) {
            return;
        }

        AABB box = entity.getBoundingBox();
        Vec3 pos = entity.position().add(0, box.getYsize() / (entity instanceof ItemEntity ? 0.5 : 2), 0);
        if (level.getRandom().nextFloat() <= chance) {
            level.sendParticles(new DustColorTransitionOptions(options.color(), options.fadeColor(),
                    options.colorFadeRate() * 4),
                pos.x, pos.y, pos.z, 1, box.getXsize() * 0.6, box.getYsize() * 0.6,
                box.getZsize() * 0.6, 1);
        }
    }
}
