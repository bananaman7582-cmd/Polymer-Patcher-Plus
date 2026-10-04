package me.drex.polymerpatcher.resources;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.BlockStateAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import nl.theepicblock.resourcelocatorapi.ResourceLocatorApi;
import nl.theepicblock.resourcelocatorapi.api.AssetContainer;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;

public class ResourceHelper {
    public static final AssetContainer GLOBAL_ASSETS = ResourceLocatorApi.createGlobalAssetContainer();
    private static final FileSystem vanillaFilesystem;

    static {
        try {
            vanillaFilesystem = FileSystems.newFileSystem(PolymerCommonUtils.getClientJar());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void init(ResourcePackBuilder packBuilder) {
        PolymerPatcher.LOGGER.info("Loading global assets");
        GLOBAL_ASSETS.locateFiles("").forEach(tuple -> {
            Identifier id = tuple.getFirst();
            IoSupplier<InputStream> ioSupplier = tuple.getSecond();

            try {
                byte[] data = IOUtils.toByteArray(ioSupplier.get());

                if (isMerged(id) && !isJson(data)) {
                    PolymerPatcher.LOGGER.warn(
                        "Skipping assets/{}/{}: it is not valid JSON. This is a fault in that mod's own files, "
                            + "and everything else from the mod is still included.",
                        id.getNamespace(), id.getPath());
                    return;
                }

                packBuilder.addData("assets/" + id.getNamespace() + "/" + id.getPath(), data);
            } catch (IOException e) {
                PolymerPatcher.LOGGER.error("Failed to read resource {}: {}", id, e);
            }
        });
        addMinecraftNamespaceContributions(packBuilder);
    }

    /**
     * Resource Locator exposes one winner for a duplicate path. That loses exactly the resources mods
     * are allowed to merge into {@code assets/minecraft}: language keys and sound definitions. Read
     * those mergeable files from every mod container so a mod using the game's namespace still works.
     */
    private static void addMinecraftNamespaceContributions(ResourcePackBuilder packBuilder) {
        int added = 0;
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            if (modId.equals("minecraft") || modId.equals("java")) {
                continue;
            }
            var root = mod.findPath("assets/minecraft");
            if (root.isEmpty()) {
                continue;
            }
            try (var files = Files.walk(root.get())) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    String relative = root.get().relativize(file).toString().replace('\\', '/');
                    boolean mergeable = relative.equals("sounds.json")
                        || relative.startsWith("lang/") && relative.endsWith(".json")
                        || relative.startsWith("atlases/") && relative.endsWith(".json");
                    if (!mergeable) {
                        continue;
                    }
                    byte[] data = Files.readAllBytes(file);
                    if (!isJson(data)) {
                        PolymerPatcher.LOGGER.warn("Skipping invalid assets/minecraft/{} from {}", relative, modId);
                        continue;
                    }
                    packBuilder.addData("assets/minecraft/" + relative, data);
                    added++;
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not merge assets/minecraft additions from {}", modId, e);
            }
        }
        if (added > 0) {
            PolymerPatcher.LOGGER.info("Merged {} mod-provided language/sound/atlas file(s) under assets/minecraft", added);
        }
    }

    /**
     * Whether the pack builder will merge this file with the same file from every other mod, rather
     * than storing it as it is.
     * <p>
     * The distinction matters because merging parses: one mod shipping a truncated language file - and
     * Alex's Mobs ships exactly that - throws where storing the bytes would not have cared. Checking
     * first turns a stack trace from inside the pack builder into one line naming the file, and costs
     * a parse of only the handful of files that are actually merged.
     */
    private static boolean isMerged(Identifier id) {
        String path = id.getPath();
        if (!path.endsWith(".json")) {
            return false;
        }
        return path.startsWith("lang/") || path.startsWith("atlases/") || path.equals("sounds.json");
    }

    private static boolean isJson(byte[] data) {
        try {
            JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sound events a mod declares under the game's own name, read once out of the mods themselves. */
    private static volatile java.util.@Nullable Set<String> moddedSoundEvents;

    /**
     * Whether a mod declares this sound event, despite it being named as the game's own.
     * <p>
     * Asked the other way round from the checks above, because the game's own sound definitions are not
     * in its jar - they are downloaded separately - so there is nothing to compare against. What can be
     * read is what the mods themselves declare, and a sound event named in a mod's own
     * {@code sounds.json} is that mod's however it is named.
     * <p>
     * This is why FallDrop Backport's sounds came out wrong rather than silent. Its sound events are
     * registered under {@code minecraft}, so they were passed over as the game's own and their numbers
     * went to clients unchanged - and a number means a different sound on a client with a shorter list,
     * so what played was some other sound entirely.
     */
    public static boolean isModDeclaredSoundEvent(Identifier id) {
        java.util.Set<String> declared = moddedSoundEvents;
        if (declared == null) {
            declared = readModDeclaredSoundEvents();
            moddedSoundEvents = declared;
        }
        // A name a mod declares is only a mod's if the game does not already have it. Mods add sounds
        // to assets/minecraft/sounds.json for two reasons - to add a new one, and to give an existing
        // one more variations - and only the first makes a new registry entry. Treating the second as a
        // mod's handed a real vanilla sound a stand-in: mcd_d_nether declares ambient.warped_forest.additions,
        // which the game has shipped for years, and the warped forest started making some other noise
        return declared.contains(id.getPath()) && !isVanillaSoundName(id.getPath());
    }

    /** The sound events the game creates in {@code SoundEvents}, read once. */
    private static volatile java.util.@Nullable Set<Identifier> soundEventsFields;

    /**
     * Whether a sound event under {@code minecraft} is really the game's, so a vanilla client has it at
     * the same number.
     * <p>
     * Being declared by a mod is not the only way to be a mod's. FallDrop Backport also registers
     * {@code minecraft:intentionally_emptys}, a silent sound it never puts in any sounds.json - so
     * nothing declared it, it passed as the game's own, and its number went out unchanged. That number
     * is some unrelated vanilla sound on a vanilla client, and the shelf mushroom uses it as its hit
     * sound, which is what plays while one is being mined.
     * <p>
     * So the game's own is asked positively: the game's sound list names it, or the game's own
     * {@code SoundEvents} creates it and no mod claims it.
     */
    public static boolean isGamesOwnSoundEvent(Identifier id) {
        if (isVanillaSoundName(id.getPath())) {
            return true;
        }
        java.util.Set<Identifier> created = soundEventsFields;
        if (created == null) {
            created = readSoundEventsFields();
            soundEventsFields = created;
        }
        return created.contains(id) && !isModDeclaredSoundEvent(id);
    }

    private static java.util.Set<Identifier> readSoundEventsFields() {
        java.util.Set<Identifier> found = new java.util.HashSet<>();
        for (var field : net.minecraft.sounds.SoundEvents.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                collectSoundEvents(field.get(null), found);
            } catch (Throwable ignored) {
                // A field that cannot be read is one sound fewer counted as the game's own
            }
        }
        return java.util.Set.copyOf(found);
    }

    private static void collectSoundEvents(@Nullable Object value, java.util.Set<Identifier> found) {
        if (value instanceof net.minecraft.sounds.SoundEvent event) {
            found.add(event.location());
        } else if (value instanceof net.minecraft.core.Holder<?> holder && holder.isBound()
            && holder.value() instanceof net.minecraft.sounds.SoundEvent event) {
            found.add(event.location());
        } else if (value instanceof Iterable<?> values) {
            for (Object each : values) {
                collectSoundEvents(each, found);
            }
        }
    }

    /** Every sound name the vanilla game itself defines, as Polymer records them. */
    private static volatile java.util.@Nullable Set<String> vanillaSoundNames;

    /**
     * Whether the vanilla game defines this sound name.
     * <p>
     * Vanilla ships its sounds.json beside the jar rather than inside it, so it cannot be read the way
     * the other vanilla assets here are. Polymer keeps a copy for exactly this reason, and that is what
     * is asked.
     */
    public static boolean isVanillaSoundName(String name) {
        java.util.Set<String> known = vanillaSoundNames;
        if (known == null) {
            try {
                known = java.util.Set.copyOf(eu.pb4.polymer.soundpatcher.api.SoundPatcher.getVanillaSoundAsset().sounds().keySet());
            } catch (Throwable e) {
                // Better to treat nothing as vanilla than to fail here; the worst case is the behaviour
                // this check was added to correct
                PolymerPatcher.LOGGER.warn("Could not read the game's own sound list", e);
                known = java.util.Set.of();
            }
            vanillaSoundNames = known;
        }
        return known.contains(name);
    }

    /** What each namespace says its own sounds are, read once per namespace. */
    private static final java.util.Map<String, java.util.Set<String>> OWN_SOUND_DEFINITIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether the mod that registered this sound also ships a definition saying what it sounds like.
     * <p>
     * This is the difference between a sound that can be sent to a stranger as itself and one that
     * cannot. A sound the pack defines arrives as its own name and plays the mod's own audio; a sound
     * with no definition has nothing behind the name, so it has to be told as some vanilla sound
     * instead. Asking the question here is what stops every modded sound being flattened onto an
     * unrelated vanilla one.
     */
    public static boolean hasOwnSoundDefinition(Identifier id) {
        return OWN_SOUND_DEFINITIONS
            .computeIfAbsent(id.getNamespace(), ResourceHelper::readSoundDefinitions)
            .contains(id.getPath());
    }

    private static java.util.Set<String> readSoundDefinitions(String namespace) {
        if (Identifier.DEFAULT_NAMESPACE.equals(namespace)) {
            java.util.Set<String> all = new java.util.HashSet<>(readModDeclaredSoundEvents());
            IoSupplier<InputStream> selected = getAsset(namespace, "sounds.json");
            if (selected != null) {
                try (InputStream stream = selected.get()) {
                    all.addAll(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().keySet());
                } catch (Throwable ignored) {
                }
            }
            return java.util.Set.copyOf(all);
        }
        IoSupplier<InputStream> asset = getAsset(namespace, "sounds.json");
        if (asset == null) {
            return java.util.Set.of();
        }
        try (InputStream stream = asset.get()) {
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return java.util.Set.copyOf(json.keySet());
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read the sounds {} defines", namespace, e);
            return java.util.Set.of();
        }
    }

    private static java.util.Set<String> readModDeclaredSoundEvents() {
        java.util.Set<String> found = new java.util.HashSet<>();

        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("minecraft") || id.equals("java")) {
                continue;
            }

            var path = mod.findPath("assets/minecraft/sounds.json");
            if (path.isEmpty()) {
                continue;
            }

            try (InputStream stream = Files.newInputStream(path.get())) {
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                found.addAll(json.keySet());
                PolymerPatcher.LOGGER.info("{} declares {} sound(s) under the game's own name; they will be given stand-ins", id, json.size());
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not read the sounds {} declares", id, e);
            }
        }

        return java.util.Set.copyOf(found);
    }

    /** Every translation key the vanilla game ships, read once out of its own language file. */
    private static volatile java.util.@Nullable Set<String> vanillaLangKeys;

    /**
     * Whether the vanilla game itself has this translation key.
     * <p>
     * The way to ask whether the game has an entity type, which unlike a block or an item leaves nothing
     * behind in the assets to look for. Every entity the game ships is named in its language file, and a
     * mod's is not - so {@code entity.minecraft.pig} is there and {@code entity.minecraft.cushion} is
     * not, whatever the cushion chose to call itself.
     */
    public static boolean hasVanillaLangKey(String key) {
        java.util.Set<String> keys = vanillaLangKeys;
        if (keys == null) {
            keys = readVanillaLangKeys();
            vanillaLangKeys = keys;
        }
        return keys.contains(key);
    }

    private static java.util.Set<String> readVanillaLangKeys() {
        try (InputStream stream = Files.newInputStream(vanillaFilesystem.getPath("/assets/minecraft/lang/en_us.json"))) {
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return java.util.Set.copyOf(json.keySet());
        } catch (Throwable e) {
            // Reading nothing would call every entity type a mod's and hand the whole game to Polymer,
            // so the failure is loud and the answer is "everything is the game's own"
            PolymerPatcher.LOGGER.error("Could not read the game's own language file; entity types will be judged by name alone", e);
            return java.util.Set.of();
        }
    }

    /** Whether the language file was readable at all, which decides if the check above means anything. */
    public static boolean hasVanillaLang() {
        hasVanillaLangKey("");
        java.util.Set<String> keys = vanillaLangKeys;
        return keys != null && !keys.isEmpty();
    }

    /**
     * Whether the vanilla game itself ships this asset, ignoring every mod on the server.
     * <p>
     * Unlike {@link #getAsset}, which answers out of the whole server's assets and so cannot tell the
     * game's own from a mod's. This is the difference that matters when the question is "would a player
     * without our mods have this", and a mod is free to add its content under {@code minecraft:} - so
     * the name it goes by is no answer at all.
     */
    public static boolean hasVanillaAsset(String namespace, String path) {
        try {
            return Files.exists(vanillaFilesystem.getPath("/assets/" + namespace + "/" + path));
        } catch (Throwable e) {
            return false;
        }
    }

    public static IoSupplier<InputStream> getAsset(String namespace, String path) {
        IoSupplier<InputStream> supplier = ResourceHelper.GLOBAL_ASSETS.getAsset(namespace, path);
        if (supplier != null) {
            return supplier;
        }
        var vanillaPath = vanillaFilesystem.getPath("/assets/" + namespace + "/" + path);
        if (Files.exists(vanillaPath)) {
            return IoSupplier.create(vanillaPath);
        } else {
            return null;
        }
    }

    /**
     * A class as the vanilla game ships it, before any mod touched it.
     * <p>
     * The running classes are no use for this: by the time anything can look at them the mixins have
     * already been applied, so a class carrying a mod's additions is indistinguishable from one that
     * never had any. The jar on disk still has the original.
     *
     * @param internalName the class's name with slashes, e.g. {@code net/minecraft/world/entity/Entity}
     * @return its bytes, or null when the vanilla game has no such class
     */
    @Nullable
    public static byte[] getVanillaClass(String internalName) {
        try {
            var path = vanillaFilesystem.getPath("/" + internalName + ".class");
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static <T> T decodeAsset(Codec<T> codec, Identifier id, String type, String extension) throws IOException {
        IoSupplier<InputStream> supplier = getAsset(id.getNamespace(), type + "/" + id.getPath() + extension);
        return codec.decode(JsonOps.INSTANCE, JsonParser.parseReader(new JsonReader(new InputStreamReader(supplier.get())))).getOrThrow().getFirst();
    }

    public static BlockStateAsset decodeBlockState(Identifier id) throws IOException {
        return decodeAsset(BlockStateAsset.CODEC, id, "blockstates", ".json");
    }

    public static ModelAsset decodeModel(Identifier id) throws IOException {
        return decodeAsset(ModelAsset.CODEC, id, "models", ".json");
    }
}
