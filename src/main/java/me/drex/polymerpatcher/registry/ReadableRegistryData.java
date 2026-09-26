package me.drex.polymerpatcher.registry;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a mod's biomes, and the rest of what the game sends as data, readable by a client without that mod.
 * <p>
 * The game hands a joining client every piece of world data it cannot already have. A client that has the mod
 * says so when it is asked which data packs it knows, and is sent nothing for it - so this only ever concerns
 * somebody who does not have the mod, and for them the mod's biomes arrive written out in full.
 * <p>
 * Most of them are readable: a biome is mostly colours and numbers, and Alex's Caves' biomes went through
 * without trouble for weeks. Some are not. Wilder Wild's tundra says its grass is coloured
 * {@code wilderwild_tundra}, which is a name only Wilder Wild's own code knows, and its day timeline is keyed
 * by track names of its own. A name the client cannot place is not a missing colour - it is a failure to read
 * the message at all, and the client is dropped at the loading screen with a registry error. After Wilder Wild
 * was added to this server nobody could get in, whatever mods they had.
 * <p>
 * So two things are put right on the way out. A name in a fixed list of choices that is not one of the choices
 * becomes the ordinary one, and an entry keyed by a mod's own name - which the client has no way to look up -
 * is left out. Both are the same trade this mod makes everywhere else: the part that cannot travel is dropped
 * so that everything around it arrives.
 */
public final class ReadableRegistryData {

    private ReadableRegistryData() {
    }

    /** Fields the game reads as one of a fixed set of names, and the name to fall back to. */
    private static final Map<String, Choice> FIXED_CHOICES = Map.of(
        "grass_color_modifier", new Choice(Set.of("none", "dark_forest", "swamp"), "none"),
        "temperature_modifier", new Choice(Set.of("none", "frozen"), "none")
    );

    private record Choice(Set<String> known, String fallback) {
    }

    /** Said once per thing changed, so a change made for every joining player is mentioned once. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * The same entries, with anything a client without the mod could not read taken out of them.
     */
    public static List<RegistrySynchronization.PackedRegistryEntry> readable(
        ResourceKey<? extends Registry<?>> registry,
        @Nullable net.minecraft.core.RegistryAccess registries,
        List<RegistrySynchronization.PackedRegistryEntry> entries
    ) {
        List<RegistrySynchronization.PackedRegistryEntry> changed = null;

        for (int i = 0; i < entries.size(); i++) {
            RegistrySynchronization.PackedRegistryEntry entry = entries.get(i);
            Tag data = entry.data().orElse(null);
            if (data == null) {
                continue;
            }

            Tag readable = readable(data, registry.identifier() + " " + entry.id());
            // And what the mod draws for itself and the client cannot: a cave biome lit from a mod's own
            // renderer is pitch black without it, and the game has somewhere to put that light now
            readable = ModBiomeLight.lit(registry, registries, entry.id(), readable);
            if (readable != data) {
                if (changed == null) {
                    changed = new ArrayList<>(entries);
                }
                changed.set(i, new RegistrySynchronization.PackedRegistryEntry(entry.id(), Optional.of(readable)));
            }
        }

        return changed == null ? entries : changed;
    }

    /** The same tag, or a new one where something in it had to go. */
    private static Tag readable(Tag tag, String entry) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag rewritten = null;

            for (String key : List.copyOf(compound.keySet())) {
                Tag value = compound.get(key);
                if (value == null) {
                    continue;
                }

                // A key that is a mod's own name is a lookup into something the client does not have
                if (namesAMod(key)) {
                    rewritten = rewritten == null ? compound.copy() : rewritten;
                    rewritten.remove(key);
                    report(entry + " is keyed by " + key + ", which only that mod can look up; that part is left out");
                    continue;
                }

                Tag replacement = choiceFor(key, value, entry);
                if (replacement == null) {
                    replacement = readable(value, entry);
                }
                if (replacement != value) {
                    rewritten = rewritten == null ? compound.copy() : rewritten;
                    rewritten.put(key, replacement);
                }
            }

            return rewritten == null ? compound : rewritten;
        }

        if (tag instanceof ListTag list) {
            ListTag rewritten = null;
            for (int i = 0; i < list.size(); i++) {
                Tag value = list.get(i);
                Tag readable = readable(value, entry);
                if (readable != value) {
                    if (rewritten == null) {
                        rewritten = list.copy();
                    }
                    rewritten.set(i, readable);
                }
            }
            return rewritten == null ? list : rewritten;
        }

        return tag;
    }

    /**
     * The ordinary name to use where this field only accepts a fixed set of them and has been given another,
     * or null where there is nothing to change.
     */
    private static @Nullable Tag choiceFor(String key, Tag value, String entry) {
        Choice choice = FIXED_CHOICES.get(key);
        if (choice == null) {
            return null;
        }

        String name = value.asString().orElse(null);
        if (name == null || choice.known().contains(name)) {
            return null;
        }

        report(entry + " asks for " + key + " " + name + ", which only its own mod knows; it is sent as " + choice.fallback());
        return StringTag.valueOf(choice.fallback());
    }

    /** Whether this name belongs to a mod rather than to the game. */
    private static boolean namesAMod(String key) {
        int colon = key.indexOf(':');
        return colon > 0 && !key.startsWith(Identifier.DEFAULT_NAMESPACE + ":");
    }

    private static void report(String what) {
        if (REPORTED.add(what)) {
            PolymerPatcher.LOGGER.info("{}", what);
        }
    }
}
