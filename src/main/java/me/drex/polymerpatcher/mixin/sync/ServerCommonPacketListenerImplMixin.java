package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.registry.ClientSafeStats;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /**
     * A mod syncing its configuration during the network configuration phase checks first whether
     * the client declared the channel for it, and Spell Engine answers a client which did not with
     * a deliberate disconnect: {@code Network configuration task not supported: spell_engine:config}.
     * That is reasonable on a server where the mod is expected, and fatal here, where a client
     * without Spell Engine is precisely the client this mod exists for.
     *
     * <p>The task was never queued for a client that declined the channel, so nothing is left
     * outstanding: letting the connection stand simply continues configuration with that mod's sync
     * left out, exactly as a mod which skipped the task silently would behave.</p>
     */
    @Inject(
        method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void polymerPatcher$keepClientWithoutModConfig(DisconnectionDetails details, CallbackInfo ci) {
        if (!((Object) this instanceof ServerConfigurationPacketListenerImpl)) {
            return;
        }
        String reason = details.reason().getString();
        if (reason.contains("configuration task not supported")
            || reason.contains("incompatible_configuration_task")) {
            PolymerPatcher.LOGGER.info(
                "Letting a client join without the mod configuration it declined: {}", reason);
            ci.cancel();
        }
    }
}
