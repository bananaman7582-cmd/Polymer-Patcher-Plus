package me.drex.polymerpatcher.compat.alexscaves;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ItemTintFallbacks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Exact meanings of Alex's Caves' {@code alexscaves:tint} sources. */
public final class AlexsCavesItemTints {
    private AlexsCavesItemTints() {
    }

    private static final String TYPE = "alexscaves:tint";
    private static final Set<String> SOURCES = Set.of("biome", "pearl", "jelly_bean", "biome_treat");
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static boolean initialized;

    /** Registers only Alex's Caves' tint vocabulary; the bridge itself is global. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ItemTintFallbacks.register(AlexsCavesItemTints::supports, AlexsCavesItemTints::resolve);
    }

    private static boolean supports(JsonObject source) {
        JsonElement type = source.get("type");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
            && supports(type.getAsString(), source);
    }

    public static boolean supports(String type, JsonObject source) {
        JsonElement name = source.get("source");
        return TYPE.equals(type) && name != null && name.isJsonPrimitive()
            && name.getAsJsonPrimitive().isString() && SOURCES.contains(name.getAsString());
    }

    /**
     * Calls the same server-side helper the mod's client tint shim calls. These are stack properties, not
     * sampled pixels, so the result remains correct in inventory, hand and display contexts alike.
     */
    public static @Nullable Integer resolve(JsonObject tint, ItemStack stack, @Nullable Level level) {
        String source = tint.get("source").getAsString();
        try {
            Object result = switch (source) {
                case "biome" -> level == null ? null : method(source,
                    "com.github.alexmodguy.alexscaves.server.item.CaveInfoItem", "getBiomeColorOf",
                    Level.class, ItemStack.class, boolean.class).invoke(null, level, stack, false);
                case "pearl" -> method(source,
                    "com.github.alexmodguy.alexscaves.server.item.GazingPearlItem", "getPearlColor",
                    ItemStack.class).invoke(null, stack);
                case "jelly_bean" -> method(source,
                    "com.github.alexmodguy.alexscaves.server.item.JellyBeanItem", "getBeanColor",
                    ItemStack.class).invoke(null, stack);
                case "biome_treat" -> level == null ? null : method(source,
                    "com.github.alexmodguy.alexscaves.server.item.BiomeTreatItem", "getBiomeTreatColorOf",
                    Level.class, ItemStack.class).invoke(null, level, stack);
                default -> null;
            };
            return result instanceof Integer color ? 0xFF000000 | (color & 0xFFFFFF) : null;
        } catch (Throwable e) {
            if (REPORTED.add(source)) {
                PolymerPatcher.LOGGER.warn("Could not calculate Alex's Caves item tint '{}'", source, e);
            }
            return null;
        }
    }

    private static Method method(String key, String owner, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Optional<Method> cached = METHODS.get(key);
        if (cached == null) {
            try {
                Method found = Class.forName(owner).getMethod(name, parameters);
                cached = Optional.of(found);
            } catch (ReflectiveOperationException e) {
                cached = Optional.empty();
            }
            METHODS.put(key, cached);
        }
        if (cached.isEmpty()) {
            throw new NoSuchMethodException(owner + "#" + name);
        }
        return cached.get();
    }
}
