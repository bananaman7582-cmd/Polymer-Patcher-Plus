package me.drex.polymerpatcher.entity.armor;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/** Generic extension point for abilities which temporarily replace an equipped armor model. */
public final class ArmorRenderHooks {
    private static final List<BiPredicate<Player, ItemStack>> SUPPRESSORS = new CopyOnWriteArrayList<>();

    private ArmorRenderHooks() {
    }

    public static void suppressWhen(BiPredicate<Player, ItemStack> predicate) {
        SUPPRESSORS.add(predicate);
    }

    public static boolean shouldRender(Player player, ItemStack stack) {
        for (BiPredicate<Player, ItemStack> suppressor : SUPPRESSORS) {
            if (suppressor.test(player, stack)) {
                return false;
            }
        }
        return true;
    }
}
