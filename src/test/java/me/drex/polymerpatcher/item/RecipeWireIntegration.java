package me.drex.polymerpatcher.item;

import com.mojang.serialization.Lifecycle;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.stream.Stream;

/** Opt-in full-modpack recipe packet check. Never install on a live server: it shuts down after testing. */
public final class RecipeWireIntegration implements ModInitializer {
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                verify(server);
                System.out.println("RECIPE_WIRE_INTEGRATION_PASS");
            } catch (Throwable error) {
                System.err.println("RECIPE_WIRE_INTEGRATION_FAIL");
                error.printStackTrace();
            } finally {
                server.halt(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void verify(MinecraftServer server) {
        var serverAccess = server.registryAccess();
        var replacements = List.of(vanilla(BuiltInRegistries.RECIPE_BOOK_CATEGORY),
            vanilla(BuiltInRegistries.RECIPE_DISPLAY), vanilla(BuiltInRegistries.SLOT_DISPLAY));
        Set<net.minecraft.resources.ResourceKey<?>> replaced = new HashSet<>();
        replacements.forEach(r -> replaced.add(r.key()));
        RegistryAccess clientAccess = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(
            serverAccess.registries().filter(r -> !replaced.contains(r.key())), replacements.stream()));
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var provider = (PacketContextProvider) (Object) connection;
        provider.getPacketContext().set((PacketContext.Key<RegistryAccess>) PacketContext.REGISTRY_ACCESS, serverAccess);
        var all = new ArrayList<ClientboundRecipeBookAddPacket.Entry>();
        var failures = new ArrayList<String>();
        var filtered = new HashSet<String>();
        boolean[] nativeChecked = {false};
        for (var recipe : server.getRecipeManager().getRecipes()) {
            server.getRecipeManager().listDisplaysForRecipe(recipe.id(), entry -> {
                try {
                    if (!RecipeBookContents.displayIsReadable(entry, provider.getPacketContext())) {
                        if (!nativeChecked[0]) {
                            var type = entry.display().type();
                            var old = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.RECIPE_DISPLAY, type);
                            try {
                                // Emulate an integration that really synchronizes this custom codec to
                                // native clients, rather than merely announcing that its mod is present.
                                PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.RECIPE_DISPLAY, type,
                                    (original, context) -> original);
                                if (!RecipeBookContents.displayIsReadable(entry, provider.getPacketContext())) {
                                    throw new AssertionError("Readable native custom display was hidden");
                                }
                                var nativeEntry = entry.craftingRequirements().isPresent()
                                    ? RecipeBookContents.answer(entry, true) : entry;
                                roundTrip(new ClientboundRecipeBookAddPacket(List.of(
                                    new ClientboundRecipeBookAddPacket.Entry(nativeEntry, (byte) 0)), false),
                                    provider, serverAccess, serverAccess);
                                nativeChecked[0] = true;
                            } finally {
                                PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.RECIPE_DISPLAY, type, old);
                            }
                        }
                        filtered.add(entry.display().getClass().getName());
                        return;
                    }
                    var answer = entry.craftingRequirements().isPresent() ? RecipeBookContents.answer(entry, true) : entry;
                    var packetEntry = new ClientboundRecipeBookAddPacket.Entry(answer, (byte) 0);
                    roundTrip(new ClientboundRecipeBookAddPacket(List.of(packetEntry), false), provider, serverAccess, clientAccess);
                    all.add(packetEntry);
                } catch (Throwable error) {
                    String problem = recipe.id().identifier() + " display=" + entry.display().getClass().getName()
                        + " category=" + BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.category()) + ": " + error;
                    System.err.println("RECIPE_WIRE_FAILURE " + problem);
                    failures.add(problem);
                }
            });
        }
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " unsafe recipe display(s)");
        roundTrip(new ClientboundRecipeBookAddPacket(all, true), provider, serverAccess, clientAccess);
        System.out.println("Verified " + all.size() + " actual recipe displays individually and together in recipe_book_add");
        System.out.println("Filtered unsupported display types without altering recipes: " + filtered);
        System.out.println("Verified genuinely shared/native custom display preserved: " + nativeChecked[0]);
    }

    private static void roundTrip(ClientboundRecipeBookAddPacket packet, PacketContextProvider provider,
                                  RegistryAccess server, RegistryAccess client) {
        var wire = Unpooled.buffer();
        try {
            PacketContext.runWithContext(provider, () -> ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(
                new RegistryFriendlyByteBuf(wire, server), packet));
            var decoded = PacketContext.supplyWithoutContext(() -> ClientboundRecipeBookAddPacket.STREAM_CODEC.decode(
                new RegistryFriendlyByteBuf(wire, client)));
            if (wire.isReadable() || decoded.entries().size() != packet.entries().size()) {
                throw new AssertionError("Recipe payload did not consume exactly its own bytes");
            }
        } finally { wire.release(); }
    }

    private static <T> RegistryAccess.RegistryEntry<T> vanilla(Registry<T> source) {
        var client = new MappedRegistry<T>(source.key(), Lifecycle.stable());
        for (var holder : source.listElements().toList()) {
            if (holder.key().identifier().getNamespace().equals("minecraft")) {
                client.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN);
            }
        }
        client.freeze();
        return new RegistryAccess.RegistryEntry<>(source.key(), client);
    }
}
