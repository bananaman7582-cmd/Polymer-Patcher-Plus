package me.drex.polymerpatcher.mixin.sync;

import eu.pb4.polymer.virtualentity.impl.HolderHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Clears empty slots out of a player's list of displays before Polymer walks it to drop a chunk.
 * <p>
 * An empty slot there crashed the server as a player disconnected. What puts one there is now kept on
 * the server thread (see ElementHolderMixin), and this makes sure one left behind can still never do it.
 */
@Mixin(value = PlayerChunkSender.class, priority = 900)
public abstract class DropChunkHolderGuardMixin {
    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void polymer_patcher$clearEmptySlots(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        if (player != null && player.connection instanceof HolderHolder holders) {
            try {
                holders.polymer$getHolders().removeIf(Objects::isNull);
            } catch (Throwable ignored) {
                // Nothing to tidy, or not ours to tidy
            }
        }
    }
}
