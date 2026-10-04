package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Names for the things a mod forgot to name.
 * <p>
 * A subtitle or an entity's name is a translation key, and a key nobody translates is shown as the
 * key itself. Some mods ship keys like that - FallDrop Backport's shelf mushroom subtitles point at
 * {@code sounds.minecraft.shelf_mushroom.break} and its cushion is {@code entity.falldrop_backport.cushion},
 * neither of which its language file has - so the raw key turns up on screen, for players with the
 * mod as much as without it.
 * <p>
 * Every key that no mod and not the game translates gets a plain English name worked out from the key
 * itself. They go into the pack before the mods' own language files, so if anything does turn out to
 * translate one, its words win.
 */
public final class MissingTranslations {

    private MissingTranslations() {
    }

    public static void generate(ResourcePackBuilder builder) {
        try {
            // Without the game's own keys every vanilla entity would look unnamed, and the pack would
            // rename the pig
            if (!ResourceHelper.hasVanillaLang()) {
                return;
            }
            Set<String> translated = new HashSet<>();
            Set<String> subtitles = new HashSet<>();
            readModFiles(translated, subtitles);

            Map<String, String> missing = new TreeMap<>();
            Set<String> namespaces = new HashSet<>();
            BuiltInRegistries.SOUND_EVENT.keySet().forEach(id -> namespaces.add(id.getNamespace()));
            BuiltInRegistries.BLOCK.keySet().forEach(id -> namespaces.add(id.getNamespace()));
            BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> namespaces.add(id.getNamespace()));
            for (String key : subtitles) {
                // Some mods write the words themselves where the key goes; the game shows those as they are
                if (!key.matches("[a-z0-9_.-]+") || !key.contains(".")) {
                    continue;
                }
                if (!translated.contains(key) && !ResourceHelper.hasVanillaLangKey(key)) {
                    missing.put(key, subtitle(key, namespaces));
                }
            }
            for (var type : BuiltInRegistries.ENTITY_TYPE) {
                String key = type.getDescriptionId();
                if (!translated.contains(key) && !ResourceHelper.hasVanillaLangKey(key)) {
                    missing.put(key, words(key.substring(key.lastIndexOf('.') + 1)));
                }
            }
            if (missing.isEmpty()) {
                return;
            }

            JsonObject json = new JsonObject();
            missing.forEach(json::addProperty);
            builder.addData("assets/minecraft/lang/en_us.json", json.toString().getBytes(StandardCharsets.UTF_8));
            PolymerPatcher.LOGGER.info("Named {} subtitle(s) and entities their mods left without a name: {}", missing.size(), missing.keySet());
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not name the subtitles and entities mods left unnamed; they will show as their keys", e);
        }
    }

    /** Every English key the mods translate, and every subtitle key their sound definitions use. */
    private static void readModFiles(Set<String> translated, Set<String> subtitles) {
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("minecraft") || id.equals("java")) {
                continue;
            }
            for (Path root : mod.getRootPaths()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) {
                    continue;
                }
                try (Stream<Path> namespaces = Files.list(assets)) {
                    for (Path namespace : namespaces.toList()) {
                        JsonObject lang = read(namespace.resolve("lang/en_us.json"));
                        if (lang != null) {
                            translated.addAll(lang.keySet());
                        }
                        JsonObject sounds = read(namespace.resolve("sounds.json"));
                        if (sounds != null) {
                            for (var entry : sounds.entrySet()) {
                                if (entry.getValue() instanceof JsonObject event
                                    && event.get("subtitle") instanceof JsonElement subtitle
                                    && subtitle.isJsonPrimitive()) {
                                    subtitles.add(subtitle.getAsString());
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // One unreadable mod is a few names short, not a reason to name nothing
                }
            }
        }
    }

    private static JsonObject read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader) instanceof JsonObject object ? object : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * A subtitle in the game's own style, from the key's last two parts: {@code sounds.minecraft.shelf_mushroom.break}
     * is a shelf mushroom being broken.
     */
    static String subtitle(String key, Set<String> namespaces) {
        String[] parts = key.split("\\.");
        String action = parts[parts.length - 1];
        String subject = parts.length >= 2 ? parts[parts.length - 2] : "";
        // A category word or a mod's own name in that place says nothing about what made the sound
        if (namespaces.contains(subject)
            || Set.of("sounds", "sound", "subtitles", "subtitle", "block", "blocks", "entity", "item", "generic").contains(subject)) {
            subject = "";
        }
        if (subject.isEmpty() && !Set.of("break", "broken", "destroy", "place", "placed", "step", "steps", "footsteps", "hit", "fall").contains(action)) {
            return words(action);
        }
        if (action.matches(".*[0-9].*")) {
            return words(subject) + " " + words(action);
        }
        String thing = subject.isEmpty() ? "" : words(subject) + " ";
        return switch (action) {
            case "break", "broken", "destroy" -> (thing.isEmpty() ? "Block " : thing) + "broken";
            case "place", "placed" -> (thing.isEmpty() ? "Block " : thing) + "placed";
            case "step", "steps", "footsteps" -> "Footsteps";
            case "hit" -> (thing.isEmpty() ? "Block " : thing) + "breaking";
            case "fall" -> "Something fell";
            default -> {
                String said = thing + doing(action);
                yield Character.toUpperCase(said.charAt(0)) + said.substring(1);
            }
        };
    }

    /** {@code bounce} said of something: "bounces"; {@code get_up}: "gets up". */
    private static String doing(String action) {
        String[] verb = action.split("_", 2);
        String first = verb[0].toLowerCase(Locale.ROOT);
        if (first.matches(".*(s|sh|ch|x|z)")) {
            first += "es";
        } else if (first.matches(".*[^aeiou]y")) {
            first = first.substring(0, first.length() - 1) + "ies";
        } else {
            first += "s";
        }
        return verb.length > 1 ? first + " " + verb[1].replace('_', ' ') : first;
    }

    /** {@code shelf_mushroom} as words: "Shelf Mushroom". */
    static String words(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (char letter : path.replace('_', ' ').trim().toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(letter) : letter);
            startOfWord = letter == ' ';
        }
        return out.toString();
    }
}
