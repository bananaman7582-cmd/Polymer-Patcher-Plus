package me.drex.polymerpatcher.compat.alexsmobs;

import me.drex.polymerpatcher.item.AirMiningFeedback;
import me.drex.polymerpatcher.item.HeldItemPresentations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** Alex's Mobs facts for the otherwise global tool-carrier and air-mining paths. */
final class AlexsMobsCarver {
    private static final Identifier CARVER = Identifier.fromNamespaceAndPath("alexsmobs", "dimensional_carver");
    private static final Identifier SHATTERED = Identifier.fromNamespaceAndPath("alexsmobs", "shattered_dimensional_carver");
    private static boolean initialized;

    private AlexsMobsCarver() {
    }

    static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        HeldItemPresentations.register(new HeldItemPresentations.Provider() {
            @Override
            public net.minecraft.world.item.Item carrier(ItemStack original) {
                return isCarver(original) ? Items.DIAMOND_PICKAXE : null;
            }

            @Override
            public boolean allowHeldStill(ItemStack original) {
                // The portal charge runs server-side after the click. Leaving the client in a
                // synthetic held-use pose suppresses every first-person mining swing.
                return !isCarver(original);
            }
        });

        AirMiningFeedback.register((player, stack) -> {
            if (!isCarver(stack)) {
                return null;
            }
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data == null) {
                return null;
            }
            var tag = data.copyTag();
            if (!tag.getBooleanOr("HASBLOCK", false)) {
                return null;
            }
            Vec3 target = new Vec3(tag.getDoubleOr("BLOCKX", player.getX()),
                tag.getDoubleOr("BLOCKY", player.getY()),
                tag.getDoubleOr("BLOCKZ", player.getZ()));
            int stage = Math.clamp(player.getTicksUsingItem() / 20, 0, 9);
            return new AirMiningFeedback.Target(target, stage, "alexsmobs");
        });
    }

    private static boolean isCarver(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return CARVER.equals(id) || SHATTERED.equals(id);
    }
}
