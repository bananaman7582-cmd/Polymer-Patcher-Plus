package me.drex.polymerpatcher.mixin.entity;

import me.drex.polymerpatcher.entity.HeldPlayers;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Settles each player's hold once their tick has run the blocks they are standing in. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerHoldMixin {
    @Inject(method = "doTick", at = @At("RETURN"))
    private void polymerPatcher$applyHold(CallbackInfo ci) {
        HeldPlayers.afterTick((ServerPlayer) (Object) this);
    }
}
