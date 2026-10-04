package me.drex.polymerpatcher.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.equipment.Equippable;

/**
 * A semantic fallback for armour whose mod forgot the vanilla enchantable armour tags.
 *
 * <p>The slot alone is not enough: elytra and several wearable curios also use a humanoid armour
 * slot. Requiring a positive armour or toughness modifier keeps those items on their own vanilla
 * enchantment rules while recognising items made through 26.2's armour component API.</p>
 */
public final class ArmorEnchantability {
    private ArmorEnchantability() {
    }

    public static boolean supports(Enchantment enchantment, ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || !isHumanoidArmorSlot(equippable.slot())
            || !stack.has(DataComponents.ENCHANTABLE)) {
            return false;
        }

        EquipmentSlot slot = equippable.slot();
        ItemAttributeModifiers modifiers = stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY
        );
        boolean hasArmorStats = modifiers.compute(Attributes.ARMOR, 0.0D, slot) > 0.0D
            || modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0D, slot) > 0.0D;
        return hasArmorStats && enchantment.matchingSlot(slot);
    }

    private static boolean isHumanoidArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
            || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }
}
