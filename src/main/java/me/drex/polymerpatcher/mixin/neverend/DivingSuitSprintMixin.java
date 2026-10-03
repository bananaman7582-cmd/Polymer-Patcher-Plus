package me.drex.polymerpatcher.mixin.neverend;

import me.drex.polymerpatcher.compat.neverend.NeverendDivingSuit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Mirrors Neverend's client-only sprint veto so vanilla clients cannot swim-sprint in the heavy suit. */
@Mixin(LivingEntity.class)
public abstract class DivingSuitSprintMixin {
    @ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
    private boolean polymerPatcher$heavyDivingSuit(boolean sprinting) {
        Object self = this;
        if (sprinting && self instanceof ServerPlayer player && player.isInWater()
            && !player.getAbilities().flying && NeverendDivingSuit.hasSuit(player)) {
            return false;
        }
        return sprinting;
    }
}
