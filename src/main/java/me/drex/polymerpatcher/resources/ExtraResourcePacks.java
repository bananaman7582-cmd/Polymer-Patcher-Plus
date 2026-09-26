package me.drex.polymerpatcher.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resource packs an admin drops into a folder, merged into the pack Polymer hosts.
 * <p>
 * Everything that reaches a vanilla client goes through the generated pack, and its assets are the
 * mods' own, so the only way an admin can hand the players a texture, language tweak or sound of
 * their own is to rebuild it all into a mod. Any zip file or directory inside the configured folder
 * is read as a pack and merged into the generated one exactly as a mod's assets would be - files
 * landing in the same place replace those already there, except the ones the game reads as a list
 * of entries, which are merged entry by entry.
 * <p>
 * The layers run: the mods' own assets, then these packs, then what the patcher generates itself -
 * so a pack here can change anything the mods ship, but never the models and definitions this mod
 * writes for its own stand-ins.
 */
public final class ExtraResourcePacks {

    private ExtraResourcePacks() {
    }

    public static void init() {
        // Registered after ResourceHelper's own listener, so these packs are merged over the mods'
        // own assets - and whatever ResourcePackGenerator rewrites later wins over both
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(ExtraResourcePacks::addAll);
    }

    private static void addAll(ResourcePackBuilder builder) {
        var resources = ConfigManager.config().resources;
        if (!resources.mergeExtraResourcePacks) {
            return;
        }

        Path folder = ConfigManager.CONFIG_DIR.resolve(resources.extraResourcePacksFolder);
        if (!Files.isDirectory(folder)) {
            // Made rather than merely missed. A folder that has to be created by hand, in a place
            // nothing names, is a feature nobody can find: this simply returned and said nothing, so
            // the only sign it existed at all was the config line naming a folder that was not there
            try {
                Files.createDirectories(folder);
                PolymerPatcher.LOGGER.info("Put resource packs in {} to have them merged into the one this server hands out", folder);
            } catch (IOException e) {
                PolymerPatcher.LOGGER.warn("Could not create the extra resource packs folder {}", folder, e);
            }
            return;
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> listing = Files.list(folder)) {
            listing.forEach(entries::add);
        } catch (IOException e) {
            PolymerPatcher.LOGGER.warn("Could not read the extra resource packs folder {}", folder, e);
            return;
        }
        // By name, so a pack that should win over another can be named to come after it
        entries.sort(Comparator.comparing(path -> path.getFileName().toString()));

        int packs = 0;
        int files = 0;
        for (Path entry : entries) {
            try {
                Map<String, byte[]> contents;
                if (Files.isDirectory(entry)) {
                    contents = readDirectory(entry);
                } else if (entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    contents = readZip(entry);
                } else {
                    continue;
                }

                packs++;
                int merged = merge(builder, contents);
                files += merged;
                if (merged == 0) {
                    PolymerPatcher.LOGGER.warn(
                        "Nothing in the extra resource pack {} could be used - a pack needs an assets folder, either at its top or one level inside it",
                        entry.getFileName());
                }
            } catch (IOException e) {
                PolymerPatcher.LOGGER.warn("Could not merge extra resource pack {}", entry.getFileName(), e);
            }
        }

        if (packs > 0) {
            PolymerPatcher.LOGGER.info("Merged {} file(s) from {} extra resource pack(s) in {}",
                files, packs, folder);
        }
    }

    private static Map<String, byte[]> readDirectory(Path root) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return contents;
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (ZipFile pack = new ZipFile(zip.toFile())) {
            var entries = pack.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = pack.getInputStream(entry)) {
                    contents.put(entry.getName().replace('\\', '/'), stream.readAllBytes());
                }
            }
        }
        return contents;
    }

    /**
     * Merges one pack in, and says how many of its files were taken.
     * <p>
     * The paths are lifted out of whatever folder they arrived in first. A pack is nearly always
     * shared as a zip of the folder rather than of its contents, so its files begin
     * {@code MyPack/assets/...} - and every one of those was being dropped for not starting with
     * {@code assets/}, without a word anywhere, which is what made this look like it did nothing.
     */
    private static int merge(ResourcePackBuilder builder, Map<String, byte[]> contents) {
        String prefix = rootPrefix(contents.keySet());
        int added = 0;

        for (var file : contents.entrySet()) {
            String path = file.getKey();
            if (!path.startsWith(prefix)) {
                continue;
            }
            path = path.substring(prefix.length());
            if (!useful(path)) {
                continue;
            }

            byte[] data = file.getValue();
            if (mergesByEntry(path)) {
                byte[] existing = builder.getDataOrSource(path);
                if (existing != null) {
                    data = mergeJson(existing, data, path);
                }
            }
            builder.addData(path, data);
            added++;
        }
        return added;
    }

    /**
     * The folder every file in a pack sits inside, which is nothing at all when the pack was zipped
     * from its own contents. Only a single wrapping folder is looked through - anything nested deeper
     * than that is not a resource pack.
     */
    private static String rootPrefix(Set<String> paths) {
        for (String path : paths) {
            if (path.startsWith("assets/")) {
                return "";
            }
        }
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash > 0 && path.startsWith("assets/", slash + 1)) {
                return path.substring(0, slash + 1);
            }
        }
        return "";
    }

    /**
     * Files the game reads as a list of entries rather than as one thing, where two packs each having
     * one are meant to end up with both.
     * <p>
     * Replacing these wholesale is worse than useless: a pack adding a single translation would take
     * away every other line the mods ship, and a pack adding one atlas source would drop the rest.
     */
    private static boolean mergesByEntry(String path) {
        return path.endsWith(".json")
            && (path.contains("/lang/") || path.endsWith("/sounds.json") || path.contains("/atlases/"));
    }

    private static byte[] mergeJson(byte[] existing, byte[] incoming, String path) {
        try {
            JsonObject before = JsonParser.parseString(new String(existing, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject after = JsonParser.parseString(new String(incoming, StandardCharsets.UTF_8)).getAsJsonObject();

            // An atlas is one keyed list; everything else here is a flat map of keys
            if (path.contains("/atlases/") && before.has("sources") && after.has("sources")) {
                JsonArray sources = new JsonArray();
                sources.addAll(before.getAsJsonArray("sources"));
                sources.addAll(after.getAsJsonArray("sources"));
                JsonObject atlas = before.deepCopy();
                atlas.add("sources", sources);
                return atlas.toString().getBytes(StandardCharsets.UTF_8);
            }

            JsonObject merged = before.deepCopy();
            after.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
            return merged.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable e) {
            // A file that will not read as JSON is taken as it is, which is what happened before anyway
            PolymerPatcher.LOGGER.debug("Could not merge {} with what is already there; the pack's own copy is used", path, e);
            return incoming;
        }
    }

    /**
     * Only assets are merged; {@code pack.mcmeta} and {@code pack.png} belong to the pack Polymer
     * writes itself. A file also needs something beneath its namespace - a bare {@code assets} marker
     * has nothing to add.
     */
    private static boolean useful(String path) {
        if (!path.startsWith("assets/")) {
            return false;
        }
        return path.indexOf('/', "assets/".length()) > 0;
    }
}
