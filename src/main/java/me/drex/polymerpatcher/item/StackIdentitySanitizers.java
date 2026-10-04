package me.drex.polymerpatcher.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Extension point for volatile mod data which changes visuals but must not replay the vanilla equip bob.
 * The storage fix is global; compat packages only describe fields whose meaning belongs to their mod.
 */
public final class StackIdentitySanitizers {
    private StackIdentitySanitizers() {
    }

    @FunctionalInterface
    public interface Sanitizer {
        void sanitize(ItemStack original, Identifier itemId, CompoundTag customData);
    }

    private static final List<Sanitizer> SANITIZERS = new CopyOnWriteArrayList<>();

    public static void register(Sanitizer sanitizer) {
        SANITIZERS.add(sanitizer);
    }

    public static void sanitize(ItemStack original, Identifier itemId, CompoundTag customData) {
        for (Sanitizer sanitizer : SANITIZERS) {
            sanitizer.sanitize(original, itemId, customData);
        }
    }
}
