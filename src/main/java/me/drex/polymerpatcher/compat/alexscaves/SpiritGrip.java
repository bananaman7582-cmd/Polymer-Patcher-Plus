package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Lets the extinction spear's spirits actually carry off a player who does not have the mod.
 * <p>
 * Thrown, the spear raises a dinosaur spirit over whatever it hits, and the flying one - a subterranodon -
 * lifts its catch into the air and lets go. Alex's Caves does that on the server, and the way it does it
 * is not a push: every tick it rises a little and puts its target back underneath it, with
 * {@code setPos(spirit - target height)} and the target's motion set to nothing. A mob goes wherever the
 * server says. A player does not. Where a player is concerned movement is the client's to decide, and a
 * client with the mod runs that same tick for itself and so hangs from the spirit it can see. A client
 * without the mod has neither the spirit's code nor any word from the server, and keeps standing on the
 * ground while the server believes it is ten blocks up.
 * <p>
 * This is the same gap {@link MagneticPull} closes, but the spirit places rather than pulls, so what is
 * passed on is the place: once the mod has put a player under a spirit, the player's client is told to be
 * there too. Only what the mod already did is repeated - nothing here decides who is caught, how high or
 * for how long, so the spirit keeps every rule it has, and lets go exactly when it would have.
 */
public final class SpiritGrip {

    private SpiritGrip() {
    }

    private static final String SPIRIT = "com.github.alexmodguy.alexscaves.server.entity.item.DinosaurSpiritEntity";

    /** How far round a player to look for the spirit holding them. It hangs a body's height overhead. */
    private static final double REACH = 4.0;

    /**
     * How close the player has to be to the spot under the spirit to count as put there this tick. The mod
     * sets the position exactly, so anything further off was not placed by a spirit at all - which is what
     * keeps this from yanking a player the moment a spirit that merely targets them comes near.
     */
    private static final double PUT_THERE = 1.0E-3;

    private static Class<Entity> spiritClass;
    private static Method heldEntity;
    private static boolean looked;
    private static boolean absent;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (absent) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    hold(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not tell {} where a spirit was carrying them", player.getName().getString(), e);
                }
            }
        });
    }

    private static void hold(ServerPlayer player) throws Exception {
        // A client with Alex's Caves hangs itself from the spirit already; telling it again would make the
        // two fight over where the player is
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()
            || NativeClients.carries(player, "alexscaves") || !lookUp()) {
            return;
        }

        for (Entity spirit : player.level().getEntitiesOfClass(spiritClass, player.getBoundingBox().inflate(REACH))) {
            if (heldEntity.invoke(spirit) != player) {
                continue;
            }

            Vec3 under = spirit.position().subtract(0, player.getBbHeight(), 0);
            if (player.position().distanceToSqr(under) > PUT_THERE * PUT_THERE) {
                continue;
            }

            // Absolute place, no motion - the mod zeroes it too, and a client left with any would fall a
            // little between ticks - and the player's own view left alone, so being carried does not wrench
            // the camera round
            player.connection.teleport(new PositionMoveRotation(under, Vec3.ZERO, 0.0F, 0.0F), Relative.ROTATION);
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean lookUp() {
        if (!looked) {
            looked = true;
            try {
                Class<?> type = Class.forName(SPIRIT, false, SpiritGrip.class.getClassLoader());
                spiritClass = (Class<Entity>) type;
                heldEntity = type.getMethod("getAttackingEntity");
                PolymerPatcher.LOGGER.info("Extinction spear spirits will be able to carry players who do not have Alex's Caves");
            } catch (Throwable e) {
                absent = true;
                PolymerPatcher.LOGGER.debug("No {} to ask what it is holding", SPIRIT, e);
            }
        }
        return !absent;
    }
}
