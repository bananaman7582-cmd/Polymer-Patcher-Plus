package me.drex.polymerpatcher.mixin.sync;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.TimeoutException;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A status connection cannot receive a common/play disconnect packet. Minecraft's generic
 * exception handler nevertheless sends one for non-timeout decoding faults, causing a second,
 * noisy EncoderException ("Sending unknown packet clientbound/minecraft:disconnect"). Close
 * only these early server-side connections instead; login and gameplay retain vanilla handling.
 */
@Mixin(Connection.class)
public abstract class ConnectionEarlyFaultMixin {
    @Shadow @Nullable private volatile PacketListener packetListener;

    @Shadow public abstract PacketFlow getSending();

    @Shadow public abstract void disconnect(Component reason);

    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void polymerPatcher$closeEarlyProtocolFault(ChannelHandlerContext context,
                                                         Throwable fault, CallbackInfo ci) {
        if (fault instanceof TimeoutException || getSending() != PacketFlow.CLIENTBOUND) {
            return;
        }
        PacketListener listener = packetListener;
        if (listener == null) {
            return;
        }
        ConnectionProtocol protocol = listener.protocol();
        if (protocol != ConnectionProtocol.HANDSHAKING && protocol != ConnectionProtocol.STATUS) {
            return;
        }

        // These protocols have no disconnect packet. The original fault is a bad/incomplete probe;
        // forwarding the exception to vanilla would generate a second, unencodable packet error.
        disconnect(Component.translatable("multiplayer.disconnect.generic"));
        ci.cancel();
    }
}
