package me.drex.polymerpatcher.dump.data.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.ColorResolver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class ColorResolverAdapter implements JsonSerializer<ColorResolver>, JsonDeserializer<ColorResolver> {
    private static final Map<ColorResolver, String> NAMES_BY_RESOLVER = new HashMap<>();
    private static final Map<String, ColorResolver> RESOLVERS_BY_NAME = new HashMap<>();

    static {
        for (Field field : BiomeColors.class.getDeclaredFields()) {
            if (!isColorResolverField(field)) {
                continue;
            }

            try {
                ColorResolver resolver = (ColorResolver) field.get(null);
                NAMES_BY_RESOLVER.put(resolver, field.getName());
                RESOLVERS_BY_NAME.put(field.getName(), resolver);
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @Override
    public JsonElement serialize(ColorResolver src, Type typeOfSrc, JsonSerializationContext context) {
        String name = NAMES_BY_RESOLVER.get(src);
        return name != null ? new JsonPrimitive(name) : JsonNull.INSTANCE;
    }

    @Override
    public ColorResolver deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return null;
        }

        return RESOLVERS_BY_NAME.get(json.getAsString());
    }

    private static boolean isColorResolverField(Field field) {
        return Modifier.isStatic(field.getModifiers()) && ColorResolver.class.isAssignableFrom(field.getType());
    }
}
