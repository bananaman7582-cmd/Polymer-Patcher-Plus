package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the ground around the cross before anybody sets out for it.
 * <p>
 * An explorer map is blank until you walk the ground it covers, because the game only ever draws the
 * part you are standing on. That is fine for a map to a shipwreck a few hundred blocks away and much
 * less fine for one to a cave biome, which is far enough off that the map is a white sheet with a cross
 * at the edge and nothing to navigate by.
 * <p>
 * Drawing it needs the chunks, and the chunks are not loaded - nobody has ever been there. Asking for
 * them the ordinary way would generate a thousand blocks square of world with the server held still
 * while it happened, which is not a trade worth making for a picture.
 * <p>
 * So they are asked for the way the game asks for anything far away: a ticket, a future, and the work
 * done whenever it finishes. The server carries on throughout. When the chunks arrive the map is drawn
 * once, centred on the cross, and the ticket is left to expire so they unload again on their own.
 * <p>
 * What that gives is an island of real ground around the cross with white all around it - which is what
 * a treasure map has always looked like, and enough to tell a cave mouth from a hillside before walking
 * an hour to it.
 */
public final class CaveMapPrefill {

    private CaveMapPrefill() {
    }

    /**
     * How far out the chunks are fetched, in chunks.
     * <p>
     * One pass of the game's own map drawing covers about two hundred and fifty blocks square at this
     * scale, so there is nothing to be gained by fetching more than that - it would be generating world
     * that the drawing never reads. Nine chunks each way is a little over the edge of what gets drawn.
     */
    private static final int RADIUS = 9;

    /**
     * What holds the chunks while they are fetched.
     * <p>
     * The game refuses to fetch chunks in the background on a ticket that can run out before they arrive,
     * which is every ticket it has for passing use - so this one never runs out, and is handed back by
     * hand once the picture is taken. Its flags are a combination no ticket of the game's own uses, because
     * tickets are compared by value and handing this one back must never hand back somebody else's.
     */
    private static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT,
        TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    /** Maps already drawn, so a second copy of the same map does not fetch it all again. */
    private static final Set<MapId> DRAWN = ConcurrentHashMap.newKeySet();

    /**
     * Starts drawing the ground around a freshly made cave map. Returns at once; the work happens as
     * the chunks arrive.
     */
    public static void begin(ServerLevel level, MapId mapId, BlockPos target, ServerPlayer forPlayer) {
        if (!DRAWN.add(mapId)) {
            return;
        }

        var server = level.getServer();
        // Scheduled rather than run here: this is reached while a packet is being written, which is not
        // necessarily the server thread, and chunks are the server thread's business alone
        server.execute(() -> {
            long started = System.nanoTime();
            ChunkPos centre = new ChunkPos(target.getX() >> 4, target.getZ() >> 4);

            try {
                level.getChunkSource()
                    .addTicketAndLoadWithRadius(TICKET, centre, RADIUS)
                    .thenRun(() -> server.execute(() -> {
                        try {
                            draw(level, mapId, target, forPlayer, started);
                        } finally {
                            // This ticket never runs out by itself, so it is handed back as soon as the
                            // picture is taken and the chunks unload again on their own
                            level.getChunkSource().removeTicketWithRadius(TICKET, centre, RADIUS);
                        }
                    }))
                    .exceptionally(failure -> {
                        PolymerPatcher.LOGGER.debug("Could not fetch the ground around {} for a cave map", target, failure);
                        DRAWN.remove(mapId);
                        server.execute(() -> level.getChunkSource().removeTicketWithRadius(TICKET, centre, RADIUS));
                        return null;
                    });
            } catch (Throwable e) {
                DRAWN.remove(mapId);
                PolymerPatcher.LOGGER.info("Cave maps will fill in as you travel rather than up front: {}", e.toString());
            }
        });
    }

    private static void draw(ServerLevel level, MapId mapId, BlockPos target, ServerPlayer forPlayer, long started) {
        MapItemSavedData saved = level.getMapData(mapId);
        if (saved == null || saved.locked) {
            return;
        }

        try {
            // The game draws around whoever is holding the map, and there is nobody standing on a cave
            // biome nobody has been to. So something is stood there that is not in the world and never
            // will be - it is read for its position and then dropped
            ItemEntity where = new ItemEntity(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, ItemStack.EMPTY);
            ((MapItem) Items.FILLED_MAP).update(level, where, saved);
            where.discard();

            PolymerPatcher.LOGGER.info("Drew the ground around {} onto a cave map in {} ms",
                target, (System.nanoTime() - started) / 1_000_000);

            if (forPlayer != null && forPlayer.isAlive()) {
                forPlayer.sendSystemMessage(Component.literal("Your cave map has finished drawing."), true);
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not draw the ground onto the cave map for {}", target, e);
        }
    }
}
