package me.drex.polymerpatcher.registry;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Standalone check that a biome ambient light can be written the way the game reads one.
 *
 * <p>The whole feature rests on a single unproven assumption: that {@code AMBIENT_LIGHT_COLOR} survives the
 * codec biomes are sent to clients through. If it does not - if it is filtered out as unsyncable, or lands
 * under a key the client does not look under - the light silently never arrives, and the only way to find
 * out would be to walk into a cave. So it is encoded here, at build time, and read back.</p>
 */
public final class ModBiomeLightCheck {

    private ModBiomeLightCheck() {
    }

    /** What the Nether carries, and so what a cave biome lit like the Nether should come out as. */
    private static final int NETHER_AMBIENT = 0xFF302821;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        EnvironmentAttributeMap map = EnvironmentAttributeMap.builder()
            .set(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, NETHER_AMBIENT)
            .build();

        Tag encoded = EnvironmentAttributeMap.NETWORK_CODEC.encodeStart(NbtOps.INSTANCE, map)
            .resultOrPartial(problem -> {
                throw new AssertionError("An ambient light could not be written for the network: " + problem);
            })
            .orElseThrow(() -> new AssertionError("An ambient light encoded to nothing"));

        if (!(encoded instanceof CompoundTag compound) || compound.isEmpty()) {
            throw new AssertionError("An ambient light is dropped by the codec biomes are sent through: " + encoded);
        }

        EnvironmentAttributeMap read = EnvironmentAttributeMap.NETWORK_CODEC.parse(NbtOps.INSTANCE, encoded)
            .resultOrPartial(problem -> {
                throw new AssertionError("An ambient light could not be read back: " + problem);
            })
            .orElseThrow(() -> new AssertionError("An ambient light read back as nothing"));

        if (!read.contains(EnvironmentAttributes.AMBIENT_LIGHT_COLOR)) {
            throw new AssertionError("An ambient light did not survive the trip: " + compound);
        }

        int back = read.applyModifier(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 0);
        if (back != NETHER_AMBIENT) {
            throw new AssertionError("An ambient light came back as " + String.format("#%06X", back));
        }

        System.out.println("Verified a biome ambient light survives the registry sync as " + compound);
    }
}
