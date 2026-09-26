package me.drex.polymerpatcher.registry;

import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Standalone check for {@link ReadableRegistryData}, built from the two entries that actually shut this
 * server's door: Wilder Wild's tundra biome and its day timeline, exactly as they were reported in the
 * client's registry error.
 *
 * <p>What is checked is both halves of the bargain: the parts a client cannot read are gone, and nothing
 * else moved. An entry with nothing wrong with it must come back as the very same object, because every
 * joining player pays for a copy.</p>
 */
public final class ReadableRegistryDataCheck {

    private ReadableRegistryDataCheck() {
    }

    public static void main(String[] args) {
        checkBiome();
        checkTimeline();
        checkVanillaUntouched();
        checkNestedInsideLists();
        System.out.println("Verified registry data is left readable by a client without the mod");
    }

    /** Wilder Wild's tundra: a grass colour named by the mod, inside an otherwise ordinary biome. */
    private static void checkBiome() {
        CompoundTag effects = new CompoundTag();
        effects.putString("grass_color_modifier", "wilderwild_tundra");
        effects.putInt("water_color", 4159204);

        CompoundTag biome = new CompoundTag();
        biome.putFloat("temperature", 0.25F);
        biome.putString("temperature_modifier", "frozen");
        biome.put("effects", effects);

        CompoundTag readable = (CompoundTag) only(Registries.BIOME, id("wilderwild", "tundra"), biome);
        CompoundTag readableEffects = readable.getCompound("effects").orElseThrow();

        expect("none".equals(readableEffects.getString("grass_color_modifier").orElse(null)),
            "an unknown grass colour is sent as the ordinary one");
        expect(readableEffects.getInt("water_color").orElse(0) == 4159204, "the rest of the effects are left alone");
        expect("frozen".equals(readable.getString("temperature_modifier").orElse(null)),
            "a choice the game does know is left alone");
        expect(Math.abs(readable.getFloat("temperature").orElse(0F) - 0.25F) < 1.0E-6F, "the biome's numbers are left alone");
    }

    /** Wilder Wild's day timeline: tracks keyed by names only that mod can look up. */
    private static void checkTimeline() {
        CompoundTag track = new CompoundTag();
        track.putInt("ticks", 12600);

        CompoundTag tracks = new CompoundTag();
        tracks.put("wilderwild:gameplay/pale_mushroom_active", track);
        tracks.put("minecraft:gameplay/day", track.copy());

        CompoundTag timeline = new CompoundTag();
        timeline.putString("clock", "minecraft:overworld");
        timeline.putInt("period_ticks", 24000);
        timeline.put("tracks", tracks);

        CompoundTag readable = (CompoundTag) only(Registries.TIMELINE, id("wilderwild", "wilderwild_day"), timeline);
        CompoundTag readableTracks = readable.getCompound("tracks").orElseThrow();

        expect(!readableTracks.keySet().contains("wilderwild:gameplay/pale_mushroom_active"),
            "a track the client cannot look up is left out");
        expect(readableTracks.keySet().contains("minecraft:gameplay/day"), "the game's own tracks stay");
        expect(readable.getInt("period_ticks").orElse(0) == 24000, "the rest of the timeline is left alone");
    }

    /** An entry with nothing wrong with it is not copied at all. */
    private static void checkVanillaUntouched() {
        CompoundTag effects = new CompoundTag();
        effects.putString("grass_color_modifier", "swamp");

        CompoundTag biome = new CompoundTag();
        biome.put("effects", effects);

        List<RegistrySynchronization.PackedRegistryEntry> entries =
            List.of(new RegistrySynchronization.PackedRegistryEntry(id("minecraft", "swamp"), Optional.of(biome)));
        List<RegistrySynchronization.PackedRegistryEntry> readable = ReadableRegistryData.readable(Registries.BIOME, null, entries);

        expect(readable == entries, "an entry that needs nothing doing to it is handed straight back");
    }

    /** The same rules apply however deeply the offending value is buried. */
    private static void checkNestedInsideLists() {
        CompoundTag effects = new CompoundTag();
        effects.putString("grass_color_modifier", "somemod_jungle");

        CompoundTag holder = new CompoundTag();
        holder.put("effects", effects);

        ListTag list = new ListTag();
        list.add(holder);

        CompoundTag root = new CompoundTag();
        root.put("variants", list);

        CompoundTag readable = (CompoundTag) only(Registries.BIOME, id("somemod", "thing"), root);
        CompoundTag readableEffects = readable.getList("variants").orElseThrow()
            .getCompound(0).orElseThrow()
            .getCompound("effects").orElseThrow();

        expect("none".equals(readableEffects.getString("grass_color_modifier").orElse(null)),
            "a value inside a list is put right too");
        expect("somemod_jungle".equals(effects.getString("grass_color_modifier").orElse(null)),
            "the server's own copy of the data is not changed");
    }

    private static Tag only(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> registry, Identifier id, CompoundTag data) {
        List<RegistrySynchronization.PackedRegistryEntry> readable = ReadableRegistryData.readable(
            registry, null, List.of(new RegistrySynchronization.PackedRegistryEntry(id, Optional.of(data))));
        return readable.getFirst().data().orElseThrow();
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Registry data check failed: " + what);
        }
    }
}
