package me.drex.polymerpatcher.mixin.sync;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import me.drex.polymerpatcher.util.HolderRefresh;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notices a display shown to somebody nobody knows anything about yet.
 * <p>
 * For the first second or so of a session there is no way to tell whether a client has the mods this
 * server patches, and a display's fields are numbered differently depending on the answer. Rather
 * than guess and disconnect half the players who join, only the fields every entity shares are sent
 * in that moment - which leaves the display with nothing on it.
 * <p>
 * That is recoverable, but only if somebody remembers to recover it: a display's contents go out
 * again when they change, and a modded block sitting in the world has no reason ever to change. So
 * each one shown during the gap is noted here, and shown again once the client has spoken.
 */
@Mixin(value = ElementHolder.class, remap = false)
public abstract class ElementHolderMixin {

    private static final java.util.concurrent.atomic.AtomicBoolean polymer_patcher$reportedOffThread = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Moves a display being shown to, or taken from, a player onto the server thread.
     * <p>
     * Polymer keeps the displays each player is watching in a plain list. Changed from two threads at
     * once, a list like that can end up with an empty slot in it, and the next time Polymer walked it -
     * when that player left and their chunks were dropped - it found nothing there and took the whole
     * server down. So a change from anywhere else is handed to the server thread to make.
     */
    @Inject(
        method = {"startWatching(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Z",
            "stopWatching(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Z"},
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void polymer_patcher$onServerThread(ServerGamePacketListenerImpl connection, CallbackInfoReturnable<Boolean> callback) {
        var player = connection == null ? null : connection.getPlayer();
        var server = player == null ? null : player.level().getServer();
        if (server == null || server.isSameThread()) {
            return;
        }
        if (polymer_patcher$reportedOffThread.compareAndSet(false, true)) {
            me.drex.polymerpatcher.PolymerPatcher.LOGGER.warn("A display was shown to or taken from a player off the server thread; moved onto it",
                new IllegalStateException("called from " + Thread.currentThread().getName()));
        }
        ElementHolder holder = (ElementHolder) (Object) this;
        boolean starting = callback.getId().contains("start");
        server.execute(() -> {
            if (starting) {
                holder.startWatching(connection);
            } else {
                holder.stopWatching(connection);
            }
        });
        callback.setReturnValue(false);
    }

    @Inject(
        method = "startWatching(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Z",
        at = @At("RETURN"),
        require = 0
    )
    private void polymer_patcher$noteIfNobodyKnowsThemYet(ServerGamePacketListenerImpl connection,
                                                          CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ()) {
            return;
        }
        var player = connection.getPlayer();
        if (player != null && !NativeClients.settled(player)) {
            HolderRefresh.note((ElementHolder) (Object) this, player);
        }
    }
}
