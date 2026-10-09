package me.drex.polymerpatcher.mixin.entity;

import me.drex.polymerpatcher.entity.HeldPlayers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hears a modded block sticking a player the cobweb's way, so their client can be made to feel it. */
@Mixin(Entity.class)
public abstract class StuckInBlockMixin {
    @Inject(method = "makeStuckInBlock", at = @At("HEAD"))
    private void polymerPatcher$holdOnClient(BlockState state, Vec3 multiplier, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            HeldPlayers.stuck(player, state, multiplier);
        }
    }
}
