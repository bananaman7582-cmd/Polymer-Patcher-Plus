package me.drex.polymerpatcher.mixin.block;

import me.drex.polymerpatcher.companion.CompanionSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Leaves a companion breaker out of their own block's break sound; see {@link CompanionBreakSoundMixin}.
 * <p>
 * Ordered after Polymer's sound patcher, which clears the excluded player for sounds it handles, so this
 * has the last word.
 */
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class ServerLevelCompanionSoundMixin {

    @ModifyVariable(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private Entity polymerPatcher$leaveOutCompanionBreaker(Entity source) {
        ServerPlayer breaker = CompanionSounds.BREAKER.get();
        return source == null && breaker != null ? breaker : source;
    }
}
