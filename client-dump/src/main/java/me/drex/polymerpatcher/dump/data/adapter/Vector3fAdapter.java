package me.drex.polymerpatcher.dump.data.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.joml.Vector3fc;

import java.lang.reflect.Type;

public class Vector3fAdapter implements JsonSerializer<Vector3fc>, JsonDeserializer<Vector3fc> {

    @Override
    public JsonElement serialize(Vector3fc src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", src.x());
        obj.addProperty("y", src.y());
        obj.addProperty("z", src.z());
        return obj;
    }

    @Override
    public Vector3fc deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        float x = obj.get("x").getAsFloat();
        float y = obj.get("y").getAsFloat();
        float z = obj.get("z").getAsFloat();
        return new org.joml.Vector3f(x, y, z);
    }
}