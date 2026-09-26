package me.drex.polymerpatcher.mixin.enderscape;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.penumbra.enderscape.manager.LowGravityManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(LowGravityManager.class)
public abstract class LowGravityManagerMixin {
    @Inject(method = "tickTail", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V", shift = At.Shift.AFTER))
    private static void polymerPatcher$syncPlayerMotion(LivingEntity entity, CallbackInfo ci,
                                                        @Local(name = "frictionMod") double frictionMod) {
        if (entity instanceof ServerPlayer player && frictionMod != 0) {
            Vec3 movement = player.getKnownMovement();
            player.connection.send(new ClientboundPlayerPositionPacket(0,
                new PositionMoveRotation(Vec3.ZERO,
                    new Vec3(movement.x / frictionMod - movement.x, 0, movement.z / frictionMod - movement.z),
                    0, 0),
                Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z, Relative.X, Relative.Y,
                    Relative.Z, Relative.X_ROT, Relative.Y_ROT)));
        }
    }
}
