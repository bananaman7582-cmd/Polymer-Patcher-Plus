package me.drex.polymerpatcher.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the resource pack even when nothing else is going to.
 * <p>
 * Everything this mod does to a block or an item ends in the pack: the model a display entity wears,
 * the stand-in a carrier block is dressed as, the textures stitched for a fluid. A fix to any of it is
 * not a fix until the pack has been built again.
 * <p>
 * And Polymer only builds it on start-up as part of auto-hosting. Turning auto-hosting off - which is
 * the reasonable thing to do when hosting the pack somewhere else - therefore stops the pack being
 * <em>generated</em> as well as served, and nothing says so. The file simply stays as it was, and every
 * change made afterwards appears to have done nothing at all. Hours were spent on fixes that were
 * already correct and had never been built.
 * <p>
 * So when auto-hosting is off, it is built here instead. Nothing is served and nothing is sent - the
 * file is just kept current, ready to be uploaded wherever it is being hosted from.
 */
public final class PackBuilder {

    private PackBuilder() {
    }

    public static void buildIfNobodyElseWill(MinecraftServer server) {
        if (autoHostEnabled()) {
            // Auto-hosting builds it and serves it; doing it twice would only cost the time
            return;
        }

        PolymerPatcher.LOGGER.info("Auto-hosting is off, so nothing else would build the resource pack. Building it anyway - it will be at polymer/resource_pack.zip, ready to upload.");

        try {
            eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod.generateAndCall(server, false,
                message -> PolymerPatcher.LOGGER.info("{}", message.getString()),
                result -> PolymerPatcher.LOGGER.info("Resource pack built. Upload it under a new name - a client will not fetch a pack whose address it has already seen unless the name changes."));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.error("Could not build the resource pack; anything this mod adds to it will be missing on clients", e);
        }
    }

    /**
     * Whether Polymer is set to host the pack itself, read out of its own configuration file.
     * <p>
     * Read rather than asked for, because the setting lives in another mod's config and there is no
     * call for it. Unreadable is treated as on, which costs a rebuild and nothing else.
     */
    private static boolean autoHostEnabled() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("polymer").resolve("auto-host.json");
        if (!Files.exists(config)) {
            return true;
        }

        try {
            JsonObject json = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
            return !json.has("enabled") || json.get("enabled").getAsBoolean();
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read Polymer's auto-host settings; assuming it builds the pack itself", e);
            return true;
        }
    }
}
