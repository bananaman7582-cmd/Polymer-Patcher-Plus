package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Lets a magnet actually move someone who does not have the mod.
 * <p>
 * The magnetism itself is not the problem and never was. Alex's Caves works it out on the server -
 * {@code MagnetUtil} lives under {@code server.entity.util} and asks a {@code ServerLevel} which
 * magnets are nearby - so a neodymium pillar knows perfectly well that a player standing near it
 * ought to be pulled, and says so.
 * <p>
 * What it does with the answer is the problem. Where a player is concerned, movement is the client's
 * to decide: the server proposes and the client disposes, and a client that has the mod applies the
 * pull locally while a client that does not simply keeps walking. The server's own idea of where that
 * player should be going is quietly overwritten by the next position the client sends, every tick.
 * <p>
 * So the resulting server motion is sent as velocity instead, which is the one way a server can move
 * a vanilla client and have it stick - the same packet behind every knockback in the game. Alex's
 * Caves has already added the magnetic delta to that motion by this point; adding it here too would
 * double the force and compound it every tick.
 */
public final class MagneticPull {

    private MagneticPull() {
    }

    private static final String MAGNET_UTIL = "com.github.alexmodguy.alexscaves.server.entity.util.MagnetUtil";

    /**
     * Below this the pull is not worth a packet - it would fight the client's own movement for no
     * visible gain and cost a packet per player per tick to do it.
     */
    private static final double WORTH_SENDING = 0.003;

    private static Method delta;
    private static boolean looked;
    private static boolean absent;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (absent) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    push(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not pass on what a magnet was doing to {}", player.getName().getString(), e);
                }
            }
        });
    }

    private static void push(ServerPlayer player) throws Exception {
        // A client carrying Alex's Caves applies this pull locally already. Repeating its reflection
        // and velocity packet is wasted work and can make the two simulations fight each other.
        if (NativeClients.carries(player, "alexscaves")) {
            return;
        }
        Method read = deltaMethod();
        if (read == null) {
            return;
        }

        Object value = read.invoke(null, player);
        if (!(value instanceof Vec3 pull) || pull.lengthSqr() < WORTH_SENDING * WORTH_SENDING) {
            return;
        }

        // MagnetUtil.tickMagnetism has already added this exact pull to deltaMovement on the server.
        // This bridge exists only because a vanilla player's next client-authored movement would
        // otherwise overwrite that result. Send the authoritative result; never apply the force twice.
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    /** The mod's own answer for how hard something is being pulled, or nothing when it is not installed. */
    private static Method deltaMethod() {
        if (!looked) {
            looked = true;
            try {
                Class<?> util = Class.forName(MAGNET_UTIL, false, MagneticPull.class.getClassLoader());
                delta = util.getMethod("getEntityMagneticDelta", net.minecraft.world.entity.Entity.class);
                delta.setAccessible(true);
                PolymerPatcher.LOGGER.info("Magnets will be able to move players who do not have Alex's Caves");
            } catch (Throwable e) {
                absent = true;
                PolymerPatcher.LOGGER.debug("No {} to ask what its magnets are doing", MAGNET_UTIL, e);
            }
        }
        return delta;
    }
}
