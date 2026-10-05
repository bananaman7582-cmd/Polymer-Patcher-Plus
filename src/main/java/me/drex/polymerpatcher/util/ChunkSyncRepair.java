package me.drex.polymerpatcher.util;

import eu.pb4.polymer.core.impl.interfaces.PolymerChunkStorage;
import eu.pb4.polymer.core.impl.networking.PolymerServerProtocol;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.VirtualEntityUtils;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Does for a player what Polymer does the moment a chunk is sent to them, for chunks that were sent some
 * other way.
 * <p>
 * Polymer hangs two things off vanilla sending a chunk. Every display standing in the chunk - a modded block
 * that ran out of carriers, a block drawn by a renderer - starts being shown to the player, and a player with
 * Polymer on their client is told which real block sits behind each carrier, which is what Jade reads. Both
 * are hooked into {@code PlayerChunkSender.sendChunk} and into the chunk packet going out on its own.
 * <p>
 * A portal mod built on Immersive Portals - Fancy Portals is one - sends chunks itself, so neither hook ever
 * runs. Polymer 0.17.5's support for that is commented out. What a player saw was every display-drawn block
 * missing (Polished Endstone Stairs once their carriers ran out) and Jade naming the carrier, until a hit on
 * the block sent it on its own and Polymer's single-block path told the client the truth.
 * <p>
 * So this walks the chunks each player is tracking, by the same test Polymer uses to stop showing them, and
 * catches up on whatever was missed. Starting to show a display is a no-op when it is already shown, and goes
 * through the same checks as ever (a companion client drawing the block itself still declines it). The block
 * list for Jade is only sent for chunks Polymer did not send it for itself, after a delay, because a client
 * throws it away if the chunk has not reached it yet.
 */
public final class ChunkSyncRepair {
    private ChunkSyncRepair() {
    }

    /** How often each player's chunks are looked over. Displays missed are at most this late. */
    private static final int INTERVAL_TICKS = 20;

    /**
     * When the real-block list is sent for a chunk Polymer did not cover, counted from the pass that first
     * found it tracked. More than once, because how long a chunk takes to reach the client is up to whoever
     * sent it, and an early copy is simply dropped; a late copy changes nothing.
     */
    private static final int[] REAL_BLOCKS_AFTER_TICKS = {40, 200};

    private static final Map<UUID, Seen> SEEN = new ConcurrentHashMap<>();

    private static volatile boolean reportedDisplays;
    private static volatile boolean reportedRealBlocks;

    private static final class Seen {
        ServerLevel level;
        /** Per tracked chunk: the tick it was first found tracked, and how many real-block lists went out. */
        final Long2ObjectOpenHashMap<int[]> tracked = new Long2ObjectOpenHashMap<>();
        /** Chunks Polymer sent the real-block list for itself. Written from wherever the chunk packet is sent. */
        final Set<Long> coveredByPolymer = ConcurrentHashMap.newKeySet();
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ChunkSyncRepair::tick);
    }

    public static void forget(ServerPlayer player) {
        SEEN.remove(player.getUUID());
    }

    /** Polymer handled this chunk being sent to this player itself; nothing to catch up on for it. */
    public static void sentByPolymer(ServerGamePacketListenerImpl connection, LevelChunk chunk) {
        ServerPlayer player = connection.getPlayer();
        if (player == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        SEEN.computeIfAbsent(player.getUUID(), id -> new Seen()).coveredByPolymer.add(key(pos.x(), pos.z()));
    }

    private static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % INTERVAL_TICKS != 0 || !ConfigManager.config().blocks.repairChunkSync) {
            return;
        }
        int radius = server.getPlayerList().getViewDistance() + 1;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                repair(player, now, radius);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not catch up on the chunks sent to {}", player.getGameProfile().name(), e);
            }
        }
    }

    private static void repair(ServerPlayer player, int now, int radius) {
        ServerGamePacketListenerImpl connection = player.connection;
        if (connection == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        Seen seen = SEEN.computeIfAbsent(player.getUUID(), id -> new Seen());
        if (seen.level != level) {
            // Not on the first pass: what Polymer covered while the player was joining still counts
            if (seen.level != null) {
                seen.coveredByPolymer.clear();
            }
            seen.level = level;
            seen.tracked.clear();
        }

        ChunkPos centre = player.chunkPosition();
        LongOpenHashSet stillTracked = new LongOpenHashSet();
        int displays = 0;
        int realBlocks = 0;
        for (int x = centre.x() - radius; x <= centre.x() + radius; x++) {
            for (int z = centre.z() - radius; z <= centre.z() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !VirtualEntityUtils.isPlayerTracking(player, chunk)) {
                    continue;
                }
                long key = key(x, z);
                stillTracked.add(key);
                displays += showDisplays(connection, chunk);
                if (sendRealBlocks(connection, chunk, key, seen, now)) {
                    realBlocks++;
                }
            }
        }

        // A chunk that left and comes back is a new send, which Polymer may or may not cover this time
        seen.tracked.keySet().retainAll(stillTracked);
        seen.coveredByPolymer.retainAll(stillTracked);

        if (displays > 0 && !reportedDisplays) {
            reportedDisplays = true;
            PolymerPatcher.LOGGER.info("Showed {} {} block display(s) that were never shown when their chunks were sent. "
                    + "Another mod is sending chunks itself (Fancy Portals and Immersive Portals do), so Polymer's own hook "
                    + "did not run; these are now caught up every second. Said once.",
                player.getGameProfile().name(), displays);
        }
        if (realBlocks > 0 && !reportedRealBlocks) {
            reportedRealBlocks = true;
            PolymerPatcher.LOGGER.info("Sent {} the real blocks behind the carriers in {} chunk(s) Polymer did not cover, "
                    + "so client-side mods such as Jade name them without the block being hit first. Said once.",
                player.getGameProfile().name(), realBlocks);
        }
    }

    /** Starts showing this player every display in the chunk they should already be seeing. */
    private static int showDisplays(ServerGamePacketListenerImpl connection, LevelChunk chunk) {
        var attachments = ((HolderAttachmentHolder) chunk).polymerVE$getHolders();
        if (attachments.isEmpty()) {
            return 0;
        }
        int shown = 0;
        // Copied, because starting to show a holder can create or move others
        for (HolderAttachment attachment : List.copyOf(attachments)) {
            if (attachment.isRemoved()) {
                continue;
            }
            ElementHolder holder = attachment.holder();
            if (holder == null || holder.getAttachment() != attachment || holder.getWatchingPlayers().contains(connection)) {
                continue;
            }
            attachment.startWatching(connection);
            if (holder.getWatchingPlayers().contains(connection)) {
                shown++;
            }
        }
        return shown;
    }

    /** True when a real-block list went out for this chunk on this pass. */
    private static boolean sendRealBlocks(ServerGamePacketListenerImpl connection, LevelChunk chunk, long key, Seen seen, int now) {
        if (seen.coveredByPolymer.contains(key) || !((PolymerChunkStorage) chunk).polymer$hasAny()) {
            return false;
        }
        int[] state = seen.tracked.get(key);
        if (state == null) {
            seen.tracked.put(key, new int[]{now, 0});
            return false;
        }
        int sent = state[1];
        if (sent >= REAL_BLOCKS_AFTER_TICKS.length || now - state[0] < REAL_BLOCKS_AFTER_TICKS[sent]) {
            return false;
        }
        state[1] = sent + 1;
        // Says nothing to a client without Polymer: it checks what the client can receive itself
        PolymerServerProtocol.sendSectionUpdate(connection, chunk);
        return true;
    }

    private static long key(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }
}
