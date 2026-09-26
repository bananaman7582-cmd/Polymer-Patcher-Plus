package me.drex.polymerpatcher.util;

import com.google.gson.JsonParser;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a mod calls something, in words, for a client that has never heard of it.
 * <p>
 * Anything a mod names is named by a translation key, and a key is only a name to somebody holding the
 * file that translates it. A client without the mod has no such file, so a message naming a modded
 * thing arrives as {@code effect.alexscaves.irradiated} - which is not nothing, but it is not a name
 * either.
 * <p>
 * The mod's own English file is right there on the server, so the key is looked up in it and the
 * answer sent as plain words. Where there is no file, or no such key in it, the key's own last part is
 * tidied into something readable rather than shown raw.
 */
public final class ModdedNames {

    private ModdedNames() {
    }

    /** Each namespace's English file, read the first time something from that mod is named. */
    private static final Map<String, Map<String, String>> BY_NAMESPACE = new ConcurrentHashMap<>();

    /**
     * The words a mod uses for this key, or a readable stand-in worked out from the key itself.
     */
    public static String of(String namespace, String key) {
        String translated = BY_NAMESPACE
            .computeIfAbsent(namespace, ModdedNames::read)
            .get(key);
        return translated != null ? translated : tidy(key);
    }

    private static Map<String, String> read(String namespace) {
        IoSupplier<InputStream> asset = ResourceHelper.getAsset(namespace, "lang/en_us.json");
        if (asset == null) {
            return Map.of();
        }

        try (InputStream stream = asset.get()) {
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> words = new HashMap<>();
            for (var entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    words.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return Map.copyOf(words);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read what {} calls things", namespace, e);
            return Map.of();
        }
    }

    /**
     * A key's last part, made readable: {@code effect.alexscaves.irradiated} becomes {@code Irradiated}.
     */
    private static String tidy(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ').trim();
        if (last.isEmpty()) {
            return key;
        }

        StringBuilder tidied = new StringBuilder(last.length());
        boolean startOfWord = true;
        for (char letter : last.toCharArray()) {
            tidied.append(startOfWord ? Character.toUpperCase(letter) : letter);
            startOfWord = letter == ' ';
        }
        return tidied.toString();
    }

    /** Whether this is something the game itself ships, and so needs no help being named. */
    public static boolean isTheGamesOwn(String namespace) {
        return namespace.toLowerCase(Locale.ROOT).equals("minecraft");
    }
}
