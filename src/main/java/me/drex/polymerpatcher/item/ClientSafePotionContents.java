package me.drex.polymerpatcher.item;

import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;
import java.util.Optional;

/** Makes potion values readable even when their registry entry already belongs to another Polymer mod. */
public final class ClientSafePotionContents {
    private ClientSafePotionContents() {}

    public static DataComponentPatch sanitize(DataComponentPatch patch) {
        PotionContents contents = patch.get(net.minecraft.core.component.DataComponentMap.EMPTY, DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return patch;
        }
        PotionContents safe = sanitize(contents);
        if (safe == contents) return patch;
        var builder = DataComponentPatch.builder();
        for (var component : patch.entrySet()) {
            if (component.getValue().isPresent()) {
                set(builder, component.getKey(), component.getValue().get());
            } else {
                builder.remove(component.getKey());
            }
        }
        return builder.set(DataComponents.POTION_CONTENTS, safe).build();
    }

    public static PotionContents sanitize(PotionContents contents) {
        if (contents.potion().isEmpty()) {
            return contents;
        }
        var holder = contents.potion().get();
        if (me.drex.polymerpatcher.util.VanillaPotionIds.id(holder) >= 0
            && !RegistrySyncUtils.isServerEntry(BuiltInRegistries.POTION, holder.value())) {
            return contents;
        }

        // Potion registry numbers are not synchronized by NativeItemSync. An item can be vanilla while
        // its potion holder is server-only, and checking only the component TYPE cannot catch this.
        // Keep the visual colour/name without transmitting the missing holder or custom effect ids.
        return new PotionContents(Optional.empty(), Optional.of(contents.getColor()),
            List.of(), contents.customName().or(() -> Optional.of(holder.value().name())));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(DataComponentPatch.Builder builder,
                            net.minecraft.core.component.DataComponentType type, Object value) {
        builder.set(type, value);
    }
}
