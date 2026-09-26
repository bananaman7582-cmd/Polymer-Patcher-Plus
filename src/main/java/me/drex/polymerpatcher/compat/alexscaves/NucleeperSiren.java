package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Method;

/**
 * Sounds the nucleeper's alarm for people who would otherwise be blown up in silence.
 * <p>
 * A nucleeper blares a siren while it charges, and that siren is not played by the mob - it is a
 * looping sound the mod starts on the client, inside a class only a client ever runs. So a stranger
 * gets no warning whatsoever: the thing walks up, charges, and detonates without a sound, which for a
 * mob whose entire danger is the seconds before it goes off is most of the mob.
 * <p>
 * The mob itself knows perfectly well whether it is charging, and says so through two methods that are
 * public. So the siren is started from here instead, played to everyone nearby as the mod's own sound -
 * which reaches a stranger correctly now that modded sounds are sent by name.
 */
public final class NucleeperSiren {

    private NucleeperSiren() {
    }

    private static final Identifier NUCLEEPER = Identifier.fromNamespaceAndPath("alexscaves", "nucleeper");
    private static final Identifier CHARGE = Identifier.fromNamespaceAndPath("alexscaves", "nucleeper_charge");

    /**
     * The alarm proper, as against the noise the mob itself makes while charging.
     * <p>
     * These are two different sounds and only one of them was being played. A nucleeper sets off the
     * sirens around it, and those blare {@code nuclear_siren} - which the mod starts through
     * {@code client.sound.NuclearSirenSound}, a class that exists only on a client. A stranger got the
     * charge and never the alarm, which is the half that carries any distance.
     */
    private static final Identifier ALARM = Identifier.fromNamespaceAndPath("alexscaves", "nuclear_siren");

    /** How often the charging noise is started again. The mod's own loop is about this long. */
    private static final int EVERY = 40;

    /** The alarm is a long sound, so it is started far less often than the charge. */
    private static final int ALARM_EVERY = 200;

    private static EntityType<?> type;
    private static Holder.Reference<SoundEvent> siren;
    private static Holder.Reference<SoundEvent> alarm;
    private static boolean lookedForAlarm;
    private static Method triggered;
    private static Method stopped;
    private static boolean looked;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EntityType<?> nucleeper = nucleeperType();
            Holder.Reference<SoundEvent> sound = sirenSound();
            Holder.Reference<SoundEvent> alarmSound = alarmSound();

            boolean charge = server.getTickCount() % EVERY == 0;
            boolean wail = server.getTickCount() % ALARM_EVERY == 0;
            if (nucleeper == null || (!charge && !wail)) {
                return;
            }

            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getEntities(nucleeper, Entity::isAlive)) {
                    try {
                        if (!isCharging(entity)) {
                            continue;
                        }
                        // From the mob's own position, so it fades with distance the way the mod's
                        // own loop does
                        if (charge && sound != null) {
                            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                                sound.value(), SoundSource.HOSTILE, 2.0F, 1.0F);
                        }
                        // Carried much further than the charge, because that is what an alarm is for
                        if (wail && alarmSound != null) {
                            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                                alarmSound.value(), SoundSource.HOSTILE, 8.0F, 1.0F);
                        }
                    } catch (Throwable e) {
                        PolymerPatcher.LOGGER.debug("Could not sound a nucleeper's alarm", e);
                    }
                }
            }
        });
    }

    private static boolean isCharging(Entity entity) throws Exception {
        if (!looked) {
            looked = true;
            try {
                triggered = entity.getClass().getMethod("isTriggered");
                stopped = entity.getClass().getMethod("shouldStopBlaringSirens");
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("A nucleeper would not say whether it is charging; its alarm stays silent", e);
            }
        }
        if (triggered == null) {
            return false;
        }

        if (!Boolean.TRUE.equals(triggered.invoke(entity))) {
            return false;
        }
        return stopped == null || !Boolean.TRUE.equals(stopped.invoke(entity));
    }

    private static EntityType<?> nucleeperType() {
        if (type == null) {
            type = BuiltInRegistries.ENTITY_TYPE.getOptional(NUCLEEPER).orElse(null);
        }
        return type;
    }

    /** The alarm, looked up once; absent where the mod does not ship it. */
    private static Holder.Reference<SoundEvent> alarmSound() {
        if (!lookedForAlarm) {
            lookedForAlarm = true;
            alarm = BuiltInRegistries.SOUND_EVENT.get(ALARM).orElse(null);
            if (alarm == null) {
                PolymerPatcher.LOGGER.debug("No {} sound to sound as a nucleeper's alarm", ALARM);
            }
        }
        return alarm;
    }

    private static Holder.Reference<SoundEvent> sirenSound() {
        if (siren == null) {
            siren = BuiltInRegistries.SOUND_EVENT.get(CHARGE).orElse(null);
        }
        return siren;
    }
}
