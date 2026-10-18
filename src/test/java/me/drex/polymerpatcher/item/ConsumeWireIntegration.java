package me.drex.polymerpatcher.item;

import com.mojang.serialization.Lifecycle;
import eu.pb4.polymer.core.impl.networking.entry.PolymerItemEntry;
import eu.pb4.polymer.networking.api.ContextByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.consume_effects.*;

import java.util.List;
import java.util.stream.Stream;

/** Opt-in actual-mod integration test; shuts its throwaway server down after checking. */
public final class ConsumeWireIntegration implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                verify(server);
                System.out.println("CONSUME_WIRE_INTEGRATION_PASS");
            } catch (Throwable error) {
                System.err.println("CONSUME_WIRE_INTEGRATION_FAIL");
                error.printStackTrace();
            } finally {
                server.halt(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void verify(MinecraftServer server) {
        var clientTypes = new MappedRegistry<ConsumeEffect.Type<?>>(Registries.CONSUME_EFFECT_TYPE, Lifecycle.stable());
        for (var type : List.of(ConsumeEffect.Type.APPLY_EFFECTS, ConsumeEffect.Type.REMOVE_EFFECTS,
            ConsumeEffect.Type.CLEAR_ALL_EFFECTS, ConsumeEffect.Type.TELEPORT_RANDOMLY, ConsumeEffect.Type.PLAY_SOUND)) {
            clientTypes.register(net.minecraft.resources.ResourceKey.create(Registries.CONSUME_EFFECT_TYPE,
                BuiltInRegistries.CONSUME_EFFECT_TYPE.getKey(type)), type, RegistrationInfo.BUILT_IN);
        }
        clientTypes.freeze();
        var serverAccess = server.registryAccess();
        RegistryAccess clientAccess = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(
            serverAccess.registries().filter(r -> !r.key().equals(Registries.CONSUME_EFFECT_TYPE)),
            Stream.of(new RegistryAccess.RegistryEntry<>(Registries.CONSUME_EFFECT_TYPE, clientTypes))));
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var provider = (PacketContextProvider) (Object) connection;
        var context = provider.getPacketContext();
        context.set((PacketContext.Key<RegistryAccess>) PacketContext.REGISTRY_ACCESS, serverAccess);
        int checked = 0;
        boolean milkChecked = false;
        // Use the exact installed default stacks, not a recreated approximation of their components.
        for (var holder : BuiltInRegistries.ITEM.listElements().toList()) {
            if (!holder.key().identifier().getNamespace().equals("farmersdelight")) continue;
            var source = holder.value().getDefaultInstance();
            var original = source.get(DataComponents.CONSUMABLE);
            var wire = Unpooled.buffer();
            try {
                var entry = new PolymerItemEntry(BuiltInRegistries.ITEM.getId(holder.value()), holder.key().identifier(), source);
                PacketContext.runWithContext(provider, () -> PolymerItemEntry.CODEC.encode(
                    new ContextByteBuf(context, 10, wire, serverAccess), entry));
                var decoded = PacketContext.supplyWithoutContext(() -> PolymerItemEntry.CODEC.decode(
                    new ContextByteBuf(context, 10, wire, clientAccess)));
                if (wire.isReadable()) throw new AssertionError("Trailing bytes for " + holder.key().identifier());
                if (Identifier.parse("farmersdelight:milk_bottle").equals(holder.key().identifier())) {
                    if (original == null || original.onConsumeEffects().isEmpty()) throw new AssertionError("Missing milk fixture");
                    var shown = decoded.representation().get(DataComponents.CONSUMABLE);
                    if (shown == null || shown.onConsumeEffects().isEmpty()
                        || !(shown.onConsumeEffects().getFirst() instanceof ApplyStatusEffectsConsumeEffect safe)
                        || !safe.effects().isEmpty()) throw new AssertionError("Milk fallback not exercised");
                    if (source.get(DataComponents.CONSUMABLE) != original
                        || original.onConsumeEffects().getFirst().getClass().getName().startsWith("net.minecraft.")) {
                        throw new AssertionError("Original milk server behavior was replaced");
                    }
                    milkChecked = true;
                    System.out.println("Actual farmersdelight:milk_bottle decoded with vanilla-only consume-effect types; server effect preserved");
                }
                checked++;
            } catch (Throwable error) {
                throw new AssertionError("Actual Polymer sync/items failed for " + holder.key().identifier(), error);
            } finally {
                wire.release();
            }
        }
        if (!milkChecked) throw new AssertionError("Farmer's Delight milk bottle was not checked");
        System.out.println("Verified " + checked + " installed Farmer's Delight item representations through PolymerItemEntry.CODEC");
    }
}
