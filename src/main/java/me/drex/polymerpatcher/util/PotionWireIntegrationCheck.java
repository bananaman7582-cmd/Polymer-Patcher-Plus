package me.drex.polymerpatcher.util;

import com.mojang.serialization.Lifecycle;
import eu.pb4.polymer.core.impl.networking.payloads.s2c.PolymerCreativeTabContentAddS2CPayload;
import eu.pb4.polymer.networking.api.ContextByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import java.util.*;

/** Opt-in development test that runs through Fabric's actually transformed Polymer payload codec. */
public final class PotionWireIntegrationCheck {
    private PotionWireIntegrationCheck() {}

    public static void run() {
        var serverPotions = new MappedRegistry<Potion>(Registries.POTION, Lifecycle.stable());
        var clientPotions = new MappedRegistry<Potion>(Registries.POTION, Lifecycle.stable());
        var custom = serverPotions.register(ResourceKey.create(Registries.POTION, Identifier.parse("wirecheck:early")),
            new Potion("early"), RegistrationInfo.BUILT_IN);
        // Copy pristine keys in pristine id order, regardless of the development server's modded registry.
        var vanilla = BuiltInRegistries.POTION.listElements().filter(h -> VanillaPotionIds.id(h) >= 0)
            .sorted(Comparator.comparingInt(VanillaPotionIds::id)).toList();
        for (var h : vanilla) {
            serverPotions.register(h.key(), h.value(), RegistrationInfo.BUILT_IN);
            clientPotions.register(h.key(), h.value(), RegistrationInfo.BUILT_IN);
        }
        me.drex.polymerpatcher.PolymerPatcher.LOGGER.info("Actual server potion at id 46: {}",
            BuiltInRegistries.POTION.getKey(BuiltInRegistries.POTION.byId(46)));
        for (var h : BuiltInRegistries.POTION.listElements().filter(h -> VanillaPotionIds.id(h) < 0).toList()) {
            if (h.key().identifier().getNamespace().equals("minecraft")) {
                me.drex.polymerpatcher.PolymerPatcher.LOGGER.info("Non-vanilla potion using minecraft namespace: {}", h.key().identifier());
            }
            serverPotions.register(h.key(), h.value(), RegistrationInfo.BUILT_IN);
        }
        serverPotions.freeze();
        clientPotions.freeze();
        var registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryAccess serverAccess = replacePotions(registryAccess, serverPotions);
        RegistryAccess clientAccess = replacePotions(registryAccess, clientPotions);
        var clientComponents = new MappedRegistry<net.minecraft.core.component.DataComponentType<?>>(
            Registries.DATA_COMPONENT_TYPE, Lifecycle.stable());
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            java.util.Objects.requireNonNull(PotionWireIntegrationCheck.class.getResourceAsStream(
                "/assets/polymer-patcher/vanilla_component_ids_26_2.tsv")), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var fields = line.split("\t", 2);
                var id = Identifier.parse(fields[1]);
                clientComponents.register(ResourceKey.create(Registries.DATA_COMPONENT_TYPE, id),
                    BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id), RegistrationInfo.BUILT_IN);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        clientComponents.freeze();
        clientAccess = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
            clientAccess.registries().filter(r -> !r.key().equals(Registries.DATA_COMPONENT_TYPE)),
            java.util.stream.Stream.of(new RegistryAccess.RegistryEntry<>(Registries.DATA_COMPONENT_TYPE, clientComponents))));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        PacketContext context = new PacketContext() {
            @SuppressWarnings("unchecked")
            public <T> T get(ReadKey<T> key) {
                if (key == CONNECTION) return (T) connection;
                if (key == REGISTRY_ACCESS) return (T) serverAccess;
                return null;
            }
            public <T> void set(Key<T> key, T value) { throw new UnsupportedOperationException(); }
        };
        var stacks = new ArrayList<ItemStack>();
        // The opt-in check exits during mod initialization, before the normal item component binding pass.
        Items.POTION.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        Items.TIPPED_ARROW.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        for (var h : serverPotions.listElements().toList()) {
            stacks.add(PotionContents.createItemStack(Items.POTION, h));
            stacks.add(PotionContents.createItemStack(Items.TIPPED_ARROW, h));
        }
        var payload = new PolymerCreativeTabContentAddS2CPayload(Identifier.parse("wirecheck:potions"),
            List.of(new PolymerCreativeTabContentAddS2CPayload.Entry(
                PolymerCreativeTabContentAddS2CPayload.Mode.INSERT_END, ItemStack.EMPTY, stacks)), List.of());
        var wire = Unpooled.buffer();
        try {
            for (var h : serverPotions.listElements().toList()) {
                var original = new PotionContents(h);
                PacketContext.runWithContext(new net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider() {
                    @Override public PacketContext getPacketContext() { return context; }
                }, () -> PotionContents.STREAM_CODEC.encode(new ContextByteBuf(context, 10, wire, serverAccess), original));
                var actual = PotionContents.STREAM_CODEC.decode(new ContextByteBuf(context, 10, wire, clientAccess));
                if (VanillaPotionIds.id(h) < 0 && actual.potion().isPresent()) throw new AssertionError("Direct potion codec leaked " + h);
                if (wire.isReadable()) throw new AssertionError("Direct potion codec left unread data");
                wire.clear();
            }
            PacketContext.runWithContext(new net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider() {
                @Override public PacketContext getPacketContext() { return context; }
            }, () ->
                PolymerCreativeTabContentAddS2CPayload.CODEC.encode(new ContextByteBuf(context, 10, wire, serverAccess), payload));
            var decoded = PolymerCreativeTabContentAddS2CPayload.CODEC.decode(new ContextByteBuf(context, 10, wire, clientAccess));
            var received = decoded.stacksMain().getFirst().stacks();
            if (received.size() != stacks.size() || wire.isReadable()) throw new AssertionError("Payload incomplete");
            for (int i = 0; i < stacks.size(); i++) {
                var original = stacks.get(i).get(DataComponents.POTION_CONTENTS);
                var actual = received.get(i).get(DataComponents.POTION_CONTENTS);
                var key = original.potion().orElseThrow().unwrapKey().orElseThrow();
                if (VanillaPotionIds.id(original.potion().orElseThrow()) < 0) {
                    if (actual.potion().isPresent()) throw new AssertionError("Custom potion holder escaped");
                } else if (!actual.potion().orElseThrow().unwrapKey().orElseThrow().equals(key)) {
                    throw new AssertionError("Wrong potion after Polymer round trip: " + key + " -> " + actual);
                }
            }
            me.drex.polymerpatcher.PolymerPatcher.LOGGER.info(
                "POTION WIRE INTEGRATION PASSED: {} stacks through real Polymer creative-tab codec with early custom registration", stacks.size());
        } finally {
            wire.release();
        }
    }

    private static RegistryAccess replacePotions(RegistryAccess base, Registry<Potion> potions) {
        return new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
            base.registries().filter(r -> !r.key().equals(Registries.POTION)),
            java.util.stream.Stream.of(new RegistryAccess.RegistryEntry<>(Registries.POTION, potions))));
    }
}
