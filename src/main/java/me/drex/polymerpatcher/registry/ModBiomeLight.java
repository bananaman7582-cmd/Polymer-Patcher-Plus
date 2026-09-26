package me.drex.polymerpatcher.registry;

import com.mojang.serialization.Codec;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gives a mod's own cave biomes their light back for a client that does not have the mod.
 * <p>
 * Alex's Caves lights its caves from the client: every frame it asks which biome the camera is in, looks up
 * an amount and a colour of its own, and brightens the light map by them. That is why its caves are a place
 * rather than a black hole with mushrooms in it - and it is client code from top to bottom, so a player
 * without the mod stands in the candy cavity in the dark.
 * <p>
 * The game itself grew the same feature in 26.2. A biome carries an attribute map, {@code AMBIENT_LIGHT_COLOR}
 * is one of the attributes in it, and the Nether uses exactly that to stop being pitch black. It travels in
 * the biome registry that every client is sent at login, which means the light can simply be written into the
 * biome on the way out - no mod, no resource pack, nothing installed. The mod's own numbers are read back out
 * of the mod, so the two agree and stay agreeing when it is updated.
 * <p>
 * Only clients without the mod are touched: those with it are sent nothing for that mod's data and go on
 * lighting their own caves, so nothing is applied twice.
 */
public final class ModBiomeLight {

    private ModBiomeLight() {
    }

    /**
     * The Nether is the measure. Its dimension carries {@code ambient_light: 0.1} from the old days, and the
     * colour written beside it now is {@code #302821} - a grey of 0.19 with a little warmth in it. So one
     * unit of a mod's ambient light is worth about 1.9 of colour, and anything measured the way the Nether
     * was comes out looking the way the Nether does.
     */
    private static final float COLOUR_PER_AMBIENT = 0.188F / 0.1F;

    /** Said once per biome, so a line written for every joining player is written once. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * The same entry, or a copy of it carrying the light its own mod would have drawn.
     */
    public static Tag lit(ResourceKey<? extends Registry<?>> registry, @Nullable RegistryAccess registries,
                          Identifier id, Tag data) {
        if (registries == null || !Registries.BIOME.equals(registry) || !(data instanceof CompoundTag compound)
            || !ConfigManager.config().light.modBiomeAmbientLight) {
            return data;
        }

        Holder<Biome> biome = registries.lookup(Registries.BIOME)
            .flatMap(biomes -> biomes.get(ResourceKey.create(Registries.BIOME, id)))
            .map(reference -> (Holder<Biome>) reference)
            .orElse(null);
        if (biome == null) {
            return data;
        }

        Light light = lightFor(id, biome);
        if (light == null) {
            return data;
        }

        CompoundTag attributes = compound.getCompoundOrEmpty("attributes").copy();
        CompoundTag written = encode(light);
        if (written == null) {
            return data;
        }

        boolean added = false;
        for (String key : written.keySet()) {
            // Whatever the mod said for itself wins; this is only here for what it could not say
            if (attributes.contains(key)) {
                continue;
            }
            Tag value = written.get(key);
            if (value != null) {
                attributes.put(key, value);
                added = true;
            }
        }
        if (!added) {
            return data;
        }

        CompoundTag copy = compound.copy();
        copy.put("attributes", attributes);
        report(id + " is lit by its own mod and not by the game; it is sent with an ambient light of "
            + String.format("#%06X", light.colour() & 0xFFFFFF) + " so it is not a black hole for a client without the mod");
        return copy;
    }

    /**
     * The light one biome should carry, or null where no mod claims it.
     * <p>
     * Worked out once per biome and kept: every joining player is sent every biome, and almost none of them
     * are lit by a mod, so the answer for the other thousand is worth remembering.
     */
    private static @Nullable Light lightFor(Identifier id, Holder<Biome> biome) {
        return KNOWN.computeIfAbsent(id, key -> {
            for (Provider provider : PROVIDERS) {
                Source source = provider.lightFor(biome);
                if (source != null && source.amount() > 0.0F) {
                    float amount = Math.max(source.amount(),
                        (float) ConfigManager.config().light.modBiomeAmbientLightMinimum);
                    float scale = amount * COLOUR_PER_AMBIENT
                        * (float) ConfigManager.config().light.modBiomeAmbientLightScale;
                    return Optional.of(new Light(rgb(
                        scale * (float) source.red(),
                        scale * (float) source.green(),
                        scale * (float) source.blue())));
                }
            }
            return Optional.<Light>empty();
        }).orElse(null);
    }

    private static final Map<Identifier, Optional<Light>> KNOWN = new ConcurrentHashMap<>();

    /** An ambient light, as a colour the game already knows how to use. */
    private record Light(int colour) {
    }

    private static @Nullable CompoundTag encode(Light light) {
        EnvironmentAttributeMap map = EnvironmentAttributeMap.builder()
            .set(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, light.colour())
            .build();

        // Written through the game's own codec rather than by hand, so it is shaped the way the client
        // reading it expects however that shape changes
        Codec<EnvironmentAttributeMap> codec = EnvironmentAttributeMap.NETWORK_CODEC;
        Tag encoded = codec.encodeStart(NbtOps.INSTANCE, map)
            .resultOrPartial(problem -> PolymerPatcher.LOGGER.warn("Could not write an ambient light: {}", problem))
            .orElse(null);
        return encoded instanceof CompoundTag compound ? compound : null;
    }

    /** A mod's native light amount and colour, before the common vanilla conversion. */
    public record Source(float amount, double red, double green, double blue) {
    }

    /** Where a mod keeps the light for its own biomes. */
    @FunctionalInterface
    public interface Provider {
        @Nullable
        Source lightFor(Holder<Biome> biome);
    }

    private static final CopyOnWriteArrayList<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Registers a mod-specific light reader with the global vanilla-biome bridge. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
        KNOWN.clear();
    }

    private static int rgb(float red, float green, float blue) {
        return (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    private static int channel(float value) {
        return Math.clamp(Math.round(value * 255.0F), 0, 255);
    }

    private static void report(String what) {
        if (REPORTED.add(what)) {
            PolymerPatcher.LOGGER.info("{}", what);
        }
    }

    /** Package-private seam for the build-time check; not part of the mod API. */
    static Map<String, String> describeForValidation(float amount, double red, double green, double blue) {
        float scale = amount * COLOUR_PER_AMBIENT;
        return Map.of("colour", String.format("#%06X",
            rgb(scale * (float) red, scale * (float) green, scale * (float) blue)));
    }
}
