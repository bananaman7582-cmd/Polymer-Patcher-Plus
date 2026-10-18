package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Throws away a particle packet that has no particle in it, rather than letting it disconnect the player.
 * <p>
 * Something built one as a player was pulled into the Wither Storm's bowels. It cannot be written - the
 * packet's first step is to ask the particle what it is - and failing to write it ended that player's
 * connection on the spot. There is nothing to show for a particle that is not there, so it is dropped,
 * and the first time it happens the log says where it came from.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class EmptyParticlePacketGuardMixin {
    @Unique
    private static final AtomicBoolean polymer_patcher$reported = new AtomicBoolean();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void polymer_patcher$dropEmptyParticle(Packet<?> packet, CallbackInfo ci) {
        if (polymer_patcher$isEmptyParticle(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void polymer_patcher$dropEmptyParticle(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, CallbackInfo ci) {
        if (polymer_patcher$isEmptyParticle(packet)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean polymer_patcher$isEmptyParticle(Packet<?> packet) {
        if (!(packet instanceof ClientboundLevelParticlesPacket particles) || particles.getParticle() != null) {
            return false;
        }
        if (polymer_patcher$reported.compareAndSet(false, true)) {
            PolymerPatcher.LOGGER.warn("Dropped a particle packet with no particle in it, which would have disconnected the player",
                new IllegalStateException("sent from here"));
        }
        return true;
    }
}
