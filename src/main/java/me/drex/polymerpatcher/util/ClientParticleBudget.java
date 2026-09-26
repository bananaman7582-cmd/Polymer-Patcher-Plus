package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.config.ConfigManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps visual compatibility effects from overwhelming one client with tiny particle packets.
 *
 * <p>Most of these effects began life as client-side mod code. Replaying them from the server is the
 * only way an unmodded client can see them, but every individual particle is then a packet. Several
 * magnetic blocks, a raygun and a shield in the same area used to add their traffic without any
 * shared limit. The original effects still run; this only caps what one viewer is asked to receive.</p>
 */
public final class ClientParticleBudget {

    private static final Map<UUID, Allowance> ALLOWANCES = new HashMap<>();

    private ClientParticleBudget() {
    }

    /** Whether one more compatibility particle may be sent to this viewer on this tick. */
    public static boolean allow(ServerPlayer viewer, ServerLevel level, double x, double y, double z) {
        var config = ConfigManager.config().entities;
        double distance = Math.max(8.0D, config.compatibilityParticleDistance);
        if (viewer.level() != level || viewer.distanceToSqr(x, y, z) > distance * distance) {
            return false;
        }

        long tick = level.getGameTime();
        Allowance allowance = ALLOWANCES.computeIfAbsent(viewer.getUUID(), ignored -> new Allowance());
        if (allowance.tick != tick) {
            allowance.tick = tick;
            allowance.used = 0;
        }

        int limit = Math.max(1, config.compatibilityParticlePacketsPerTick);
        if (allowance.used >= limit) {
            return false;
        }
        allowance.used++;
        return true;
    }

    public static void forget(ServerPlayer viewer) {
        ALLOWANCES.remove(viewer.getUUID());
    }

    private static final class Allowance {
        private long tick = Long.MIN_VALUE;
        private int used;
    }
}
