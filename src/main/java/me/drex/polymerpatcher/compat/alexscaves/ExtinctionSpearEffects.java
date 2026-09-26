package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.util.ClientParticleBudget;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Replays the Extinction Spear's client-only tephra trail for clients without Alex's Caves. */
public final class ExtinctionSpearEffects {

    private static final Identifier EXTINCTION_SPEAR =
        Identifier.fromNamespaceAndPath("alexscaves", "extinction_spear");
    private static final DustColorTransitionOptions EMBER =
        new DustColorTransitionOptions(0xFFB02E, 0xE93116, 0.72F);

    /** Only the loaded matching projectiles are visited; worlds are never scanned for them per tick. */
    private static final Map<ServerLevel, Set<Entity>> LOADED = new IdentityHashMap<>();

    private ExtinctionSpearEffects() {
    }

    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (isExtinctionSpear(entity)) {
                LOADED.computeIfAbsent(level,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entity);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Set<Entity> spears = LOADED.get(level);
            if (spears != null) {
                spears.remove(entity);
                if (spears.isEmpty()) {
                    LOADED.remove(level);
                }
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(ExtinctionSpearEffects::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
    }

    private static void tick(ServerLevel level) {
        Set<Entity> spears = LOADED.get(level);
        if (spears == null || spears.isEmpty()) {
            return;
        }

        spears.removeIf(entity -> entity.isRemoved() || entity.level() != level);
        for (Entity entity : spears) {
            // AbstractArrow keeps its embedded-in-block flag protected. A lodged spear has no motion,
            // while both its outgoing and returning phases do, which is the same visible distinction
            // needed by the trail without another invasive accessor.
            if (entity instanceof AbstractArrow arrow && arrow.getDeltaMovement().lengthSqr() > 1.0E-6D) {
                draw(level, arrow);
            }
        }
    }

    private static void draw(ServerLevel level, AbstractArrow spear) {
        Vec3 at = spear.position().add(0.0D, 0.25D, 0.0D);
        Vec3 trail = spear.getDeltaMovement().scale(-0.20D);
        for (ServerPlayer viewer : level.players()) {
            if (!NativeClients.carries(viewer, "alexscaves")
                && ClientParticleBudget.allow(viewer, level, at.x(), at.y(), at.z())) {
                level.sendParticles(viewer, EMBER, true, false,
                    at.x(), at.y(), at.z(), 0, trail.x(), trail.y(), trail.z(), 1.0D);
            }
        }
    }

    private static boolean isExtinctionSpear(Entity entity) {
        return EXTINCTION_SPEAR.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }
}
