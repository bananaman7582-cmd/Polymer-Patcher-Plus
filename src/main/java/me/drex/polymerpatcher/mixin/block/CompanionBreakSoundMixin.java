package me.drex.polymerpatcher.mixin.block;

import me.drex.polymerpatcher.companion.CompanionServer;
import me.drex.polymerpatcher.companion.CompanionSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes who is breaking a block while its break effects go out.
 * <p>
 * Polymer's sound patcher plays a block's break sound to everybody around it, the breaker included,
 * because an ordinary breaker's client played its carrier's sound instead. A companion breaker's client
 * plays the real one itself, so it is left out - see {@code ServerLevelCompanionSoundMixin}. Ordered after
 * the patcher's own hook, so the note is still there when its sound is played.
 */
@Mixin(value = Block.class, priority = 1500)
public abstract class CompanionBreakSoundMixin {

    @Inject(method = "spawnDestroyParticles", at = @At("HEAD"))
    private void polymerPatcher$noteCompanionBreaker(Level level, Player player, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!level.isClientSide() && player instanceof ServerPlayer breaker
            && CompanionServer.hasBlock(breaker, state.getBlock())
            && CompanionSounds.playedLocally(state.getSoundType().getBreakSound())) {
            CompanionSounds.BREAKER.set(breaker);
        }
    }

    @Inject(method = "spawnDestroyParticles", at = @At("TAIL"))
    private void polymerPatcher$forgetCompanionBreaker(Level level, Player player, BlockPos pos, BlockState state, CallbackInfo ci) {
        CompanionSounds.BREAKER.remove();
    }
}
