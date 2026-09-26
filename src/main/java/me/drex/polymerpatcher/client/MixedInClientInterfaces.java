package me.drex.polymerpatcher.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes loadable, before anything else runs, every client-only interface a mod's mixins add to the game.
 * <p>
 * A mixin can make one of the game's own classes implement an interface of the mod's. FrozenLib 3.0 does it
 * to the classes every model is made of - {@code ModelPart} and {@code PartDefinition} now implement its
 * {@code InvertibleModelPart} - and marks that interface as client-only. On a server Fabric refuses to load
 * a client-only class, and a game class that implements one cannot load without it. So the first thing here
 * to touch a model class took the whole server down with it, before a single player could join.
 * <p>
 * This mod draws modded mobs on the server by running their client renderers, and brings their client-only
 * classes in by hand to do it ({@link ClientOnlyClasses}). That only works if it gets to a class before
 * anything else asks for it: once a class has been refused on behalf of another, that other class stays
 * broken for the rest of the run. So these are brought in first thing, found by reading what every mod's
 * mixins implement - nothing here names a mod or an interface.
 */
public final class MixedInClientInterfaces {

    private MixedInClientInterfaces() {
    }

    private static final String ENVIRONMENT = "Lnet/fabricmc/api/Environment;";

    public static void predefine() {
        Set<String> seen = new HashSet<>();
        List<String> brought = new ArrayList<>();
        int mixins = 0;

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (String config : mixinConfigs(mod)) {
                JsonObject json = json(bytes(mod, config));
                if (json == null || !json.has("package")) {
                    continue;
                }
                String pkg = json.get("package").getAsString();
                // A client-only config is never applied on a server, so nothing it adds can get in the way here
                for (String side : List.of("mixins", "server")) {
                    if (!json.has(side) || !json.get(side).isJsonArray()) {
                        continue;
                    }
                    for (JsonElement entry : json.getAsJsonArray(side)) {
                        byte[] mixin = bytes(mod, (pkg + "." + entry.getAsString()).replace('.', '/') + ".class");
                        if (mixin == null) {
                            continue;
                        }
                        mixins++;
                        for (String type : new ClassReader(mixin).getInterfaces()) {
                            if (!seen.add(type) || !clientOnly(type)) {
                                continue;
                            }
                            String name = type.replace('/', '.');
                            if (ClientOnlyClasses.loadQuietly(name) != null) {
                                brought.add(name);
                            } else {
                                PolymerPatcher.LOGGER.warn("{} adds client-only {} to a class of the game, and it could not be "
                                    + "brought in; anything that touches that class on this server will fail", mod.getMetadata().getId(), name);
                            }
                        }
                    }
                }
            }
        }

        if (!brought.isEmpty()) {
            PolymerPatcher.LOGGER.info("Brought in {} client-only interface(s) that mods' mixins add to the game's own classes, "
                + "out of {} mixin(s) read, so those classes can load on a server: {}", brought.size(), mixins, brought);
        }
    }

    /** The mixin configs a mod applies on a server, from its fabric.mod.json. */
    private static List<String> mixinConfigs(ModContainer mod) {
        List<String> configs = new ArrayList<>();
        JsonObject metadata = json(bytes(mod, "fabric.mod.json"));
        if (metadata == null || !metadata.has("mixins") || !metadata.get("mixins").isJsonArray()) {
            return configs;
        }
        for (JsonElement entry : metadata.getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive()) {
                configs.add(entry.getAsString());
            } else if (entry.isJsonObject() && entry.getAsJsonObject().has("config")) {
                JsonObject object = entry.getAsJsonObject();
                String environment = object.has("environment") ? object.get("environment").getAsString() : "*";
                if (!environment.equals("client")) {
                    configs.add(object.get("config").getAsString());
                }
            }
        }
        return configs;
    }

    /** Whether a class is marked {@code @Environment(EnvType.CLIENT)}, read from its bytes without loading it. */
    private static boolean clientOnly(String type) {
        byte[] bytes;
        try (InputStream in = MixedInClientInterfaces.class.getClassLoader().getResourceAsStream(type + ".class")) {
            if (in == null) {
                return false;
            }
            bytes = in.readAllBytes();
        } catch (Exception e) {
            return false;
        }

        boolean[] client = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!ENVIRONMENT.equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String name, String enumDescriptor, String value) {
                        if ("CLIENT".equals(value)) {
                            client[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return client[0];
    }

    private static byte @Nullable [] bytes(ModContainer mod, String path) {
        try {
            var found = mod.findPath(path);
            return found.isPresent() ? Files.readAllBytes(found.get()) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static @Nullable JsonObject json(byte @Nullable [] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
