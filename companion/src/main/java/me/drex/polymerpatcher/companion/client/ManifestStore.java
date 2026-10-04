package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The manifests this client has been given, one per server, kept until the next launch can use them.
 * <p>
 * Saved under the address the player typed for the server, because that is the one thing known about
 * a server both while joining it and while the game starts up again afterwards.
 */
public final class ManifestStore {

    private ManifestStore() {
    }

    private static final String EXTENSION = ".ppm";

    /** The manifest each server's blocks were registered from this launch, by server. */
    private static final Map<String, String> REGISTERED = new ConcurrentHashMap<>();

    public record Saved(String server, Path file, byte[] bytes, String hash) {
    }

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("polymer-patcher-client");
    }

    /** A server's address as a file name: case and anything a file system dislikes taken out. */
    public static String keyOf(String address) {
        return address.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /** Every saved manifest, newest first, so a server joined recently wins any disagreement. */
    public static List<Saved> all() {
        List<Saved> found = new ArrayList<>();
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                .sorted(Comparator.comparing(ManifestStore::modified).reversed()).toList()) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    String name = file.getFileName().toString();
                    found.add(new Saved(name.substring(0, name.length() - EXTENSION.length()), file, bytes,
                        CompanionManifest.hashOf(bytes)));
                } catch (IOException e) {
                    CompanionMod.LOGGER.warn("Could not read the saved blocks in {}", file, e);
                }
            }
        } catch (IOException e) {
            CompanionMod.LOGGER.warn("Could not list the saved server blocks in {}", directory, e);
        }
        return found;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The hash of the manifest saved for a server, whether or not it was registered, or null. */
    public static @Nullable String savedHash(String server) {
        Path file = directory().resolve(server + EXTENSION);
        try {
            return Files.isRegularFile(file) ? CompanionManifest.hashOf(Files.readAllBytes(file)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static void save(String server, byte[] bytes) throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        Path temp = directory.resolve(server + EXTENSION + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, directory.resolve(server + EXTENSION), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void markRegistered(String server, String hash) {
        REGISTERED.put(server, hash);
    }

    /** The manifest this server's blocks were registered from at startup, or null. */
    public static @Nullable String registeredHash(String server) {
        return REGISTERED.get(server);
    }
}
