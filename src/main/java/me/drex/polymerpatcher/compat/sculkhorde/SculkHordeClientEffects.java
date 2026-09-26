package me.drex.polymerpatcher.compat.sculkhorde;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.ClientParticleReplay;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The parts of Sculk Horde that only a client draws, drawn for the players shown stand-ins instead.
 * <p>
 * Most of the horde's look is particles its own client code adds: the souls a cursor trails as it
 * crawls the ground converting it - which is how the spread is watched at all - the trails behind its
 * projectiles, the Angel of Reaping's glyphs, the sculk enderman's portal specks, the crust a spore
 * spewer or phantom corpse sheds, and the clouds around anything Corroded or in a Diseased Atmosphere.
 * A player without the mod has none of that code, and a player shown a stand-in has no real mob for it
 * to run on. Each is run here instead with {@link ClientParticleReplay}: the mod's own method where it
 * has one, and the handful of lines copied out where the particles sit inside a method that also does
 * the mob's real thinking.
 * <p>
 * Nothing here is compiled against Sculk Horde; everything is found by name, and a class or method that
 * has moved costs that one effect and nothing else.
 */
public final class SculkHordeClientEffects {

    private SculkHordeClientEffects() {
    }

    private static final String MOD = "sculkhorde";
    private static final String ENTITY = "com.github.sculkhorde.common.entity.";

    private static final ClassValue<Consumer<Entity>> RULES = new ClassValue<>() {
        @Override
        protected Consumer<Entity> computeValue(Class<?> type) {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                Consumer<Entity> rule = ruleFor(current);
                if (rule != null) {
                    return rule;
                }
            }
            return null;
        }
    };

    private static final Set<Entity> WATCHED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Class<?>> BROKEN = Collections.newSetFromMap(new IdentityHashMap<>());

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded(MOD)) {
            return;
        }

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (RULES.get(entity.getClass()) != null) {
                WATCHED.add(entity);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> WATCHED.remove(entity));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (WATCHED.isEmpty()) {
                return;
            }
            for (Entity entity : new ArrayList<>(WATCHED)) {
                if (entity.isRemoved()) {
                    WATCHED.remove(entity);
                    continue;
                }
                Consumer<Entity> rule = RULES.get(entity.getClass());
                if (rule == null || BROKEN.contains(entity.getClass()) || !(entity.level() instanceof ServerLevel level)) {
                    continue;
                }
                List<ServerPlayer> to = ClientParticleReplay.shownStandInFor(entity, MOD);
                if (!ClientParticleReplay.run(level, to, () -> rule.accept(entity))) {
                    BROKEN.add(entity.getClass());
                    PolymerPatcher.LOGGER.warn("Could not draw {}'s particles for players without Sculk Horde; it will not be tried again",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
                }
            }
        });

        // Corroded and Diseased Atmosphere draw their clouds from the client's own effect tick
        Class<?> clientTickEffect = type("com.github.sculkhorde.common.effect.IClientTickEffect");
        Method clientEffectTick = method(clientTickEffect, "clientEffectTick", LivingEntity.class, int.class);
        if (clientEffectTick != null) {
            ClientParticleReplay.registerEffectDrawing(clientTickEffect, (entity, instance) ->
                invoke(clientEffectTick, instance.getEffect().value(), entity, instance.getAmplifier()));
        }

        PolymerPatcher.LOGGER.info("Sculk Horde's client-side particles will be drawn for players without it");
    }

    @Nullable
    private static Consumer<Entity> ruleFor(Class<?> type) {
        return switch (type.getName()) {
            // Every kind of cursor: its own method, once a tick from the moment it is seen
            case ENTITY + "infection.CursorEntity" -> call(type, "spawnParticleEffects");
            case ENTITY + "infection.CursorBridgerEntity" -> entity -> {
                for (int i = 0; i < 2; i++) {
                    add(entity, ParticleTypes.LARGE_SMOKE, entity.getRandomX(0.5D), entity.getRandomY(), entity.getRandomZ(0.5D), 0, 0, 0);
                }
            };
            case ENTITY + "projectile.AbstractProjectileEntity" -> call(type, "trailParticles");
            case ENTITY + "boss.angel_of_reaping.ElementalFireMagicCircleAttackEntity" ->
                call(type, "spawnPartilcesRandomlyInHitboxClientSide");
            case ENTITY + "boss.angel_of_reaping.AngelOfReapingEntity" -> {
                ParticleOptions glyph = particle("ancient_dialect_particle");
                yield entity -> {
                    if (!entity.getRandom().nextBoolean()) {
                        return;
                    }
                    if (glyph != null) {
                        add(entity, glyph, entity.getRandomX(3D), entity.getRandomY() - 0.25D, entity.getRandomZ(3D), 0, 0, 0);
                    }
                    add(entity, ParticleTypes.SCULK_SOUL, entity.getRandomX(1D), entity.getRandomY() - 0.25D, entity.getRandomZ(1D), 0, 0, 0);
                };
            }
            case ENTITY + "boss.sculk_enderman.SculkEndermanEntity" -> entity -> {
                RandomSource random = entity.getRandom();
                for (int i = 0; i < 2; i++) {
                    add(entity, ParticleTypes.PORTAL, entity.getRandomX(0.5D), entity.getRandomY() - 0.25D, entity.getRandomZ(0.5D),
                        (random.nextDouble() - 0.5D) * 2.0D, -random.nextDouble(), (random.nextDouble() - 0.5D) * 2.0D);
                }
            };
            case ENTITY + "SculkPhantomCorpseEntity" -> {
                ParticleOptions crust = particle("sculk_crust_particle");
                yield crust == null ? null : entity -> {
                    RandomSource random = entity.getRandom();
                    add(entity, crust, entity.getX() + random.nextFloat() * 0.5F, entity.getY() + random.nextFloat() * 0.5F,
                        entity.getZ() + random.nextFloat() * 0.5F, (random.nextDouble() - 0.5) * 3,
                        (random.nextDouble() - 0.5) * 3, (random.nextDouble() - 0.5) * 3);
                };
            }
            case ENTITY + "SculkSporeSpewerEntity" -> {
                ParticleOptions crust = particle("sculk_crust_particle");
                yield crust == null ? null : entity -> {
                    RandomSource random = entity.getRandom();
                    add(entity, crust, entity.getX(), entity.getY() + 1.7, entity.getZ(), (random.nextDouble() - 0.5) * 3,
                        (random.nextDouble() - 0.5) * 3, (random.nextDouble() - 0.5) * 3);
                };
            }
            case ENTITY + "projectile.SculkAcidicProjectileEntity" -> {
                Method particle = method(type, "getParticle");
                yield particle == null ? null : entity -> {
                    if (invoke(particle, entity) instanceof ParticleOptions options) {
                        add(entity, options, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0);
                    }
                };
            }
            case ENTITY + "projectile.PurificationFlaskProjectileEntity" -> entity ->
                add(entity, ParticleTypes.COMPOSTER, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0);
            default -> null;
        };
    }

    private static void add(Entity entity, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
        entity.level().addParticle(particle, x, y, z, dx, dy, dz);
    }

    @Nullable
    private static Consumer<Entity> call(Class<?> type, String name) {
        Method method = method(type, name);
        return method == null ? null : entity -> invoke(method, entity);
    }

    @Nullable
    private static ParticleOptions particle(String path) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.fromNamespaceAndPath(MOD, path));
        return type instanceof ParticleOptions options ? options : null;
    }

    @Nullable
    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, SculkHordeClientEffects.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private static Method method(@Nullable Class<?> type, String name, Class<?>... parameters) {
        if (type == null) {
            return null;
        }
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Sculk Horde's {}.{} was not found", type.getName(), name);
            return null;
        }
    }

    @Nullable
    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
