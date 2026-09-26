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
