package me.drex.polymerpatcher.item;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Mod-independent pipeline for exact component translations supplied by compat modules. */
public final class ItemStackPatches {
    private ItemStackPatches() {
    }

    @FunctionalInterface
    public interface Modifier {
        void modify(ItemStack out, ItemStack original, PacketContext context);
    }

    private static final List<Modifier> MODIFIERS = new CopyOnWriteArrayList<>();

    public static void register(Modifier modifier) {
        MODIFIERS.add(modifier);
    }

    public static void apply(ItemStack out, ItemStack original, PacketContext context) {
        for (Modifier modifier : MODIFIERS) {
            modifier.modify(out, original, context);
        }
    }
}
