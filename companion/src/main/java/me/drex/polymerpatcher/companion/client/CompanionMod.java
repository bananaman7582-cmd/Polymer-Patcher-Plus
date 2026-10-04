package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers a real block for every modded block of each Polymer Patcher++ server this client has
 * visited, while the game is still starting - the only moment the game accepts new blocks.
 */
public final class CompanionMod implements ModInitializer {

    public static final String MOD_ID = "polymer-patcher-client";
    public static final Logger LOGGER = LoggerFactory.getLogger("Polymer Patcher++ Client");

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundConfiguration().register(CompanionPayloads.Request.TYPE, CompanionPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().registerLarge(CompanionPayloads.Manifest.TYPE, CompanionPayloads.Manifest.CODEC,
            CompanionPayloads.MAX_MANIFEST_BYTES + 1024);
        PayloadTypeRegistry.clientboundConfiguration().register(CompanionPayloads.Status.TYPE, CompanionPayloads.Status.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(CompanionPayloads.Hello.TYPE, CompanionPayloads.Hello.CODEC);

        ProxyRegistry.registerAll();
    }
}
