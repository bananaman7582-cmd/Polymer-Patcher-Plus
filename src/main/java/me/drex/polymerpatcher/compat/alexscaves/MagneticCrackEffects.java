package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.util.ClientParticleBudget;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/** Vanilla-particle reconstruction of Alex's Caves' large magnetic lightning cracks. */
public final class MagneticCrackEffects {
    private static final Identifier MAGNETIC_CAVES =
        Identifier.fromNamespaceAndPath("alexscaves", "magnetic_caves");
    private static final ParticleOptions AZURE =
        new DustColorTransitionOptions(0x27CAFF, 0x4555FF, 1.35F);
    private static final ParticleOptions SCARLET =
        new DustColorTransitionOptions(0xFF214D, 0xFF7134, 1.35F);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private MagneticCrackEffects() {
    }

    /** Recreates the cave's long, alternating red/blue surface-lightning for vanilla clients. */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (NativeClients.carries(player, "alexscaves")
                    || (player.level().getGameTime() + player.getId()) % 12 != 0
                    || player.level().getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> !MAGNETIC_CAVES.equals(key.identifier())).orElse(true)) {
                    continue;
                }
                ServerLevel level = (ServerLevel) player.level();
                RandomSource random = player.getRandom();
                Vec3 start = player.position().add(
                    between(random, -9.0, 9.0), between(random, -4.0, 5.0), between(random, -9.0, 9.0));
                Vec3 direction = randomDirection(random).scale(between(random, 5.0, 11.0));
                drawBolt(level, player, start, start.add(direction), random.nextBoolean(), random, 1.0F);
            }
        });
    }

    /**
     * The original shield emits five to nine bolts from its centre to random points across a five
     * block disc. Drawing the complete radial bolts (rather than dots only at that disc's edge) makes
     * the active volume and polarity readable to clients without Alex's Caves.
     */
    public static void drawShield(ServerLevel level, Vec3 centre, boolean scarlet, RandomSource random) {
        int bolts = 5 + random.nextInt(4);
        for (ServerPlayer viewer : level.players()) {
            if (NativeClients.carries(viewer, "alexscaves")) {
                continue;
            }
            for (int bolt = 0; bolt < bolts; bolt++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double radius = between(random, 3.5D, 5.75D);
                Vec3 end = centre.add(Math.cos(angle) * radius,
                    between(random, -1.15D, 1.15D), Math.sin(angle) * radius);
                drawBolt(level, viewer, centre, end, scarlet, random, 1.35F);
            }
        }
    }

    private static void drawBolt(ServerLevel level, ServerPlayer viewer, Vec3 start, Vec3 end,
                                 boolean scarlet, RandomSource random, float scale) {
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (length < 0.01D) {
            return;
        }
        int points = Math.max(6, Math.min(13, (int) Math.ceil(length * 1.7D)));
        Vec3 normal = delta.normalize().cross(new Vec3(0, 1, 0));
        if (normal.lengthSqr() < 0.01D) {
            normal = new Vec3(1, 0, 0);
        } else {
            normal = normal.normalize();
        }
        Vec3 secondNormal = delta.normalize().cross(normal).normalize();
        ParticleOptions particle = scarlet ? SCARLET : AZURE;

        for (int point = 0; point <= points; point++) {
            double progress = point / (double) points;
            // Endpoints remain anchored. Interior points alternate sides and height to read as one
            // angular lightning crack instead of a smooth dotted ray.
            double envelope = Math.sin(Math.PI * progress);
            double kink = (point % 2 == 0 ? -1.0D : 1.0D)
                * between(random, 0.06D, 0.22D) * envelope * scale;
            double lift = between(random, -0.15D, 0.15D) * envelope * scale;
            Vec3 at = start.add(delta.scale(progress)).add(normal.scale(kink)).add(secondNormal.scale(lift));
            if (!ClientParticleBudget.allow(viewer, level, at.x, at.y, at.z)) {
                break;
            }
            level.sendParticles(viewer, particle, false, false,
                at.x, at.y, at.z, 0, 0.0D, 0.0D, 0.0D, 1.0D);
        }
    }

    private static Vec3 randomDirection(RandomSource random) {
        Vec3 value;
        do {
            value = new Vec3(between(random, -1.0D, 1.0D), between(random, -0.55D, 0.55D),
                between(random, -1.0D, 1.0D));
        } while (value.lengthSqr() < 0.04D);
        return value.normalize();
    }

    private static double between(RandomSource random, double minimum, double maximum) {
        return minimum + random.nextDouble() * (maximum - minimum);
    }
}
