package me.drex.polymerpatcher.dump.data.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import me.drex.polymerpatcher.dump.PolymerPatcherDumper;

import java.lang.reflect.Type;

public record CodecSerializer<T>(Codec<T> codec) implements JsonSerializer<T>, JsonDeserializer<T> {
    @Override
    public T deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        try {
            DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
            return this.codec.decode(ops, json).getOrThrow().getFirst();
        } catch (Throwable e) {
            PolymerPatcherDumper.LOGGER.error("Failed to deserialize: {}", json, e);
            return null;
        }
    }

    @Override
    public JsonElement serialize(T src, Type typeOfSrc, JsonSerializationContext context) {
        try {
            if (src == null) {
                return JsonNull.INSTANCE;
            }
            DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
            return this.codec.encodeStart(ops, src).getOrThrow();
        } catch (Throwable e) {
            PolymerPatcherDumper.LOGGER.error("Failed to serialize: {}", src, e);
            return JsonNull.INSTANCE;
        }
    }
}
