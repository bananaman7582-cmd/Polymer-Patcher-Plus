package me.drex.polymerpatcher.dump;

import me.drex.polymerpatcher.dump.command.DumpCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.ColorResolver;
import nl.theepicblock.resourcelocatorapi.ResourceLocatorApi;
import nl.theepicblock.resourcelocatorapi.api.AssetContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PolymerPatcherDumper implements ClientModInitializer {
    public static final String MOD_ID = "polymer-patcher-client-dump";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final AssetContainer GLOBAL_ASSETS = ResourceLocatorApi.createGlobalAssetContainer();
    public static final ThreadLocal<ColorResolver> COLOR_RESOLVER = new ThreadLocal<>();

    @Override
    public void onInitializeClient() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            DumpCommand.register(dispatcher)
        );

        // The integrated server starting is a single-player world opening, which is the first moment
        // there is somewhere to build a mob and ask it to draw itself
        ServerLifecycleEvents.SERVER_STARTED.register(AutoDump::onServerStarted);
    }
}
