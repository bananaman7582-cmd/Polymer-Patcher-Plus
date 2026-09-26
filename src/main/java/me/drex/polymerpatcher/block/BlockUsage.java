package me.drex.polymerpatcher.block;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many of each display-drawn block this world actually has, remembered between runs, so the blocks
 * placed by the hundred are the ones given real carriers next time.
 * <p>
 * Carriers are handed out once, at startup, in a fixed order - and some kinds are tiny: there are stair
 * carriers for about three mods' stairs. Registry order gave them to Alex's Caves stairs that are seldom
 * placed, and Sculk Horde's infested sturdy stairs, which the horde puts on every building it reaches, got
 * none: seven hundred of them in one base were seven hundred displays. Nothing at startup can know which
 * blocks a world is full of, but a world that has been played on can say. Every display block that is
 * loaded is counted, the counts are saved beside the config, and on the next start the most-placed go
 * first ({@link PolymerBlockHelper#getPriority}). A block given a carrier stops being counted, and keeps
 * its place because the count it had is kept.
 */
public final class BlockUsage {

    private BlockUsage() {
    }

    /** Below this a block is not placed often enough to be worth moving ahead of anything. */
    private static final long WORTH_PRIORITY = 16;

    private static final Path FILE = ConfigManager.CONFIG_DIR.resolve("polymer-patcher-block-usage.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What earlier runs saw, which decides this run's order. */
    private static final Map<String, Long> REMEMBERED = new ConcurrentHashMap<>();
    /** How many displays of each this run has put in the world; a chunk loaded twice counts twice, which
     * only matters relative to other blocks. */
    private static final Map<String, Long> PEAK = new ConcurrentHashMap<>();

    static {
        try {
            if (Files.exists(FILE)) {
                Map<String, Long> read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Long>>() {
                    }.getType());
                if (read != null) {
                    REMEMBERED.putAll(read);
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read {}; blocks keep their usual carrier order", FILE, e);
        }
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 6000 == 0) {
                save();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
    }

    /**
     * The weight a block has earned by how much of it the world holds, or null if it has not earned one.
     * Lower goes first; the most-placed come ahead of everything but fluids.
     */
    public static Integer weight(Identifier block) {
        Long count = REMEMBERED.get(block.toString());
        if (count == null || count < WORTH_PRIORITY) {
            return null;
        }
        return 299 - (int) Math.min(299, count / 8);
    }

    /** A display for this block has just been put in the world. */
    static void shown(Identifier block) {
        PEAK.merge(block.toString(), 1L, Long::sum);
    }

    private static synchronized void save() {
        Map<String, Long> merged = new TreeMap<>(REMEMBERED);
        PEAK.forEach((block, peak) -> merged.merge(block, peak, Math::max));
        try {
            Files.writeString(FILE, GSON.toJson(merged), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not save {}", FILE, e);
        }
    }
}
