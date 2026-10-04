package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import me.drex.polymerpatcher.companion.shared.CompanionPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CompanionClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        registerTints();
        registerFluids();
        SelfTest.init();

        ClientConfigurationNetworking.registerGlobalReceiver(CompanionPayloads.Request.TYPE, (payload, context) -> {
            String server = serverKey(context.packetListener());
            if (server == null) {
                CompanionMod.LOGGER.info("Not saving blocks for this connection: it is not to a server in the server list");
            }
            String registered = server == null ? null : ManifestStore.registeredHash(server);
            String saved = server == null ? null : ManifestStore.savedHash(server);
            String report = registered != null ? registered : saved != null ? saved : "";
            context.responseSender().sendPacket(new CompanionPayloads.Hello(CompanionPayloads.PROTOCOL, report,
                registered != null && registered.equals(report)));
        });

        ClientConfigurationNetworking.registerGlobalReceiver(CompanionPayloads.Manifest.TYPE, (payload, context) -> {
            String server = serverKey(context.packetListener());
            CompanionMod.LOGGER.info("Received this server's blocks ({} KiB) for {}", payload.data().length / 1024, server);
            if (server == null) {
                return;
            }
            try {
                // Read before it is kept, so a damaged download never stops the game starting next time
                CompanionManifest.fromBytes(payload.data());
                if (!CompanionManifest.hashOf(payload.data()).equals(payload.hash())) {
                    CompanionMod.LOGGER.warn("The blocks {} sent arrived damaged; they were not saved", server);
                    return;
                }
                ManifestStore.save(server, payload.data());
                boolean hadOlder = ManifestStore.registeredHash(server) != null;
                CompanionState.setNotice(hadOlder
                    ? "This server's blocks have changed since you last restarted. Restart Minecraft to see the new ones as real blocks."
                    : "This server's blocks have been downloaded. Restart Minecraft to see them as real blocks.");
                CompanionMod.LOGGER.info("Saved the blocks of {} ({} KiB); they will be used after a restart", server, payload.data().length / 1024);
            } catch (Throwable e) {
                CompanionMod.LOGGER.warn("Could not save the blocks {} sent", server, e);
            }
        });

        ClientConfigurationNetworking.registerGlobalReceiver(CompanionPayloads.Status.TYPE, (payload, context) -> {
            CompanionState.setDecoding(payload.active());
            if (payload.active()) {
                CompanionMod.LOGGER.info("Using this server's real blocks");
            }
        });

        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> CompanionState.setDecoding(false));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CompanionState.setDecoding(false));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String notice = CompanionState.takeNotice();
            if (notice != null) {
                client.execute(() -> client.gui.chatListener().handleSystemMessage(Component.literal("[Polymer Patcher++ Client] ")
                    .withStyle(ChatFormatting.GOLD).append(Component.literal(notice).withStyle(ChatFormatting.YELLOW)), false));
            }
        });
    }

    /** The server being joined, as its saved manifest is filed; null in single player. */
    static @Nullable String serverKey(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl listener) {
        ServerData server = null;
        try {
            server = ((me.drex.polymerpatcher.companion.client.mixin.ClientCommonPacketListenerImplAccessor) listener).polymerPatcherClient$serverData();
        } catch (Throwable ignored) {
        }
        if (server == null) {
            server = Minecraft.getInstance().getCurrentServer();
        }
        return server == null || server.ip == null || server.ip.isBlank() ? null : ManifestStore.keyOf(server.ip);
    }

    /** Grass, leaves and the like take their colour from the biome, as they do with the mod installed. */
    private static void registerTints() {
        Map<Integer, List<Block>> byTint = new HashMap<>();
        for (ProxyBlock block : ProxyRegistry.BLOCKS) {
            if (block.tint() != CompanionManifest.TINT_NONE) {
                byTint.computeIfAbsent(block.tint(), key -> new ArrayList<>()).add(block);
            }
        }
        byTint.forEach((tint, blocks) -> {
            BlockTintSource source = switch (tint) {
                case CompanionManifest.TINT_GRASS -> BlockTintSources.grass();
                case CompanionManifest.TINT_FOLIAGE -> BlockTintSources.foliage();
                case CompanionManifest.TINT_DRY_FOLIAGE -> BlockTintSources.dryFoliage();
                case CompanionManifest.TINT_WATER -> BlockTintSources.water();
                default -> null;
            };
            if (source != null) {
                BlockColorRegistry.register(List.of(source), blocks.toArray(Block[]::new));
            }
        });
    }

    /** Each fluid drawn from the still and flowing pictures the server found for it. */
    private static void registerFluids() {
        for (ProxyFluid.Family family : ProxyRegistry.FLUIDS) {
            CompanionManifest.FluidEntry entry = family.entry;
            BlockTintSource tint = entry.tint() == -1 ? null : BlockTintSources.constant(entry.tint());
            FluidRenderingRegistry.register(family.source, family.flowing, new FluidModel.Unbaked(
                new Material(entry.stillTexture()), new Material(entry.flowingTexture()), null, tint));
        }
    }
}
