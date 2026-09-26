package me.drex.polymerpatcher.dump.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.dump.data.adapter.ClassAdapter;
import me.drex.polymerpatcher.dump.data.adapter.CodecSerializer;
import me.drex.polymerpatcher.dump.data.adapter.ColorResolverAdapter;
import me.drex.polymerpatcher.dump.data.adapter.ModelLayerLocationAdapter;
import me.drex.polymerpatcher.dump.data.adapter.RegistrySerializer;
import me.drex.polymerpatcher.dump.data.adapter.Vector3fAdapter;
import me.drex.polymerpatcher.dump.data.codec.LayerDefinitionCodecs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class RenderRegistryStorage {
    private static final String DUMP_FILE_NAME = "polymer-patcher-dump.json";

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(ModelLayerLocation.class, new ModelLayerLocationAdapter())
        .registerTypeAdapter(LayerDefinition.class, new CodecSerializer<>(LayerDefinitionCodecs.LAYER_DEFINITION))
        .registerTypeAdapter(Class.class, new ClassAdapter())
        .registerTypeAdapter(ColorResolver.class, new ColorResolverAdapter())
        .registerTypeAdapter(Vector3fc.class, new Vector3fAdapter())
        .registerTypeHierarchyAdapter(EntityType.class, new RegistrySerializer<>(BuiltInRegistries.ENTITY_TYPE))
        .registerTypeHierarchyAdapter(Block.class, new RegistrySerializer<>(BuiltInRegistries.BLOCK))
        .registerTypeHierarchyAdapter(Item.class, new RegistrySerializer<>(BuiltInRegistries.ITEM))
        .registerTypeHierarchyAdapter(Identifier.class, new CodecSerializer<>(Identifier.CODEC))
        .setPrettyPrinting()
        .create();

    private RenderRegistryStorage() {
    }

    public static RenderRegistry load() {
        try {
            return load(existingDumpFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + DUMP_FILE_NAME, e);
        }
    }

    /**
     * Where a dump was actually found, or where one would be looked for if there is none yet.
     * <p>
     * A current file somebody put somewhere deliberately wins over one that arrived on its own: the
     * config folder first, then the server folder, and only then the shared client copy. An obsolete
     * file does not hide a current one, though; that lets a newly refreshed shared dump replace an old
     * server copy automatically on a machine which runs both.
     */
    public static Path existingDumpFile() {
        Path firstExisting = null;
        for (Path candidate : List.of(configDumpFile(), gameDumpFile(), sharedDumpFile())) {
            if (Files.exists(candidate)) {
                if (firstExisting == null) {
                    firstExisting = candidate;
                }
                if (isCurrentDump(candidate)) {
                    return candidate;
                }
            }
        }

        return firstExisting != null ? firstExisting : configDumpFile();
    }

    /** A current dump outranks an obsolete copy, while the normal config-first order remains intact. */
    private static boolean isCurrentDump(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            return root.has("formatVersion")
                && root.get("formatVersion").getAsInt() >= RenderRegistry.CURRENT_FORMAT_VERSION;
        } catch (Throwable e) {
            return false;
        }
    }

    public static RenderRegistry load(Path path) throws IOException {
        RenderRegistry registry = Files.exists(path)
            ? GSON.fromJson(Files.readString(path), RenderRegistry.class)
            : new RenderRegistry();
        registry.rebuildIndexes();
        return registry;
    }

    /**
     * Writes the dump to both places it might be wanted, because the two ways of running a server want
     * different ones.
     * <p>
     * Hosting it yourself, the server is on this machine but has no idea where the client is installed
     * - it does know where home is, so the copy left there is found without anything being moved by
     * hand. Renting one instead, nothing is shared and the file has to be uploaded: the copy in the
     * config folder is the one to send, that being the folder host panels give you to put files in.
     */
    public static void save(RenderRegistry registry) throws IOException {
        String json = GSON.toJson(registry, RenderRegistry.class);

        for (Path path : List.of(configDumpFile(), sharedDumpFile())) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json);
        }
    }

    public static void save(RenderRegistry registry, Path path) throws IOException {
        Files.writeString(path, GSON.toJson(registry, RenderRegistry.class));
    }

    /**
     * Beside the other configuration, which is what a host panel lets you upload into.
     */
    public static Path configDumpFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(DUMP_FILE_NAME);
    }

    /**
     * The server or client folder itself, which is where dumps used to be written.
     */
    public static Path gameDumpFile() {
        return FabricLoader.getInstance().getGameDir().resolve(DUMP_FILE_NAME);
    }

    /**
     * Beside the home directory, where both sides can find it without knowing where the other lives.
     */
    public static Path sharedDumpFile() {
        return Path.of(System.getProperty("user.home"), ".polymer-patcher", DUMP_FILE_NAME);
    }
}
