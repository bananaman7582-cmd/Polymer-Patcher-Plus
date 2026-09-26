package me.drex.polymerpatcher.util;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a player what they were sent before anyone knew who they were.
 * <p>
 * A client says what mods it carries a second or three after it arrives, and everything around its
 * spawn is sent the instant it does. In that gap the numbering a display needs cannot be worked out -
 * a vanilla client and a modded one want different numbers for the same display, and guessing wrong
 * disconnects whichever one guessed against. So during the gap only the fields every entity shares
 * are sent, which are true for both and enough for neither: the display arrives with no item on it,
 * and a modded block is simply not there.
 * <p>
 * Left alone it would stay not there. What a display holds is only sent again when it changes, and a
 * block that is merely sitting in the world never changes - so the missing ones would wait for the
 * player to walk out of range and back, which is a strange thing to have to know.
 * <p>
 * So the displays caught in the gap are remembered, and the moment the client does say what it has,
 * each is shown to them again from scratch - with the numbering that was impossible to choose a
 * moment earlier.
 */
public final class HolderRefresh {

    private HolderRefresh() {
    }

    /** Displays sent to a player before anything was known about them. */
    private static final Map<UUID, Set<ElementHolder>> CAUGHT_IN_THE_GAP = new ConcurrentHashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (CAUGHT_IN_THE_GAP.isEmpty()) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (NativeClients.settled(player)) {
                    replay(player);
                }
            }
        });
    }

    /**
     * Remembers a display that a player was shown before their client had spoken.
     */
    public static void note(ElementHolder holder, ServerPlayer player) {
        CAUGHT_IN_THE_GAP
            .computeIfAbsent(player.getUUID(), key -> Collections.newSetFromMap(new IdentityHashMap<>()))
            .add(holder);
    }

    /** Nothing to replay for somebody who has gone. */
    public static void forget(ServerPlayer player) {
        CAUGHT_IN_THE_GAP.remove(player.getUUID());
    }

    private static void replay(ServerPlayer player) {
        Set<ElementHolder> waiting = CAUGHT_IN_THE_GAP.remove(player.getUUID());
        if (waiting == null || waiting.isEmpty()) {
            return;
        }

        int shown = 0;
        // Copied first: stopping and starting a holder can change what is watching what, and walking
        // the set while that happens is asking for trouble
        for (ElementHolder holder : Set.copyOf(waiting)) {
            try {
                // Sent from scratch rather than nudged, because what they were given the first time was
                // not a stale version of the display but a fraction of one
                if (holder.stopWatching(player)) {
                    holder.startWatching(player);
                    shown++;
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not show {} a display again", player.getName().getString(), e);
            }
        }

        if (shown > 0) {
            PolymerPatcher.LOGGER.debug("Showed {} {} display(s) again, now that their client has said what it has",
                player.getName().getString(), shown);
        }
    }
}
