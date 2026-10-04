package me.drex.polymerpatcher.item;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.equipment.Equippable;

/** Executable regression check for untagged component-defined mod armour. */
public final class ArmorEnchantabilityCheck {
    private ArmorEnchantabilityCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        ItemAttributeModifiers armorStats = ItemAttributeModifiers.builder()
            .add(Attributes.ARMOR, new AttributeModifier(
                Identifier.fromNamespaceAndPath("polymer-patcher", "test_armor"),
                8.0D, AttributeModifier.Operation.ADD_VALUE
            ), EquipmentSlotGroup.CHEST)
            .build();
        Items.BONE.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST).build())
            .set(DataComponents.ENCHANTABLE, new Enchantable(10))
            .set(DataComponents.ATTRIBUTE_MODIFIERS, armorStats)
            .build());
        Items.FEATHER.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST).build())
            .set(DataComponents.ENCHANTABLE, new Enchantable(10))
            .build());

        Enchantment armorEnchantment = enchantment(EquipmentSlotGroup.ARMOR);
        Enchantment handEnchantment = enchantment(EquipmentSlotGroup.MAINHAND);
        expect("untagged semantic chest armour", armorEnchantment, Items.BONE, true);
        expect("chest wearable without armour stats", armorEnchantment, Items.FEATHER, false);
        expect("hand enchantment on semantic armour", handEnchantment, Items.BONE, false);

        System.out.println("Verified semantic armour enchantability and wearable false positives");
    }

    private static Enchantment enchantment(EquipmentSlotGroup slot) {
        return new Enchantment(
            Component.literal("test"),
            Enchantment.definition(
                HolderSet.direct(Items.STICK.builtInRegistryHolder()), 1, 4,
                Enchantment.constantCost(1), Enchantment.constantCost(10), 1, slot
            ),
            HolderSet.direct(), DataComponentMap.EMPTY
        );
    }

    private static void expect(String label, Enchantment enchantment,
                               net.minecraft.world.item.Item item, boolean expected) {
        boolean actual = ArmorEnchantability.supports(enchantment, new ItemStack(item));
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
