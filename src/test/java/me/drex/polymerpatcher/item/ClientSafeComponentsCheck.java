package me.drex.polymerpatcher.item;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import io.netty.buffer.Unpooled;

/** Regression check for component types leaking from a real item into its vanilla stand-in. */
public final class ClientSafeComponentsCheck {
    private ClientSafeComponentsCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Early mod registration shifts even vanilla potion holders. The last vanilla potion is sent
        // as 46 after one custom registration, reproducing the same missing id without a custom value.
        var shifted = new net.minecraft.core.MappedRegistry<Potion>(
            net.minecraft.core.registries.Registries.POTION, com.mojang.serialization.Lifecycle.stable());
        shifted.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.POTION,
            net.minecraft.resources.Identifier.parse("example:early")), new Potion("early"),
            net.minecraft.core.RegistrationInfo.BUILT_IN);
        for (Potion potion : BuiltInRegistries.POTION) {
            var holder = shifted.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.POTION,
                BuiltInRegistries.POTION.getKey(potion)), potion, net.minecraft.core.RegistrationInfo.BUILT_IN);
            int actual = me.drex.polymerpatcher.util.VanillaPotionIds.id(holder);
            if (actual != BuiltInRegistries.POTION.getId(potion) || actual == shifted.getId(potion)) {
                throw new AssertionError("vanilla potion mapping depended on mod registration order: " + holder);
            }
        }
        shifted.freeze();
        int shiftedLast = shifted.getId(net.minecraft.world.item.alchemy.Potions.INFESTED.value());
        if (shiftedLast != 46) throw new AssertionError("26.2 potion fixture changed: " + shiftedLast);
        System.out.println("Verified every vanilla potion wire id after early mod registration, including server id 46");

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

        // A VANILLA component can still contain an unreadable modded registry holder. Round-trip the
        // actual 26.2 component codec using only vanilla registries, the way the receiving client does.
        var serverPotions = new net.minecraft.core.MappedRegistry<Potion>(
            net.minecraft.core.registries.Registries.POTION, com.mojang.serialization.Lifecycle.stable());
        for (Potion potion : BuiltInRegistries.POTION) {
            serverPotions.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.POTION,
                BuiltInRegistries.POTION.getKey(potion)), potion, net.minecraft.core.RegistrationInfo.BUILT_IN);
        }
        var customHolder = serverPotions.register(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.POTION, net.minecraft.resources.Identifier.parse("example:server_only")),
            new Potion("server_only"), net.minecraft.core.RegistrationInfo.BUILT_IN);
        serverPotions.freeze();
        PotionContents customPotion = new PotionContents(customHolder);
        DataComponentPatch unsafe = DataComponentPatch.builder().set(DataComponents.POTION_CONTENTS, customPotion).build();
        var vanillaAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var serverAccess = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
            vanillaAccess.registries().filter(reg -> !reg.key().equals(net.minecraft.core.registries.Registries.POTION)),
            java.util.stream.Stream.of(new RegistryAccess.RegistryEntry<>(net.minecraft.core.registries.Registries.POTION, serverPotions))));
        var originalWire = Unpooled.buffer();
        try {
            DataComponentPatch.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(originalWire, serverAccess), unsafe);
            try {
                DataComponentPatch.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(originalWire, vanillaAccess));
                throw new AssertionError("unsafe potion unexpectedly decoded without its registry entry");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().equals("No value with id " + serverPotions.getId(customHolder.value()))) {
                    throw expected;
                }
                System.out.println("Reproduced nested potion decode failure: " + expected.getMessage());
            }
        } finally {
            originalWire.release();
        }
        DataComponentPatch safePotion = ClientSafePotionContents.sanitize(unsafe);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            DataComponentPatch.STREAM_CODEC.encode(buf, safePotion);
            DataComponentPatch received = DataComponentPatch.STREAM_CODEC.decode(buf);
            PotionContents decoded = received.get(DataComponentMap.EMPTY, DataComponents.POTION_CONTENTS);
            if (decoded.potion().isPresent() || decoded.getColor() != customPotion.getColor()
                || !decoded.customName().orElseThrow().equals("server_only") || buf.isReadable()) {
                throw new AssertionError("custom potion did not round-trip as a complete client-safe value");
            }
            if (unsafe.get(DataComponentMap.EMPTY, DataComponents.POTION_CONTENTS) != customPotion) {
                throw new AssertionError("wire conversion mutated the original server potion");
            }
        } finally {
            buf.release();
        }
        System.out.println("Client-safe item component and nested potion registry checks passed");
    }
}
