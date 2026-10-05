package me.drex.polymerpatcher;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource;
import me.drex.polymerpatcher.block.color.ColorMapHelper;
import me.drex.polymerpatcher.command.PolymerPatcherCommand;
import me.drex.polymerpatcher.compat.CompatibilityModules;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.dump.data.RenderRegistryStorage;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.resources.ExtraResourcePacks;
import me.drex.polymerpatcher.resources.PerClientPackProvider;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.resources.ResourcePackGenerator;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PolymerPatcher implements DedicatedServerModInitializer {
    public static final String MOD_ID = "polymer-patcher";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Set<String> PATCHED_MODS = new HashSet<>();

    private static RenderRegistry renderRegistry = new RenderRegistry();

    public static RenderRegistry getRenderRegistry() {
        return renderRegistry;
    }

    @Override
    public void onInitializeServer() {
        ConfigManager.load();
        // Before anything can ask Polymer for a carrier, because it takes its copy of the pools the
        // first time it is touched and works from that copy afterwards
        me.drex.polymerpatcher.block.CarrierPools.widen();
        // Stonecutters do not use the recipe book. Their complete input/result table is sent once and
        // the client filters it locally, which cannot work when a modded input is represented by a
        // vanilla carrier. Polymer has a menu-scoped synchronizer which sends exactly the server's
        // matching recipes with the visible carrier as their input; it is opt-in, so turn it on.
        eu.pb4.polymer.core.api.item.PolymerItemUtils.enableStonecutterFix();
        me.drex.polymerpatcher.util.ModdedMenus.init();
        CompatibilityModules.initBeforeResources();
        // Registered before the server starts, which is when auto-host looks its configured type up
        ResourcePackDataProvider.register(id("per_client"), PerClientPackProvider::new);
        // Before the mods' own language files, so a name worked out here never covers a real one
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(me.drex.polymerpatcher.resources.MissingTranslations::generate);
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(ResourceHelper::init);
        me.drex.polymerpatcher.companion.CompanionServer.init();
        me.drex.polymerpatcher.effect.ModdedEffectNotices.init();
        CompatibilityModules.initAfterResources();
        me.drex.polymerpatcher.block.ModdedBlockAmbience.init();
        me.drex.polymerpatcher.block.BlockUsage.init();
        me.drex.polymerpatcher.util.LogQuieter.install();
        me.drex.polymerpatcher.item.RecipeBookContents.init();
        me.drex.polymerpatcher.item.ConvertedMaps.init();
        me.drex.polymerpatcher.item.MapDecorationFallbacks.init();
        me.drex.polymerpatcher.util.NativeItemSync.init();
        me.drex.polymerpatcher.util.ChunkSyncRepair.init();
        me.drex.polymerpatcher.block.fluid.ModdedFluidPhysics.init();
        me.drex.polymerpatcher.item.AirMiningFeedback.init();

        // Before anything can ask what a client has, so the answer is ready for the first packet
        me.drex.polymerpatcher.util.NativeClients.init();
        me.drex.polymerpatcher.item.ArmourAbilityMeter.init();
        me.drex.polymerpatcher.util.HolderRefresh.init();
        ExtraResourcePacks.init();
        ResourcePackGenerator.setup();
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        ColorMapHelper.init();
        me.drex.polymerpatcher.entity.armor.ArmorAttachments.init();
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, server) -> {
                me.drex.polymerpatcher.util.NativeClients.forget(handler.getPlayer());
                me.drex.polymerpatcher.util.ClientParticleBudget.forget(handler.getPlayer());
                me.drex.polymerpatcher.item.ArmourAbilityMeter.forget(handler.getPlayer());
                me.drex.polymerpatcher.util.HolderRefresh.forget(handler.getPlayer());
                me.drex.polymerpatcher.util.ModdedMenus.forget(handler.getPlayer());
                me.drex.polymerpatcher.util.NativeItemSync.forget(handler.getPlayer());
                me.drex.polymerpatcher.util.ChunkSyncRepair.forget(handler.getPlayer());
                me.drex.polymerpatcher.block.fluid.ModdedFluidPhysics.forget(handler.getPlayer());
                CompatibilityModules.forget(handler.getPlayer());

                me.drex.polymerpatcher.effect.ModdedEffectNotices.forget(handler.getPlayer());
            });

        renderRegistry = RenderRegistryStorage.load();
        // Once the level is up but before the door opens, so the one pass that fills Polymer's caches
        // happens with nothing else running alongside it
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Before the door opens, so a patch that no longer fits is a line in the log rather than the
            // first player to join
            me.drex.polymerpatcher.util.PatchSelfCheck.run();
            try {
                me.drex.polymerpatcher.util.VanillaEntityData.prewarm();
            } catch (Throwable e) {
                LOGGER.warn("Could not work out entity tracked data up front; it will be worked out as needed instead", e);
            }
            try {
                // Reads mod jars, so it is done here rather than on the connection thread of whichever
                // player happens to ask first
                me.drex.polymerpatcher.util.TrackedDataMods.shifting();
            } catch (Throwable e) {
                LOGGER.warn("Could not work out which mods renumber entity fields up front", e);
            }
            try {
                me.drex.polymerpatcher.resources.PackBuilder.buildIfNobodyElseWill(server);
            } catch (Throwable e) {
                LOGGER.error("Could not build the resource pack", e);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Nothing here is worth a server for. This runs before the level is loaded, so anything
            // thrown takes the whole start-up down with it - and what is lost by carrying on instead is
            // that modded content renders as its vanilla stand-in, which is how it looked before this
            // mod was installed anyway
            try {
                // Before any renderer is built, because a renderer that reaches for a missing class
                // does not just fail - it leaves that class permanently unusable for every renderer
                // after it
                me.drex.polymerpatcher.client.ClientOnlyFabricApi.ensureLoaded();
                // Before the first renderer too, and for the same reason: a class refused while
                // another class is setting itself up takes that one down permanently, and no amount
                // of trying again afterwards can bring it back
                // Before any of them is brought in, because a class can only be changed on its way past
                if (me.drex.polymerpatcher.config.ConfigManager.config().entities.drawModelPiecesDrawnAlone) {
                    me.drex.polymerpatcher.entity.citadel.CitadelBoxPatch.install();
                }
                me.drex.polymerpatcher.dump.ClientOnlyClasses.predefineSplitEnvironment();
                // Before the first renderer too: a Citadel class refused while a renderer is being set up
                // poisons that renderer for the rest of the run, which is how both of Alex's Caves' boats
                // came to be drawn as nothing
                me.drex.polymerpatcher.entity.citadel.CitadelModel.warmModelClasses();
                me.drex.polymerpatcher.entity.render.RenderCaptureRules.init();
                CompatibilityModules.setupRendering(renderRegistry);
                // Before models are registered: each resolved player skin needs the same generated
                // Borrowed Echo part models as the default skins already in the render dump.
                me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoPlayerSkins.prepare(server);
                reportDumpCoverage();
                AnimatedEntities.registerEntities(renderRegistry);
                me.drex.polymerpatcher.block.CitadelBlockModels.setup();
                me.drex.polymerpatcher.block.GeckoLibBlockModels.setup();
            } catch (Throwable e) {
                LOGGER.error("Failed to set up modded rendering; the server will run without it", e);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PolymerPatcherCommand.register(dispatcher);
            me.drex.polymerpatcher.command.EffectsCommand.register(dispatcher);
            me.drex.polymerpatcher.command.ModsCheckCommand.register(dispatcher);
        });
    }

    /**
     * Says out loud whether the dump still describes this server.
     * <p>
     * Everything drawn here comes from a dump taken on a client, and a mod the dump was taken without
     * is simply absent from it. What that looks like in the world is a mob that renders as nothing at
     * all, with not a word anywhere saying why - so the mismatch is worth naming while the server is
     * still starting, rather than leaving it to be discovered by walking into an invisible bear.
     */
    private static void reportDumpCoverage() {
        if (renderRegistry.entityData.isEmpty() && renderRegistry.blockData.isEmpty()) {
            LOGGER.warn("No render dump found - modded content will not render. Looked in {}, {} and {}.",
                RenderRegistryStorage.configDumpFile(), RenderRegistryStorage.gameDumpFile(), RenderRegistryStorage.sharedDumpFile());
            LOGGER.warn("Install this mod on a client with the same mods and open a single-player world; "
                + "the dump is taken by itself, then upload it to the server's config folder.");
            return;
        }

        if (!renderRegistry.isCurrentFormat()) {
            LOGGER.warn("The render dump at {} predates custom armor-renderer capture.",
                RenderRegistryStorage.existingDumpFile());
            LOGGER.warn("Open a single-player world once with this version installed, then restart the server to enable custom armor models.");
        }

        Set<String> missing = renderRegistry.missingNamespaces();
        if (!missing.isEmpty()) {
            LOGGER.warn("The render dump at {} was taken without {} - content from those mods will not render.",
                RenderRegistryStorage.existingDumpFile(), missing);
            LOGGER.warn("Open a single-player world on a client running the same mods to take a new one.");
        }
    }

    public static void setupModAssets(String modId) {
        ResourcePackExtras.forDefault().addBridgedModelsFolder(
            Identifier.fromNamespaceAndPath(modId, "block_sign")
        );
        ResourcePackExtras.forDefault().addBridgedModelsFolder(
            Identifier.fromNamespaceAndPath(modId, "block"),
            (id, resourcePackBuilder) -> {
                return new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))), ItemAsset.Properties.DEFAULT);
            }
        );
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Identifier.fromNamespaceAndPath(modId, "entity"), (id, b) -> {
            return new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))), new ItemAsset.Properties(true, true));
        });
        // Custom armour is drawn by item displays in exactly the same way as an entity model. The
        // generated model JSON is not enough on modern clients: ITEM_MODEL names an item definition,
        // and without this bridge the display resolves to nothing even though every part model and
        // texture is present in the pack.
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Identifier.fromNamespaceAndPath(modId, "armor"), (id, b) -> {
            return new ItemAsset(new BasicItemModel(id, List.of(new MapColorTintSource(0xFFFFFF))), new ItemAsset.Properties(true, true));
        });
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
