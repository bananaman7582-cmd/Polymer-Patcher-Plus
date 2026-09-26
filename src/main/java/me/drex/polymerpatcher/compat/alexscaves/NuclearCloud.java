package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Builds Alex's Caves' mushroom cloud out of particles, for people who cannot be shown the real one.
 * <p>
 * The explosion itself is an entity, and its renderer draws nothing at all - the cloud is built
 * entirely in the mod's own drawing code, from shapes and shaders a server has no way to send. What a
 * stranger sees of a nuclear detonation is therefore the damage and nothing else: the world is flattened
 * in silence, with no cloud over it.
 * <p>
 * The shape is simple enough to rebuild out of the particles every client already has. A stem rising
 * from the ground, a cap rolling outwards at the top of it, and a ring of debris thrown along the
 * ground - all scaled by how big the blast is and how far through it has got. It is not the mod's
 * cloud, but it is unmistakably a mushroom cloud, which is the thing that was missing.
 */
public final class NuclearCloud {

    private NuclearCloud() {
    }

    private static final Identifier EXPLOSION = Identifier.fromNamespaceAndPath("alexscaves", "nuclear_explosion");

    /** How long the cloud is drawn for. The entity itself lives longer while it works out damage. */
    private static final int SHOWN_FOR = 220;

    private static EntityType<?> type;
    private static Method size;
    private static boolean looked;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EntityType<?> explosion = explosionType();
            if (explosion == null) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getEntities(explosion, found -> found.tickCount <= SHOWN_FOR)) {
                    try {
                        draw(level, entity);
                    } catch (Throwable e) {
                        PolymerPatcher.LOGGER.debug("Could not draw a cloud over the explosion at {}", entity.position(), e);
                    }
                }
            }
        });
    }

    private static void draw(ServerLevel level, Entity entity) {
        int age = entity.tickCount;
        float blast = sizeOf(entity);
        Vec3 at = entity.position();

        // Everything is drawn from how far through the blast is, so the cloud climbs and spreads rather
        // than appearing whole
        float through = Mth.clamp(age / (float) SHOWN_FOR, 0.0F, 1.0F);
        float rise = Mth.lerp(Math.min(through * 2.0F, 1.0F), 0.0F, blast * 1.6F);
        float spread = Mth.lerp(through, blast * 0.15F, blast * 0.75F);

        // The stem: a twisting column of smoke from the ground up to the cap
        for (int step = 0; step < 12; step++) {
            double height = rise * (step / 12.0);
            double sway = Math.sin(age * 0.06 + step) * blast * 0.05;
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                at.x + sway, at.y + height, at.z + sway,
                2, blast * 0.08, 0.1, blast * 0.08, 0.01);
        }

        // The cap: a ring rolling outwards at the top, which is what makes it a mushroom rather than a
        // column of smoke
        int around = 26;
        for (int step = 0; step < around; step++) {
            double angle = (step / (double) around) * Math.PI * 2 + age * 0.01;
            double capX = at.x + Math.cos(angle) * spread;
            double capZ = at.z + Math.sin(angle) * spread;
            double capY = at.y + rise + Math.sin(through * Math.PI) * blast * 0.1;

            level.sendParticles(ParticleTypes.LARGE_SMOKE, capX, capY, capZ, 1, 0.2, 0.2, 0.2, 0.02);
            if (step % 3 == 0) {
                level.sendParticles(ParticleTypes.EXPLOSION, capX, capY, capZ, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // The first moments only: the flash at the heart of it, and the debris thrown outwards
        if (age < 40) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + blast * 0.2, at.z,
                2, blast * 0.2, blast * 0.2, blast * 0.2, 0.0);

            int ring = 20;
            for (int step = 0; step < ring; step++) {
                double angle = (step / (double) ring) * Math.PI * 2;
                double reach = blast * (0.3 + age * 0.02);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    at.x + Math.cos(angle) * reach, at.y + 0.5, at.z + Math.sin(angle) * reach,
                    1, 0.1, 0.05, 0.1, 0.01);
            }
        }
    }

    /** How big the blast is, asked of the entity, or a sensible size when it will not say. */
    private static float sizeOf(Entity entity) {
        if (!looked) {
            looked = true;
            try {
                size = entity.getClass().getMethod("getSize");
                size.setAccessible(true);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("A nuclear explosion would not say how big it is; clouds will be a standard size", e);
            }
        }

        if (size != null) {
            try {
                float asked = ((Number) size.invoke(entity)).floatValue();
                if (asked > 0) {
                    return Math.min(asked, 60.0F);
                }
            } catch (Throwable ignored) {
            }
        }
        return 20.0F;
    }

    private static EntityType<?> explosionType() {
        if (type == null) {
            type = BuiltInRegistries.ENTITY_TYPE.getOptional(EXPLOSION).orElse(null);
        }
        return type;
    }
}
