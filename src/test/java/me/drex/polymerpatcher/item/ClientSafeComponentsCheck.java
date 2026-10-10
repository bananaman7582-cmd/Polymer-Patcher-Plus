package me.drex.polymerpatcher.item;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression check for component types leaking from a real item into its vanilla stand-in. */
public final class ClientSafeComponentsCheck {
    private ClientSafeComponentsCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        DataComponentType<Integer> serverOnly = DataComponentType.<Integer>builder().persistent(Codec.INT).build();
        Items.TRIAL_KEY.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        ItemStack carrier = new ItemStack(Items.TRIAL_KEY);
        carrier.set(serverOnly, 7);

        ItemStack safe = PolyBaseItem.keepOnlyClientComponents(carrier);
        if (safe.getComponentsPatch().entrySet().stream().anyMatch(entry -> entry.getKey() == serverOnly)) {
            throw new AssertionError("server-only component remained in carrier patch");
        }
        if (safe.getItem() != Items.TRIAL_KEY || safe.getCount() != carrier.getCount()) {
            throw new AssertionError("sanitizing components changed the carrier identity");
        }

        System.out.println("Client-safe item component checks passed");
    }
}
