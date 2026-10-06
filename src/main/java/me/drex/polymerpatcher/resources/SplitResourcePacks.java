package me.drex.polymerpatcher.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.autohost.api.AutoHostUtils;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.autohost.impl.AutoHost;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sends an oversized generated resource pack as several packs instead of one.
 *
 * <p>{@link PackSplitter} does the cutting. This decides when it happens, hosts the pieces, and gets
 * them to clients.</p>
 *
 * <h2>When the pack is finished, not when a player joins</h2>
 * <p>The split works off the file Polymer has finished writing, so it runs on the same background thread
 * the pack itself was built on and never on the server thread. A player who arrives while it is running
 * waits, exactly as they already wait while the pack is generating.</p>
 *
 * <h2>How the pieces reach a client</h2>
 * <p>Polymer already has everything needed for several packs at once and no client mod: its own collector
 * event is where the one pack is added, so each fragment is added the same way, through whichever
 * provider the admin configured - which is what makes this project's per-client addressing work for
 * fragments too. Every fragment is required on the same terms the single pack was, and Polymer's
 * configuration task holds the player at the loading screen until all of them have come back.</p>
 *
 * <h2>Standing in for the single pack</h2>
 * <p>The one thing an event cannot do is take the original pack away, so that is a single small mixin
 * into Polymer: once fragments are in place the main pack is no longer added, and the admin's own
 * external packs are still sent behind them exactly as before.</p>
 *
 * <h2>Not rebuilding what is already right</h2>
 * <p>Splitting a large pack is real work, and most restarts do not change it at all. The pack's own hash
 * and size are written down beside the pieces, so an unchanged pack is left alone and its pieces are
 * simply registered for hosting again. A changed pack replaces every piece and removes the ones no
 * longer part of it.</p>
 */
public final class SplitResourcePacks {

    /**
     * The largest a single server resource pack may be, which is the ceiling this works inside.
     * <p>Vanilla refuses anything above it, so a limit configured at or over it is unreachable and is
     * quietly brought back inside.
     */
    public static final int VANILLA_LIMIT_MIB = 250;

    /** Small enough to be worth nothing; a limit below this is treated as a mistake. */
    public static final int MINIMUM_LIMIT_MIB = 8;

    private static final String STATE_FILE = "split-state.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The fragments in force, published on the server thread so a connection reading them and the
     * pack builder writing them cannot see a half-built set.
     */
    private static volatile List<Fragment> fragments = List.of();

    /**
     * Whether {@link #fragments} stand in for the single pack.
     * <p>Kept apart from whether any fragments exist on purpose: a split that failed leaves the original
     * pack to be sent exactly as it always was, which is no worse than what happened before.
     */
    private static volatile boolean replacing = false;

    /** Whether the packs this server hands out can be built from what is on disk right now. */
    private static volatile boolean ready = false;

    @Nullable
    private static MinecraftServer server;

    private SplitResourcePacks() {
    }

    public static void init() {
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(SplitResourcePacks::onPackFinished);
        // Polymer answers "are the packs ready" for every listener, so a fragment set that is still
        // being written holds a joining player at the same screen as one waiting on the pack itself
        AutoHostUtils.SEND_RESOURCE_PACK_COLLECTOR.register(SplitResourcePacks::collectPacks);
        AutoHostUtils.RESOURCE_PACKS_READY.register((provider, context) -> ready);
        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
    }

    /**
     * Whether the fragments are what clients are sent in place of the single generated pack.
     * <p>Read by the mixin that stops Polymer adding that pack as well. False - and so no change to
     * anything at all - whenever splitting is off, the pack fitted, or the split did not work.
     */
    public static boolean replacesMainPack() {
        return replacing && !fragments.isEmpty();
    }

    /** How many packs a client is being sent right now, for diagnostics. */
    public static int fragmentCount() {
        return fragments.size();
    }

    /**
     * The pack has been written. Whether it can be sent as it is, and if it cannot, cutting it up.
     * <p>Runs on the pack builder's own thread, never the server's, and hands the heavy work further
     * off to the I/O pool regardless.
     */
    private static void onPackFinished(Object value) {
        if (!(value instanceof OutputGenerator.Result result) || result.path() == null) {
            if (value == null) {
                PolymerPatcher.LOGGER.warn("The resource pack could not be generated; it will be sent as a single pack");
            }
            drop();
            return;
        }

        Path pack = result.path();
        long size;
        try {
            size = Math.max(Files.size(pack), 0);
        } catch (IOException e) {
            PolymerPatcher.LOGGER.warn("Could not weigh the generated resource pack; it will be sent unsplit", e);
            drop();
            return;
        }

        int limitMib = limitMib();
        if (limitMib <= 0) {
            drop();
            return;
        }

        PolymerPatcher.LOGGER.info("Generated resource pack is {} ({})", describe(size),
            result.hash() == null ? "SHA-1 unknown" : "SHA-1 " + result.hash());

        if (size <= limitMib * 1024L * 1024L) {
            PolymerPatcher.LOGGER.info("That is within the {} MiB limit, so it is sent as a single pack", limitMib);
            drop();
            return;
        }

        if (!PackBuilder.autoHostEnabled()) {
            // Nothing is served from here, so cutting the pack up would only write files nobody asks
            // for. It is still written whole, exactly where it always was.
            PolymerPatcher.LOGGER.info(
                "Polymer is not hosting the pack, so the oversized pack at {} is left whole for uploading", pack);
            drop();
            return;
        }

        PolymerPatcher.LOGGER.info("{} is over the {} MiB limit, so it is being split into several packs",
            describe(size), limitMib);
        ready = false;
        Util.ioPool().execute(() -> rebuild(result, pack, size, limitMib));
    }

    /** Cuts the pack up off the server thread and publishes the result on it. */
    private static void rebuild(OutputGenerator.Result result, Path pack, long sourceSize, int limitMib) {
        Path folder = splitFolder(pack);
        String sourceHash = result.hash() == null ? "" : result.hash();
        long limit = limitMib * 1024L * 1024L;

        try {
            List<Fragment> existing = reusable(folder, sourceHash, sourceSize, limitMib);
            if (existing != null) {
                PolymerPatcher.LOGGER.info(
                    "The generated pack has not changed, so the {} split pack(s) from the last run are reused",
                    existing.size());
                publish(existing);
                return;
            }

            long started = System.nanoTime();
            PackSplitter.Result split = PackSplitter.split(pack, folder, sourceHash, limit);
            long took = (System.nanoTime() - started) / 1_000_000L;

            List<Fragment> built = new ArrayList<>(split.fragments().size());
            for (PackSplitter.Fragment fragment : split.fragments()) {
                built.add(new Fragment(fragment.index(), identifierFor(fragment.index()), fragment.path(),
                    fragment.hash(), fragment.uuid(), fragment.size(), fragment.files()));
            }

            writeState(folder, sourceHash, sourceSize, limitMib, built);
            PolymerPatcher.LOGGER.info("Split it into {} pack(s) in {} ms; together they hold exactly what the one pack did",
                built.size(), took);
            publish(built);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.error("Could not split the oversized resource pack. It will be sent as a single pack, "
                + "which a vanilla client will refuse - lower resources.maxSplitPackSizeMiB, or leave more out of the pack.", e);
            drop();
        }
    }

    /**
     * Makes the fragments the packs clients are sent, and hands them to Polymer to host.
     * <p>On the server thread, because that is where the hosting table is read and written.
     */
    private static void publish(List<Fragment> built) {
        Consumer<Runnable> run = task -> {
            MinecraftServer active = server;
            if (active != null) {
                active.execute(task);
            } else {
                task.run();
            }
        };

        run.accept(() -> {
            for (Fragment fragment : built) {
                // One id per index, so a rebuild replaces the file behind an address rather than
                // leaving the server advertising one that no longer means anything
                AutoHostUtils.registerHostedFile(fragment.identifier(), fragment.path());
                PolymerPatcher.LOGGER.info("Split pack {} of {}: {}, {} file(s), SHA-1 {}, hosted as {}",
                    fragment.index() + 1, built.size(), describe(fragment.size()), fragment.files(),
                    fragment.hash(), AutoHostUtils.getPathFromId(fragment.identifier()));
            }

            fragments = List.copyOf(built);
            replacing = true;
            ready = true;
        });
    }

    /** Goes back to sending the generated pack as one file, which is what always happened. */
    private static void drop() {
        ready = true;
        fragments = List.of();
        replacing = false;
    }

    /**
     * Adds this server's pack to the list of packs a joining client is sent.
     * <p>All on Polymer's own path: the fragments go out through the configured provider, so they get
     * the same address treatment, the same required setting and the same hash-in-file-name handling the
     * single pack did. The admin's own external packs follow, in the order they used to, so they still
     * sit below the generated content rather than above it.
     */
    private static void collectPacks(ResourcePackDataProvider provider, PacketContext context,
                                     Consumer<MinecraftServer.ServerResourcePackInfo> consumer) {
        List<Fragment> current = fragments;
        if (!replacesMainPack() || current.isEmpty()) {
            return;
        }

        for (Fragment fragment : current) {
            try {
                consumer.accept(provider.createProperties(context, fragment.uuid(), fragment.identifier(),
                    fragment.hash()));
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.error("Could not work out an address for split pack {}; falling back to the single pack",
                    fragment.index() + 1, e);
                drop();
                return;
            }
        }

        AutoHost.GLOBAL_RESOURCE_PACKS.forEach(consumer);
    }

    /** The configured ceiling in MiB, or 0 to leave the pack whole. */
    private static int limitMib() {
        var resources = ConfigManager.config().resources;
        if (!resources.autoSplitLargePacks) {
            PolymerPatcher.LOGGER.info(
                "An oversized resource pack will not be split (resources.autoSplitLargePacks is off); it is sent as one file");
            return 0;
        }

        int configured = resources.maxSplitPackSizeMiB;
        if (configured >= VANILLA_LIMIT_MIB) {
            PolymerPatcher.LOGGER.warn("resources.maxSplitPackSizeMiB is {} MiB, which a client will not accept; using {} MiB",
                configured, VANILLA_LIMIT_MIB - 20);
            return VANILLA_LIMIT_MIB - 20;
        }

        if (configured < MINIMUM_LIMIT_MIB) {
            PolymerPatcher.LOGGER.warn("resources.maxSplitPackSizeMiB is {} MiB, which is not usable; using {} MiB",
                configured, MINIMUM_LIMIT_MIB);
            return MINIMUM_LIMIT_MIB;
        }

        return configured;
    }

    /** The pieces of {@code pack}, written beside it so they are cleaned up together with it. */
    private static Path splitFolder(Path pack) {
        Path absolute = pack.toAbsolutePath();
        Path parent = absolute.getParent();
        return (parent == null ? absolute : parent).resolve("split");
    }

    /** The address name a fragment is hosted under. */
    private static Identifier identifierFor(int index) {
        return Identifier.fromNamespaceAndPath(PolymerPatcher.MOD_ID,
            "split_" + String.format(Locale.ROOT, "%02d", index));
    }

    private static String describe(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0d * 1024.0d));
    }

    /**
     * The fragments from the last run, when they are still exactly what this pack needs.
     * <p>Returns null when they have to be written again: a different pack, a different ceiling, a piece
     * that has gone missing, or one whose size says it is not what it was.
     */
    @Nullable
    private static List<Fragment> reusable(Path folder, String sourceHash, long sourceSize, int limitMib) {
        Path state = folder.resolve(STATE_FILE);
        if (!Files.isRegularFile(state)) {
            return null;
        }

        try {
            JsonObject json = JsonParser.parseString(Files.readString(state, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!sourceHash.equals(read(json, "sourceHash"))
                || limitMib != json.get("limitMiB").getAsInt()
                || sourceSize != json.get("sourceSize").getAsLong()) {
                return null;
            }

            List<Fragment> kept = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("fragments")) {
                JsonObject entry = element.getAsJsonObject();
                int index = entry.get("index").getAsInt();
                Path file = folder.resolve(read(entry, "file"));
                if (!Files.isRegularFile(file) || Math.max(Files.size(file), 0) != entry.get("size").getAsLong()) {
                    return null;
                }

                kept.add(new Fragment(index, identifierFor(index), file, read(entry, "hash"),
                    UUID.fromString(read(entry, "uuid")), entry.get("size").getAsLong(),
                    entry.get("files").getAsInt()));
            }

            return kept.isEmpty() ? null : List.copyOf(kept);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read the record of the previous split; it will be written again", e);
            return null;
        }
    }

    /** What is on disk now, so a later run can tell whether it is still what this pack needs. */
    private static void writeState(Path folder, String sourceHash, long sourceSize, int limitMib,
                                   List<Fragment> built) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("sourceHash", sourceHash);
        json.addProperty("limitMiB", limitMib);
        json.addProperty("sourceSize", sourceSize);

        JsonArray array = new JsonArray();
        for (Fragment fragment : built) {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", fragment.index());
            entry.addProperty("file", fragment.path().getFileName().toString());
            entry.addProperty("hash", fragment.hash());
            entry.addProperty("uuid", fragment.uuid().toString());
            entry.addProperty("size", fragment.size());
            entry.addProperty("files", fragment.files());
            array.add(entry);
        }
        json.add("fragments", array);

        Files.writeString(folder.resolve(STATE_FILE), GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private static String read(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** One hosted pack, and everything needed to describe it to a client. */
    private record Fragment(int index, Identifier identifier, Path path, String hash, UUID uuid, long size, int files) {
    }
}
