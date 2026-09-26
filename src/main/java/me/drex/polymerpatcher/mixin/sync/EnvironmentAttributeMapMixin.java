package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps unknown environment-attribute ids out of the data sent to an unmodified client.
 *
 * <p>The old patch stopped {@code Builder.syncable()} from setting its flag at all. Since that
 * method also builds Minecraft's own visual attributes, it removed the Overworld sky, fog and
 * light colours from every biome packet. Their client defaults are black, which made the whole
 * sky and some fully-lit terrain render pitch black.</p>
 *
 * <p>Filtering the finished network map instead preserves every vanilla attribute (including
 * values changed by a mod) while still removing keys a vanilla client's registry cannot know.</p>
 */
@Mixin(EnvironmentAttributeMap.class)
public class EnvironmentAttributeMapMixin {
    @ModifyArg(
        method = "filterSyncable",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;copyOf(Ljava/util/Map;)Ljava/util/Map;"),
        index = 0
    )
    private static Map<?, ?> polymer_patcher$keepVanillaEnvironmentAttributes(Map<?, ?> entries) {
        Map<Object, Object> filtered = new LinkedHashMap<>();

        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof EnvironmentAttribute<?> attribute)) {
                continue;
            }

            Identifier id = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute);
            if (id != null && id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }

        return filtered;
    }
}
