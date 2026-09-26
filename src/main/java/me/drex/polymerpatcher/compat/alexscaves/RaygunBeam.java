package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.util.ClientParticleBudget;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the Alex's Caves raygun beam for clients which do not have its client renderer.
 *
 * <p>The gun does its damage on the server, but the continuous ray is not a particle or an entity:
 * its renderer reads a client-only hit position written by the mod's client tick. A vanilla client
 * therefore sees the gun work and the impact particles, but no ray between the two. A thin line of
 * vanilla dust is the closest protocol-native equivalent.</p>
 */
public final class RaygunBeam {

    private static final Identifier RAYGUN = Identifier.fromNamespaceAndPath("alexscaves", "raygun");
    private static final DustParticleOptions BEAM = new DustParticleOptions(0x59FF72, 0.72F);
    private static final double MAX_RANGE = 25.0D;
    private static final double SPACING = 0.65D;

    private RaygunBeam() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Dust lasts longer than a tick, so ten updates a second remain continuous while halving
            // the packets made by somebody holding the trigger down.
            if ((server.getTickCount() & 1) != 0) {
                return;
            }
            for (ServerPlayer shooter : server.getPlayerList().getPlayers()) {
                if (isFiring(shooter)) {
                    draw(shooter);
                }
            }
        });
    }

    private static boolean isFiring(ServerPlayer player) {
        if (!player.isUsingItem()) {
            return false;
        }
        ItemStack used = player.getUseItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(used.getItem());
        return RAYGUN.equals(id);
    }

    private static void draw(ServerPlayer shooter) {
        ServerLevel level = shooter.level();
        float warmup = Math.min(shooter.getTicksUsingItem() / 15.0F, 1.0F);
        if (warmup <= 0.05F) {
            return;
        }

        double range = MAX_RANGE * warmup;
        Vec3 look = shooter.getViewVector(1.0F).normalize();
        Vec3 start = shooter.getEyePosition().add(look.scale(0.55D)).add(0.0D, -0.16D, 0.0D);
        HitResult hit = ProjectileUtil.getHitResultOnViewVector(shooter,
            entity -> entity != shooter && !entity.isSpectator() && entity.isPickable(), range);
        Vec3 end = hit.getType() == HitResult.Type.MISS
            ? shooter.getEyePosition().add(look.scale(range))
            : hit.getLocation();

        Vec3 ray = end.subtract(start);
        double length = ray.length();
        if (length < 0.05D) {
            return;
        }

        Vec3 step = ray.scale(SPACING / length);
        int points = Math.min(40, Math.max(1, (int) Math.ceil(length / SPACING)));
        for (ServerPlayer viewer : level.players()) {
            if (NativeClients.carries(viewer, "alexscaves")) {
                continue;
            }
            Vec3 point = start;
            for (int i = 0; i <= points; i++) {
                if (ClientParticleBudget.allow(viewer, level, point.x(), point.y(), point.z())) {
                    level.sendParticles(viewer, BEAM, false, false,
                        point.x(), point.y(), point.z(), 0, 0.0D, 0.0D, 0.0D, 1.0D);
                }
                point = point.add(step);
            }
        }
    }
}
