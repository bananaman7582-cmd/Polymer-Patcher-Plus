package me.drex.polymerpatcher.dump.data.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Type;

public class ModelLayerLocationAdapter implements JsonSerializer<ModelLayerLocation>, JsonDeserializer<ModelLayerLocation> {

    @Override
    public JsonElement serialize(ModelLayerLocation src, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(src.toString());
    }

    @Override
    public ModelLayerLocation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {

        String s = json.getAsString();
        int i = s.lastIndexOf('#');

        if (i < 0) {
            throw new JsonParseException("Invalid ModelLayerLocation: " + s);
        }

        Identifier id = Identifier.parse(s.substring(0, i));
        String layer = s.substring(i + 1);

        return new ModelLayerLocation(id, layer);
    }
}
