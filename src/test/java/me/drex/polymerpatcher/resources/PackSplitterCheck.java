package me.drex.polymerpatcher.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Executable regression check for splitting a finished resource pack.
 *
 * <p>Works on a synthetic pack small enough to check quickly, laid out the way the real one is: many
 * namespaces, one namespace much larger than any fragment, an overlay folder declared in
 * {@code pack.mcmeta}, root metadata and a licenses folder. What matters is not the size but the shape,
 * so the same rules are exercised in milliseconds instead of gigabytes.</p>
 */
public final class PackSplitterCheck {

    private static final long MIB = 1024L * 1024L;

    /** Small enough to split several times over, large enough for the measurements to mean something. */
    private static final long LIMIT = 2 * MIB;

    private PackSplitterCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("polymer-patcher-split-check");
        try {
            Path source = root.resolve("resource_pack.zip");
            Map<String, byte[]> expected = buildSource(source);

            Path folder = root.resolve("split");
            PackSplitter.Result result = PackSplitter.split(source, folder, "a".repeat(40), LIMIT);
            if (!result.isSplit()) {
                throw new AssertionError("An oversized pack was not split at all");
            }
            if (result.fragments().size() < 3) {
                throw new AssertionError("Expected the pack to need several fragments, got "
                    + result.fragments().size());
            }

            // Nothing is lost, nothing is duplicated, and every fragment is a pack in its own right
            Map<String, byte[]> assembled = new HashMap<>();
            long total = 0;
            for (PackSplitter.Fragment fragment : result.fragments()) {
                if (fragment.size() > LIMIT) {
                    throw new AssertionError(fragment.fileName() + " is " + fragment.size()
                        + " bytes, over the " + LIMIT + " limit");
                }
                if (!fragment.hash().equals(sha1(fragment.path()))) {
                    throw new AssertionError(fragment.fileName() + " does not hash to the value it will be sent with");
                }
                if (fragment.files() < 2) {
                    throw new AssertionError(fragment.fileName() + " is not a valid pack: no pack.mcmeta was written");
                }
                total += fragment.size();

                Set<String> inThisFragment = new HashSet<>();
                PackSplitter.forEachEntry(fragment.path(), (path, data) -> {
                    if (!inThisFragment.add(path)) {
                        throw new AssertionError("'" + path + "' was written twice into " + fragment.fileName());
                    }
                    if (!path.equals(PackSplitter.PACK_MCMETA) && assembled.containsKey(path)) {
                        // pack.mcmeta is the one entry meant to be in every fragment - it is what makes
                        // each of them a pack - but nothing else may be split or repeated
                        throw new AssertionError("'" + path + "' appears in more than one fragment");
                    }
                    assembled.put(path, data.readAllBytes());
                });
                if (assembled.get(PackSplitter.PACK_MCMETA) == null) {
                    throw new AssertionError(fragment.fileName() + " has no " + PackSplitter.PACK_MCMETA);
                }
            }

            if (!assembled.keySet().equals(expected.keySet())) {
                Set<String> missing = new HashSet<>(expected.keySet());
                missing.removeAll(assembled.keySet());
                Set<String> extra = new HashSet<>(assembled.keySet());
                extra.removeAll(expected.keySet());
                throw new AssertionError("The fragments do not add up to the pack. Missing " + missing
                    + ", unexpected " + extra);
            }
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                if (!java.util.Arrays.equals(entry.getValue(), assembled.get(entry.getKey()))) {
                    throw new AssertionError(entry.getKey() + " came out of the split changed");
                }
            }

            // A namespace stays whole when it fits...
            assertSameFragment(result, "assets/mod3/models/item/item7.json", "assets/mod3/textures/item/item7.png");
            // ...and an overlay file stays with the file it covers, even when its namespace is cut up
            assertSameFragment(result, "assets/hugepack/textures/block/block5.png",
                "assets/hugepack/overlays/tinted/textures/block/block5.png");

            // The same input gives the same output, byte for byte
            Path again = root.resolve("split-again");
            PackSplitter.Result repeated = PackSplitter.split(source, again, "a".repeat(40), LIMIT);
            if (repeated.fragments().size() != result.fragments().size()) {
                throw new AssertionError("The same pack split into a different number of fragments the second time");
            }
            for (int i = 0; i < result.fragments().size(); i++) {
                if (!result.fragments().get(i).hash().equals(repeated.fragments().get(i).hash())) {
                    throw new AssertionError("Fragment " + i + " came out differently the second time");
                }
                if (!result.fragments().get(i).uuid().equals(repeated.fragments().get(i).uuid())) {
                    throw new AssertionError("Fragment " + i + " was given a different id the second time");
                }
            }

            // A pack that will not fit anywhere is reported rather than written out over the limit
            Path huge = root.resolve("huge.zip");
            writeZip(huge, Map.of(PackSplitter.PACK_MCMETA, metadata(), "assets/huge/textures/a.png",
                incompressible(3 * (int) MIB)));
            try {
                PackSplitter.split(huge, root.resolve("huge-split"), "b".repeat(40), LIMIT);
                throw new AssertionError("A single file over the limit was accepted");
            } catch (IOException expectedFailure) {
                if (!String.valueOf(expectedFailure.getMessage()).contains("under")) {
                    throw expectedFailure;
                }
            }

            System.out.println("Verified " + result.fragments().size() + " fragments (" + total
                + " bytes) holding every one of " + expected.size() + " original entries");
        } finally {
            deleteRecursively(root);
        }
    }

    /** Asserts that both paths were written into the same fragment. */
    private static void assertSameFragment(PackSplitter.Result result, String path, String other) throws IOException {
        for (PackSplitter.Fragment fragment : result.fragments()) {
            List<String> paths = new ArrayList<>();
            PackSplitter.forEachEntry(fragment.path(), (name, data) -> paths.add(name));
            if (paths.contains(path) && paths.contains(other)) {
                return;
            }
            if (paths.contains(path)) {
                throw new AssertionError("'" + path + "' and '" + other
                    + "' were put in different fragments (the first is in fragment " + fragment.index() + ")");
            }
        }

        throw new AssertionError("'" + path + "' is not in any fragment");
    }

    /**
     * A pack with the layout of the real thing: root metadata, a licenses folder, several namespaces of
     * ordinary size, one enormous namespace that has to be cut at file level, and an overlay folder.
     */
    private static Map<String, byte[]> buildSource(Path source) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PackSplitter.PACK_MCMETA, metadata());

        // Incompressible, so a measured size is a real size rather than an artefact of the fixture
        entries.put("pack.png", incompressible(4096));
        entries.put("licenses/third-party.txt", "Someone else's terms\n".repeat(64).getBytes(StandardCharsets.UTF_8));

        for (int mod = 0; mod < 6; mod++) {
            String namespace = "mod" + mod;
            for (int i = 0; i < 24; i++) {
                entries.put("assets/" + namespace + "/models/item/item" + i + ".json",
                    ("{\"textures\":{\"layer0\":\"" + namespace + ":item" + i + "\"}}").getBytes(StandardCharsets.UTF_8));
                entries.put("assets/" + namespace + "/textures/item/item" + i + ".png", incompressible(24 * 1024));
            }
        }

        // One namespace far larger than a fragment, so it is cut again at individual files
        for (int i = 0; i < 24; i++) {
            entries.put("assets/hugepack/textures/block/block" + i + ".png", incompressible(320 * 1024));
            entries.put("assets/hugepack/models/block/block" + i + ".json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"hugepack:block/block" + i + "\"}}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        // An overlay that covers one of the files above: it is only readable next to it, and its path mirrors
        // the covered one under the overlay directory, as the game expects
        entries.put("assets/hugepack/overlays/tinted/textures/block/block5.png", incompressible(64 * 1024));

        // Written in a shuffled order, so the split's own ordering is what puts the pack back together
        List<String> names = new ArrayList<>(entries.keySet());
        java.util.Collections.shuffle(names, new Random(20251006L));
        Map<String, byte[]> shuffled = new TreeMap<>();
        for (String name : names) {
            shuffled.put(name, entries.get(name));
        }

        writeZip(source, shuffled);
        return shuffled;
    }

    private static byte[] metadata() {
        return ("{\"pack\":{\"pack_format\":88,\"description\":\"split check\"},"
            + "\"overlays\":{\"entries\":[{\"formats\":{\"min_format\":88,\"max_format\":88},"
            + "\"directory\":\"assets/hugepack/overlays/tinted\"}]}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Random bytes, which deflate cannot shrink and so weigh what they look like. */
    private static byte[] incompressible(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static void writeZip(Path target, Map<String, byte[]> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
             ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                // The same zeroed timestamps the real pack is stamped with
                zipEntry.setTime(0);
                out.putNextEntry(zipEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static String sha1(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }

        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary directory left behind is not worth failing a build over
                }
            });
        }
    }
}