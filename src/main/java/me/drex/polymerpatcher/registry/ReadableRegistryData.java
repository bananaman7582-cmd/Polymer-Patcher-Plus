package me.drex.polymerpatcher.registry;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
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
            if (Registries.ENCHANTMENT.equals(registry)) {
                readable = readableEnchantment(readable, registry.identifier() + " " + entry.id());
            }
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

    /**
     * A strip just for enchantments, where a value, not a key, is the problem.
     * <p>
     * The generic walk above leaves out anything keyed by a mod's name. A mod can also rewrite a vanilla
     * enchantment in place, and then its name shows up inside a value: Subtle adds an {@code is_wet}
     * condition to the effects of the vanilla impaling enchantment, and a client reads a condition's id
     * from a string, not a map key, so the whole enchantment fails to load. The effect entry, not the
     * string, is the unit the game can do without; an entry is trimmed so what the client can read stays.
     */
    private static Tag readableEnchantment(Tag data, String entry) {
        if (!(data instanceof CompoundTag compound)) {
            return data;
        }
        Tag effectsRaw = compound.get("effects");
        Tag effects = rewriteEnchantmentEffects(effectsRaw, entry);
        if (effects == effectsRaw) {
            return data;
        }
        CompoundTag rewritten = compound.copy();
        rewritten.put("effects", effects);
        return rewritten;
    }

    private static Tag rewriteEnchantmentEffects(Tag effectsRaw, String entry) {
        if (!(effectsRaw instanceof CompoundTag effects)) {
            return effectsRaw;
        }
        CompoundTag rewritten = null;
        for (String kind : List.copyOf(effects.keySet())) {
            Tag listRaw = effects.get(kind);
            if (!(listRaw instanceof ListTag entries)) {
                continue;
            }
            ListTag trimmed = new ListTag();
            boolean changed = false;
            for (Tag item : entries) {
                Tag readable = readableEnchantmentEffect(item, entry);
                if (readable == null) {
                    changed = true;
                } else {
                    trimmed.add(readable);
                    changed |= readable != item;
                }
            }
            if (!changed) {
                continue;
            }
            if (rewritten == null) {
                rewritten = effects.copy();
            }
            if (trimmed.isEmpty()) {
                rewritten.remove(kind);
            } else {
                rewritten.put(kind, trimmed);
            }
        }
        return rewritten == null ? effectsRaw : rewritten;
    }

    /** Null where the client cannot read the effect at all; a trimmed copy where part of it will do. */
    private static @Nullable Tag readableEnchantmentEffect(Tag item, String entry) {
        if (!(item instanceof CompoundTag effect)) {
            return item;
        }

        CompoundTag rewritten = null;
        Tag requirements = effect.get("requirements");
        if (requirements != null) {
            Tag readable = readableRequirements(requirements, entry);
            if (readable == null) {
                report(entry + " is gated by a condition only its own mod knows; that check is left out");
                rewritten = effect.copy();
                rewritten.remove("requirements");
            } else if (readable != requirements) {
                rewritten = effect.copy();
                rewritten.put("requirements", readable);
            }
        }

        Tag effectBody = (rewritten != null ? rewritten : effect).get("effect");
        if (effectBody instanceof CompoundTag body) {
            Tag type = body.get("type");
            String name = type == null ? null : type.asString().orElse(null);
            if (name != null && namesAMod(name)) {
                report(entry + " has an effect only its own mod can read; that effect is left out");
                return null;
            }
        }
        return rewritten == null ? item : rewritten;
    }

    /** Null where nothing a client reads remains; otherwise the same or a trimmed copy. */
    private static @Nullable Tag readableRequirements(Tag requirements, String entry) {
        if (!(requirements instanceof CompoundTag compound)) {
            return requirements;
        }
        CompoundTag rewritten = null;
        for (String key : List.copyOf(compound.keySet())) {
            Tag value = compound.get(key);
            if (key.equals("terms") && value instanceof ListTag terms) {
                ListTag kept = new ListTag();
                int dropped = 0;
                for (Tag term : terms) {
                    if (refersToAMod(term)) {
                        dropped++;
                    } else {
                        kept.add(term);
                    }
                }
                if (dropped == 0) {
                    continue;
                }
                report(entry + " is only sometimes gated by a condition its own mod knows; that check is left out");
                if (kept.isEmpty()) {
                    return null;
                }
                rewritten = rewritten == null ? compound.copy() : rewritten;
                rewritten.put(key, kept);
            } else if (refersToAMod(value)) {
                return null;
            }
        }
        return rewritten == null ? compound : rewritten;
    }

    /** Whether this tag, anywhere inside it, names something only a mod can look up. */
    private static boolean refersToAMod(Tag tag) {
        if (tag instanceof StringTag string) {
            String name = string.asString().orElse("");
            if (name.startsWith("#")) {
                name = name.substring(1);
            }
            return namesAMod(name);
        }
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                if (refersToAMod(compound.get(key))) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag item : list) {
                if (refersToAMod(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void report(String what) {
        if (REPORTED.add(what)) {
            PolymerPatcher.LOGGER.info("{}", what);
        }
    }
}
