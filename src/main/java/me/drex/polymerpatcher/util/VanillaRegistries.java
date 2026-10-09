package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registries every client has, however plain its installation is.
 *
 * <p>A command argument that reads from a registry is written across the network by naming the
 * registry, and the client looks that name up among its own when the tree arrives. The registries
 * Minecraft itself declares are on every client; the ones a mod adds are only on the clients which
 * installed that mod, and a client without it throws at the moment of the lookup - which for that
 * client is the end of the connection before it has joined the world. Spell Engine's
 * {@code minecraft:spell} is exactly this case: named for {@code minecraft}, but present only where
 * Spell Engine is.</p>
 *
 * <p>So the names are read off {@link Registries}, where Minecraft keeps the keys of the game's own
 * registries. Anything absent from that list is treated as belonging to whoever installed the mod,
 * and arguments reading from it are written as something a plain client can read.</p>
 */
public final class VanillaRegistries {

    private VanillaRegistries() {
    }

    /** The names of the registries a client has even with no mods installed. */
    private static final Set<Identifier> UNIVERSAL = collect();

    /** Registries already named in the log, so each one is only ever reported once. */
    private static final Set<Identifier> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Whether a client can look this registry up without having been given it.
     *
     * <p>Answered the same way for every client, because the command tree is written while its
     * packet is encoded, at which point there is no one yet to ask: a client that does have the
     * registry loses the argument's suggestions, which cost it a word of typing, where a client
     * that does not have it would lose the connection.</p>
     */
    public static boolean everyClientHas(ResourceKey<?> registry) {
        if (UNIVERSAL.contains(registry.identifier())) {
            return true;
        }
        if (REPORTED.add(registry.identifier())) {
            PolymerPatcher.LOGGER.info(
                "{} is not a registry every client has, so command arguments reading from it are sent as plain text",
                registry.identifier());
        }
        return false;
    }

    private static Set<Identifier> collect() {
        Set<Identifier> names = new HashSet<>();
        for (Field field : Registries.class.getDeclaredFields()) {
            if (field.getType() != ResourceKey.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(null) instanceof ResourceKey<?> key) {
                    names.add(key.identifier());
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not read {} as a registry name", field, e);
            }
        }
        return Set.copyOf(names);
    }
}
