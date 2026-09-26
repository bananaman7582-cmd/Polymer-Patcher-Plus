package me.drex.polymerpatcher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import me.drex.polymerpatcher.dump.data.adapter.CodecSerializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static me.drex.polymerpatcher.PolymerPatcher.LOGGER;

public class ConfigManager {

    public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("polymer-patcher.json");
    private static final Gson GSON = new GsonBuilder()
        .registerTypeHierarchyAdapter(Identifier.class, new CodecSerializer<>(Identifier.CODEC))
        .setPrettyPrinting()
        .create();
    private static Config config = new Config();

    public static boolean load() {
        LOGGER.info("Loading polymer-patcher config");
        if (Files.exists(CONFIG_FILE)) {
            try {
                String data = Files.readString(CONFIG_FILE);
                try {
                    config = GSON.fromJson(data, Config.class);
                    return true;
                } catch (JsonSyntaxException e) {
                    LOGGER.error("Failed to parse polymer-patcher config", e);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load polymer-patcher config", e);
            }
        } else {
            try {
                Files.writeString(CONFIG_FILE, GSON.toJson(config));
                return true;
            } catch (IOException e) {
                LOGGER.error("Failed to save polymer-patcher config", e);
            }
        }
        return false;
    }

    public static Config config() {
        return config;
    }

}