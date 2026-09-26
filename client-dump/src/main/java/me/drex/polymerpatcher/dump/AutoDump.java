package me.drex.polymerpatcher.dump;

import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.dump.data.RenderRegistryStorage;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Takes the dump on its own, the first time a world is open to take it in.
 * <p>
 * Everything the server needs to draw a modded mob is decided on the client: which renderer belongs to
 * which mob, what its model layers bake to, which textures it wears. None of that exists on a
 * dedicated server, which is why it has to be read here and carried across.
 * <p>
 * Reading it needs a world, though - a mob has to be built before it can be asked to draw itself - so
 * this waits for one rather than running at startup. Opening any single-player world is enough, and
 * the world is only borrowed: mobs are built, asked, and dropped without ever being put in it.
 * <p>
 * It only runs when it has something new to learn. A dump that already covers every mod installed is
 * left alone, so opening a world stays as quick as it was; adding a mod is what makes it run again.
 */
public final class AutoDump {
    /**
     * Whether this has already run this session. A world can be opened and closed repeatedly and
     * nothing about the answers changes in between.
     */
    private static boolean done;

    private AutoDump() {
    }

    public static void onServerStarted(@NotNull MinecraftServer server) {
        if (done) {
            return;
        }

        done = true;

        Set<String> missing = missingFromExistingDump();
        if (missing.isEmpty()) {
            PolymerPatcherDumper.LOGGER.info("Dump at {} already covers every installed mod; not taking another",
                RenderRegistryStorage.existingDumpFile());
            return;
        }

        PolymerPatcherDumper.LOGGER.info("Taking a render dump; nothing on file covers {}", missing);
        run(server.overworld(), true);
    }

    /**
     * Takes the dump and writes it out.
     *
     * @param announce whether to say so in chat - worth doing when nobody asked for it, since the
     *                 player has no other way to know it happened or that they can close the world
     * @return whether it was written
     */
    public static boolean run(@NotNull ServerLevel level, boolean announce) {
        PolymerPatcherDumper.LOGGER.info("Dumping render registry...");

        RenderRegistry registry;
        try {
            registry = RenderRegistryGenerator.generate(level);
            registry.namespaces = RenderRegistry.currentNamespaces();
            RenderRegistryStorage.save(registry);
        } catch (IOException | RuntimeException e) {
            PolymerPatcherDumper.LOGGER.error("Failed to save render registry", e);
            if (announce) {
                say(Component.literal("[Polymer Patcher++] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Failed to write the render dump; see the log.")
                        .withStyle(ChatFormatting.RED)));
            }
            return false;
        }

        PolymerPatcherDumper.LOGGER.info("Render registry dumped to {} and {}",
            RenderRegistryStorage.configDumpFile(), RenderRegistryStorage.sharedDumpFile());

        if (announce) {
            announce(registry);
        }

        return true;
    }

    /**
     * The namespaces the dump on file knows nothing about, or every one of them when there is no dump.
     */
    @NotNull
    private static Set<String> missingFromExistingDump() {
        Path path = RenderRegistryStorage.existingDumpFile();
        if (!Files.exists(path)) {
            return RenderRegistry.currentNamespaces();
        }

        try {
            RenderRegistry existing = RenderRegistryStorage.load(path);
            if (!existing.isCurrentFormat()) {
                // Namespace coverage alone cannot reveal that an older dump predates a newly captured
                // kind of rendering data. Re-take it once when the schema changes.
                return RenderRegistry.currentNamespaces();
            }
            return existing.missingNamespaces();
        } catch (IOException | RuntimeException e) {
            // A dump that cannot be read is worth no more than none at all
            PolymerPatcherDumper.LOGGER.warn("Could not read the dump at {}; taking another", path, e);
            return RenderRegistry.currentNamespaces();
        }
    }

    /**
     * Tells the player what was read and that they are free to leave again.
     */
    private static void announce(@NotNull RenderRegistry registry) {
        say(Component.literal("[Polymer Patcher++] ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal("Read " + registry.entityData.size() + " modded entity renderer(s) across "
                    + registry.namespaces.size() + " mod(s).")
                .withStyle(ChatFormatting.GREEN)));

        say(Component.literal("[Polymer Patcher++] ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal("Saved. You can close this world and start the server.")
                .withStyle(ChatFormatting.GRAY)));
    }

    /**
     * Puts a line in the player's chat, or in the log when there is nobody standing there yet.
     */
    private static void say(@NotNull Component message) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null) {
                client.player.sendSystemMessage(message);
                return;
            }
        } catch (Throwable t) {
            // The chat is a convenience; the log below carries the same thing
        }

        PolymerPatcherDumper.LOGGER.info(message.getString());
    }
}
