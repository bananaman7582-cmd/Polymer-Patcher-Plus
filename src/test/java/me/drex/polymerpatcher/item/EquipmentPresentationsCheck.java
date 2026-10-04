package me.drex.polymerpatcher.item;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

/** Executable regression check for global component-defined equipment carriers. */
public final class EquipmentPresentationsCheck {
    private EquipmentPresentationsCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Items.FEATHER.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.GLIDER, Unit.INSTANCE)
            .set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST)
                .setAsset(EquipmentAssets.ELYTRA).build())
            .build());
        Items.BONE.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST)
                .setAsset(EquipmentAssets.IRON).build())
            .build());
        Items.STICK.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);

        expect("custom GLIDER wins over chest equipment", Items.FEATHER, Items.ELYTRA);
        expect("component-defined chest armour", Items.BONE, Items.IRON_CHESTPLATE);
        expect("ordinary item", Items.STICK, null);
        System.out.println("Verified global GLIDER/EQUIPPABLE carrier selection and glider precedence");
    }

    private static void expect(String label, Item input, Item expected) {
        Item actual = EquipmentPresentations.carrier(new ItemStack(input));
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
