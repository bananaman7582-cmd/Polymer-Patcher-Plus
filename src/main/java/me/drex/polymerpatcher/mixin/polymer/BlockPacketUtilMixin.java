package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.core.impl.interfaces.ChunkDataS2CPacketInterface;
import eu.pb4.polymer.core.impl.networking.BlockPacketUtil;
import me.drex.polymerpatcher.util.ChunkSyncRepair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes each chunk Polymer told a player about itself, so {@link ChunkSyncRepair} only covers the ones it
 * never saw being sent.
 */
@Mixin(value = BlockPacketUtil.class, remap = false)
public class BlockPacketUtilMixin {
    @Inject(method = "sendFromPacket", at = @At("HEAD"), require = 0)
    private static void polymer_patcher$noteChunkSent(Packet<?> packet, ServerGamePacketListenerImpl handler, CallbackInfo ci) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            LevelChunk chunk = ((ChunkDataS2CPacketInterface) (Object) packet).polymer$getWorldChunk();
            if (chunk != null) {
                ChunkSyncRepair.sentByPolymer(handler, chunk);
            }
        }
    }
}
