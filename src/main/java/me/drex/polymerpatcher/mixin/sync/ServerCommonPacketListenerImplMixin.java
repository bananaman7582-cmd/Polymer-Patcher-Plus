package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.registry.ClientSafeStats;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Removes registry-backed statistics a particular client cannot decode before packet encoding. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private Packet<?> polymerPatcher$sanitizeStats(Packet<?> packet) {
        if (!(packet instanceof ClientboundAwardStatsPacket award)
            || !((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return packet;
        }
        var safe = ClientSafeStats.sanitize(award.stats(),
            namespace -> NativeClients.carries(listener.player, namespace));
        return safe == award.stats() ? packet : new ClientboundAwardStatsPacket(safe);
    }
}
