package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Cuts a finished resource pack into several packs small enough for a client to accept.
 *
 * <p>Vanilla allows several server resource packs at once and refuses a single one above a fixed size.
 * A heavily modded server's generated pack outgrew that size - it was 343 MB - and the game's answer to
 * that was to give every player vanilla stand-ins for everything, with nothing saying why. So the pack
 * is cut up and all the pieces are pushed instead.</p>
 *
 * <h2>Cut at resource-file boundaries, never at byte offsets</h2>
 * <p>Splitting the zip's bytes would produce archives that are not archives: the tail of a fragment has
 * no central directory, and the head of one ends mid-entry. A client rejects both. So nothing here ever
 * looks at bytes as bytes - every fragment is rebuilt entry by entry from the source archive, and each
 * one is a complete, independently loadable resource pack.</p>
 *
 * <h2>Namespace first, files when a namespace will not fit</h2>
 * <p>The first split is by namespace, because a namespace is what a client resolves against and a
 * whole one landing in one fragment keeps a mod's assets together. A single namespace is not assumed to
 * fit: an enormous one - a mod with a hundred megabytes of textures - is cut again at individual files,
 * which is still a file boundary and still valid.</p>
 *
 * <h2>Measured, not assumed</h2>
 * <p>A fragment is measured by its real compressed size on disk before it is accepted, and a fragment
 * that came out over the limit is repartitioned with a smaller target until it is not. If it cannot be
 * brought under the limit at all, the split is abandoned and the original single pack is used, because a
 * set of fragments no client can accept is worse than the one pack that already worked.</p>
 *
 * <h2>Order is fixed</h2>
 * <p>Entries are ordered by their path and grouped in a fixed sequence, and timestamps are zeroed exactly
 * as Polymer zeroes them, so the same input always produces byte-identical fragments. Every resource path
 * lands in exactly one fragment, so the fragments together are the original pack - which is why nothing
 * about the result looks or behaves differently from the single pack it replaced.</p>
 */
public final class PackSplitter {

    /** Every fragment is a resource pack in its own right, and this is what makes it one. */
    public static final String PACK_MCMETA = "pack.mcmeta";

    /** Prefix of the files this writes, so old fragments can be recognised and removed. */
    public static final String FRAGMENT_PREFIX = "fragment-";

    public static final String FRAGMENT_SUFFIX = ".zip";

    /**
     * What a zip entry costs on top of its own bytes: a local header, a central directory record and
     * the name, twice. Being generous here is free - the real size is measured afterwards - while being
     * stingy means a fragment that looked small enough came out over the limit.
     */
    private static final long ENTRY_OVERHEAD = 256L;

    /** The pack is sealed below this share of the limit, to absorb a wrong first estimate. */
    private static final double FIRST_FILL = 0.97d;

    /** How much of the limit is still aimed for after a fragment came out too large. */
    private static final double RETRY_FILL = 0.85d;

    /** How many times a fragment set is written before giving up and keeping the single pack. */
    private static final int MAX_ATTEMPTS = 6;

    /** How far nested overlay directories are unwound, which no pack a client accepts comes near. */
    private static final int MAX_OVERLAY_DEPTH = 8;

    private static final String ASSETS = "assets";

    private PackSplitter() {
    }

    /**
     * One pack a client can be sent on its own.
     *
     * @param uuid the id it is pushed under, derived from its own content so an unchanged fragment
     *             keeps the id the client already has and an altered one is fetched again
     */
    public record Fragment(int index, String fileName, Path path, String hash, UUID uuid, long size, int files) {
    }

    /**
     * @param fragments empty when the pack was left as it was, which is the case when it was small
     *                  enough, or when splitting could not be made to work
     */
    public record Result(String sourceHash, long sourceSize, List<Fragment> fragments) {

        public boolean isSplit() {
            return !this.fragments.isEmpty();
        }
    }

    /** An overlay file, kept with the entry it covers because a client cannot read it otherwise. */
    private record Satellite(String path, long size) {
        long total() {
            return this.size + ENTRY_OVERHEAD;
        }
    }

    /** One resource file, together with any overlay entries that belong to it. */
    private record Unit(String path, List<Satellite> satellites, long size) {
        long total() {
            long sum = this.size;
            for (Satellite satellite : this.satellites) {
                sum += satellite.total();
            }
            return sum;
        }
    }

    /** Every file of one namespace, or of everything else at the top of the pack. */
    private static final class Group {
        private final List<Unit> units = new ArrayList<>();
        private long size;

        private void add(Unit unit) {
            this.units.add(unit);
            this.size += unit.total();
        }
    }

    /**
     * Splits {@code source} into as few packs as possible, none of them larger than {@code limitBytes}.
     *
     * @param limitBytes the ceiling for a fragment's real compressed size
     * @throws IOException if the source cannot be read or the fragments cannot be written
     */
    public static Result split(Path source, Path folder, String sourceHash, long limitBytes) throws IOException {
        Files.createDirectories(folder);

        byte[] meta;
        Set<String> overlayDirectories;
        List<Group> groups;

        try (ZipFile zip = new ZipFile(source.toFile())) {
            meta = readMetadata(zip);
            if (meta == null) {
                // No pack.mcmeta means the source is not a resource pack, and nothing produced from
                // it would be one either
                throw new IOException(source.getFileName() + " has no " + PACK_MCMETA);
            }
            overlayDirectories = readOverlayDirectories(meta);
            groups = group(zip, overlayDirectories);
        }

        double fill = FIRST_FILL;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long target = (long) (limitBytes * fill);
            List<List<Unit>> partition = partition(groups, Math.max(target, ENTRY_OVERHEAD));
            List<Fragment> fragments = write(source, folder, meta, partition, sourceHash);

            List<Fragment> tooLarge = fragments.stream().filter(f -> f.size() > limitBytes).toList();
            if (tooLarge.isEmpty()) {
                // Only once the new set is known good: a failed run leaves whatever was there before,
                // because the previous fragments are still the right answer for a pack that has not
                // changed yet and a half-written set never is
                removeStaleFragments(folder, fragments.size());
                return new Result(sourceHash, sizeOf(source), List.copyOf(fragments));
            }

            if (attempt == MAX_ATTEMPTS) {
                throw new IOException("could not bring every fragment under " + limitBytes
                    + " bytes; still too large: " + tooLarge.stream().map(Fragment::fileName).toList());
            }
            fill *= RETRY_FILL;
        }

        throw new IOException("unreachable");
    }

    /** The {@code pack.mcmeta} as written, copied into every fragment so each is a valid pack. */
    private static byte[] readMetadata(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(PACK_MCMETA);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * The overlay folders the finished pack declares, taken from its own metadata.
     * <p>
     * An overlay file is only readable to a client if the file it covers is in the same pack, so these
     * are never grouped by where they sit but by the entry underneath them, and travel with it wherever
     * that goes.
     */
    private static Set<String> readOverlayDirectories(byte[] meta) {
        Set<String> directories = new HashSet<>();
        try {
            JsonObject root = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject overlays = root.has("overlays") && root.get("overlays").isJsonObject()
                ? root.getAsJsonObject("overlays") : null;
            if (overlays == null || !overlays.has("entries") || !overlays.get("entries").isJsonArray()) {
                return directories;
            }

            for (JsonElement entry : overlays.getAsJsonArray("entries")) {
                if (!entry.isJsonObject()) {
                    continue;
                }

                // The game's own name for the folder is "directory"; some pack tools have written it
                // as "overlay", so both are read rather than only the one the game uses
                JsonObject holder = entry.getAsJsonObject();
                JsonElement folder = holder.get("directory");
                if (folder == null) {
                    folder = holder.get("overlay");
                }
                if (folder != null && folder.isJsonPrimitive()) {
                    directories.add(folder.getAsString().replace('\\', '/').replaceAll("^/+|/+$", ""));
                }
            }
        } catch (Throwable unreadable) {
            // Metadata this cannot be read is still usable as it is; only the co-location of overlays
            // is lost, and only for a pack that declares them
        }
        return directories;
    }

    /**
     * The entry an overlay file covers, or the path itself when it is not an overlay.
     * <p>
     * An overlay file is only readable to a client if the file it covers is in the same pack, so the
     * two are never grouped by where they sit but by the entry underneath them, and travel with it
     * wherever that goes. The overlay directory is taken off the front and the namespace put back, so
     * {@code assets/ns/overlays/tinted/textures/block/x.png} is recognised as covering
     * {@code assets/ns/textures/block/x.png}. One overlay directory inside another is unwound the same
     * way, up to a depth no real pack reaches.
     */
    private static String basePath(String path, Set<String> overlayDirectories) {
        String current = path;
        for (int depth = 0; depth < MAX_OVERLAY_DEPTH; depth++) {
            String covered = overlayBase(current, overlayDirectories);
            if (covered == null) {
                return current;
            }
            current = covered;
        }
        return current;
    }

    /** What one overlay file covers, or null when it is not sitting in an overlay directory. */
    private static String overlayBase(String path, Set<String> overlayDirectories) {
        for (String directory : overlayDirectories) {
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            if (!path.startsWith(prefix)) {
                continue;
            }

            String rest = path.substring(prefix.length());
            String namespace = namespaceOfOverlay(directory);
            return namespace == null ? rest : "assets/" + namespace + "/" + rest;
        }
        return null;
    }

    /** The namespace an overlay directory belongs to, as in {@code hugepack} for {@code assets/hugepack/overlays/tinted}. */
    private static String namespaceOfOverlay(String directory) {
        if (!directory.startsWith(ASSETS + "/")) {
            return null;
        }
        int slash = directory.indexOf('/', ASSETS.length() + 1);
        return slash < 0 ? null : directory.substring(ASSETS.length() + 1, slash);
    }

    /**
     * What a resource is grouped by, which decides what is kept together in a fragment.
     * <p>
     * A namespace is the unit that matters to a client, so it is the unit kept whole. Everything outside
     * {@code assets/} - the pack's own files and its licenses - is grouped separately, and sorts first,
     * so the metadata ends up in the first fragment rather than being scattered.
     */
    private static String groupKey(String basePath) {
        int first = basePath.indexOf('/');
        if (first < 0) {
            return "_root";
        }
        String top = basePath.substring(0, first);
        int second = basePath.indexOf('/', first + 1);
        if ((top.equals("assets") || top.equals("data")) && second > 0) {
            return top + "/" + basePath.substring(first + 1, second);
        }
        return "_" + top;
    }

    /**
     * The whole pack, in a fixed order and grouped, with sizes taken from the archive's own directory.
     * <p>
     * Nothing is decompressed here. A zip's central directory records what every entry compressed to, and
     * these fragments are written from those same bytes at the same deflate level, so it is the size each
     * one will really have - which is what makes it worth partitioning on.
     */
    private static List<Group> group(ZipFile zip, Set<String> overlayDirectories) {
        Map<String, List<Satellite>> satellites = new HashMap<>();

        List<String> overlayPaths = new ArrayList<>();
        List<String> ordinaryPaths = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || entry.getName().isEmpty()) {
                continue;
            }
            String path = entry.getName().replace('\\', '/');
            if (path.equals(PACK_MCMETA)) {
                // Written separately into every fragment, so it is left out of the grouping: it would
                // otherwise be copied a second time, into whichever fragment happened to hold it
                continue;
            }
            if (basePath(path, overlayDirectories).equals(path)) {
                ordinaryPaths.add(path);
            } else {
                overlayPaths.add(path);
            }
        }

        // Both lists sorted first, so the order entries are visited in - and therefore the order
        // fragments are assembled in - is the same on every run
        ordinaryPaths.sort(Comparator.naturalOrder());
        overlayPaths.sort(Comparator.naturalOrder());

        List<String> known = List.copyOf(ordinaryPaths);
        for (String path : overlayPaths) {
            String base = basePath(path, overlayDirectories);
            ZipEntry entry = zip.getEntry(path);
            long size = entry == null ? 0 : Math.max(entry.getCompressedSize(), 0);
            satellites.computeIfAbsent(known.contains(base) ? base : path, key -> new ArrayList<>())
                .add(new Satellite(path, size));
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (String path : ordinaryPaths) {
            ZipEntry entry = zip.getEntry(path);
            long size = entry == null ? 0 : Math.max(entry.getCompressedSize(), 0);
            groups.computeIfAbsent(groupKey(path), key -> new Group())
                .add(new Unit(path, List.copyOf(satellites.getOrDefault(path, List.of())), size + ENTRY_OVERHEAD));
        }
        // An overlay with nothing under it is still a resource file and is still published
        for (String path : overlayPaths) {
            if (!known.contains(basePath(path, overlayDirectories))) {
                ZipEntry entry = zip.getEntry(path);
                long size = entry == null ? 0 : Math.max(entry.getCompressedSize(), 0);
                groups.computeIfAbsent(groupKey(basePath(path, overlayDirectories)), key -> new Group())
                    .add(new Unit(path, List.of(), size + ENTRY_OVERHEAD));
            }
        }

        List<Group> ordered = new ArrayList<>(groups.values());
        for (Group group : ordered) {
            group.units.sort(Comparator.comparing(Unit::path));
        }
        ordered.sort(Comparator.comparing(g -> groupKey(g.units.getFirst().path())));
        return ordered;
    }

    /**
     * Fills packs in group order, starting a new one whenever the next group would not fit.
     * <p>
     * A group too large to fit anywhere is dealt with one file at a time rather than being given a pack
     * of its own and overflowing it. A single file larger than the limit cannot be made any smaller - it
     * is copied whole, and {@link #split} will find out and say so.
     */
    private static List<List<Unit>> partition(List<Group> groups, long limit) {
        List<List<Unit>> fragments = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        long currentSize = 0;

        for (Group group : groups) {
            if (!current.isEmpty() && currentSize + group.size > limit) {
                fragments.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }

            if (group.size <= limit) {
                current.addAll(group.units);
                currentSize += group.size;
                continue;
            }

            for (Unit unit : group.units) {
                if (!current.isEmpty() && currentSize + unit.total() > limit) {
                    fragments.add(current);
                    current = new ArrayList<>();
                    currentSize = 0;
                }
                current.add(unit);
                currentSize += unit.total();
            }
        }

        if (!current.isEmpty() || fragments.isEmpty()) {
            fragments.add(current);
        }
        return fragments;
    }

    /** Rebuilds every fragment as a standalone pack and measures what came out. */
    private static List<Fragment> write(Path source, Path folder, byte[] meta, List<List<Unit>> partition,
                                        String sourceHash) throws IOException {
        List<Fragment> fragments = new ArrayList<>(partition.size());

        try (ZipFile zip = new ZipFile(source.toFile())) {
            for (int index = 0; index < partition.size(); index++) {
                String fileName = FRAGMENT_PREFIX + String.format(Locale.ROOT, "%02d", index) + FRAGMENT_SUFFIX;
                Path target = folder.resolve(fileName);
                int files = 1;

                try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                    65536))) {

                    // First, so that pack.mcmeta is the first thing in the archive, as it is in the
                    // pack it came from
                    writeEntry(out, PACK_MCMETA, meta);

                    for (Unit unit : partition.get(index)) {
                        copy(zip, out, unit.path());
                        files++;
                        for (Satellite satellite : unit.satellites) {
                            copy(zip, out, satellite.path());
                            files++;
                        }
                    }
                }

                long size = sizeOf(target);
                String hash = sha1(target);
                fragments.add(new Fragment(index, fileName, target, hash, uuidFor(sourceHash, index, hash), size, files));
            }
        }

        return fragments;
    }

    private static void copy(ZipFile zip, ZipOutputStream out, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return;
        }
        out.putNextEntry(entry(entry));
        try (InputStream in = zip.getInputStream(entry)) {
            in.transferTo(out);
        }
        out.closeEntry();
    }

    private static void writeEntry(ZipOutputStream out, String path, byte[] data) throws IOException {
        out.putNextEntry(entry(new ZipEntry(path)));
        out.write(data);
        out.closeEntry();
    }

    /** Entries are stamped with the same zeroed time Polymer stamps them with, so runs match. */
    private static ZipEntry entry(ZipEntry entry) {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(0);
        return copy;
    }

    /**
     * An id derived from what the fragment contains.
     * <p>
     * The client fetches a pack again when the id or the hash changes, and keeps the one it has when
     * neither does. Deriving both from the content means a server restart with an unchanged pack asks
     * for nothing, and a rebuilt one with one changed file is fetched in full, as it has to be.
     */
    private static UUID uuidFor(String sourceHash, int index, String fragmentHash) {
        String name = "polymer-patcher:split-packs:" + sourceHash + ':' + index + ':' + fragmentHash;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** What the fragment really weighs on disk, which is what the limit is about. */
    private static long sizeOf(Path path) throws IOException {
        return Math.max(Files.size(path), 0);
    }

    /** The SHA-1 the client checks a downloaded pack against, in the form it expects. */
    private static String sha1(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable, so a fragment hash cannot be produced", e);
        }

        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(path)) {
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

    /**
     * Removes fragments left behind by an earlier run.
     * <p>
     * Only files this class writes are touched, matched by its own naming, and only ever after a
     * successful split - so a run that fails leaves the previous fragments exactly as they were.
     */
    public static void removeStaleFragments(Path folder, int keep) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }

        try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder,
            FRAGMENT_PREFIX + "*" + FRAGMENT_SUFFIX)) {

            for (Path candidate : listing) {
                String name = candidate.getFileName().toString();
                int index = indexOf(name);
                if (index >= keep) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    /** The number a fragment file name carries, or -1 when it is not one of ours. */
    public static int indexOf(String fileName) {
        if (!fileName.startsWith(FRAGMENT_PREFIX) || !fileName.endsWith(FRAGMENT_SUFFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(fileName.substring(FRAGMENT_PREFIX.length(),
                fileName.length() - FRAGMENT_SUFFIX.length()));
        } catch (NumberFormatException notOurs) {
            return -1;
        }
    }

    /** A sink for one entry of a pack, given its path and its bytes. */
    @FunctionalInterface
    public interface EntrySink {
        void accept(String path, InputStream data) throws IOException;
    }

    /** Reads every file of a pack, in the order the archive holds them. */
    public static void forEachEntry(Path source, EntrySink sink) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    sink.accept(entry.getName().replace('\\', '/'), in);
                }
            }
        }
    }
}
