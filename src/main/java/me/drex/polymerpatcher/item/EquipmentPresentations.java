package me.drex.polymerpatcher.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/**
 * Selects vanilla carriers for component-defined equipment.
 *
 * <p>This deliberately knows no mod names. In 26.2 the capability lives on the stack: shields carry
 * {@code BLOCKS_ATTACKS}, gliders carry {@code GLIDER}, and wearable items carry {@code EQUIPPABLE}.
 * Keeping that decision here prevents individual compat packages from having to rediscover the same
 * vanilla behavior for every new shield, elytra or armour set.</p>
 */
public final class EquipmentPresentations {
    private EquipmentPresentations() {
    }

    public static boolean isGlider(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
    }

    public static @Nullable Item carrier(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof ShieldItem || stack.has(DataComponents.BLOCKS_ATTACKS)) {
            return Items.SHIELD;
        }
        if (isGlider(stack)) {
            return Items.ELYTRA;
        }

        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return null;
        }
        return switch (equippable.slot()) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            case FEET -> Items.IRON_BOOTS;
            default -> null;
        };
    }
}
