package me.drex.polymerpatcher.dump.data.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;

import java.lang.reflect.Type;

public class ClassAdapter implements JsonSerializer<Class<?>>, JsonDeserializer<Class<?>> {

    @Override
    public JsonElement serialize(Class<?> src, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(src.getName());
    }

    @Override
    public Class<?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {

        // Never throws: a renderer that cannot be loaded costs its own entity its model, and the
        // registration below already treats a null renderer as an entity to leave alone. Letting it
        // out of here instead took the whole server down with it, since the dump is read while the
        // mod is still starting up
        return ClientOnlyClasses.load(json.getAsString());
    }
}
