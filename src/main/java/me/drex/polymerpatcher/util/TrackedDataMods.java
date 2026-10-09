package me.drex.polymerpatcher.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which of the server's mods change how an entity's fields are numbered.
 * <p>
 * Tracked data is numbered at start-up, each class taking the next few numbers after its superclass, so
 * a mod that adds a field to {@code LivingEntity} pushes everything below it down by one. That is the
 * only thing that makes a server's numbering differ from a client's, and it is the only reason
 * {@link VanillaEntityData} exists.
 * <p>
 * The point of asking is that <b>most mods do not do this</b>. Whether a player numbers their fields
 * the way the server does used to be answered with "have they got every mod this server patches", and
 * that is a far larger question than the one that matters. Enderscape was added to this server, it adds
 * no tracked data at all, and every player without it was immediately treated as numbering their fields
 * differently - so their own player entity was renumbered down to what the game ships with, which for a
 * player who does have Alex's Mobs put the skin settings where the main hand belongs. The client checks
 * the type of every field it is handed, and threw the connection away on the spot.
 * <p>
 * So the question asked here is the narrow one: which mods actually add a field. A mod can only do that
 * to a class it did not write by mixing into it, so its own mixins are read out of its jar and asked
 * whether any of them calls {@code defineId}. Alex's Mobs does. Nothing else on this server does, and a
 * player missing any of those others is now left alone.
 */
public final class TrackedDataMods {

    private static final String SYNCHED_ENTITY_DATA = "SynchedEntityData";
    private static final String DEFINE_ID = "defineId";

    private static volatile @Nullable Set<String> shifting;

    private TrackedDataMods() {
    }

    /**
     * The namespaces of the patched mods that add tracked data, worked out once.
     * <p>
     * Empty is a real answer and means no mod on this server changes the numbering, rather than that
     * the question failed. A mod that cannot be read is counted as one that shifts rather than skipped,
     * because being wrong in that direction leaves the behaviour that was already here in place instead
     * of quietly turning the translation off for everybody.
     */
    public static Set<String> shifting() {
        Set<String> known = shifting;
        if (known != null) {
            return known;
        }

        Set<String> found = new HashSet<>();
        for (String namespace : PolymerPatcher.PATCHED_MODS) {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(namespace);
            if (container.isEmpty()) {
                // Nothing to read, so nothing can be ruled out
                found.add(namespace);
                continue;
            }

            try {
                if (addsTrackedData(container.get())) {
                    found.add(namespace);
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not read the mixins of {}, so it is treated as one that renumbers entity fields", namespace, e);
                found.add(namespace);
            }
        }

        shifting = Set.copyOf(found);
        PolymerPatcher.LOGGER.info(
            "Mods that change how entity fields are numbered: {}. A player's fields are only translated when they are missing one of these.",
            found.isEmpty() ? "none, so this server numbers them exactly as the game does" : found);
        return shifting;
    }

    /**
     * Says whether the mod contains a mixin that defines tracked data.
     */
    private static boolean addsTrackedData(ModContainer container) throws Exception {
        boolean found = false;
        for (String config : mixinConfigs(container)) {
            Optional<Path> path = container.findPath(config);
            if (path.isEmpty()) {
                continue;
            }

            JsonObject json = JsonParser.parseString(Files.readString(path.get(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("package")) {
                continue;
            }

            String pkg = json.get("package").getAsString().replace('.', '/');
            // Every mixin, not just until the first: which numbers belong to which mod is worked out from
            // the fields each one fills, and a mod that adds to two classes has to be found in both
            for (String key : List.of("mixins", "client", "server")) {
                for (String mixin : strings(json, key)) {
                    found |= definesTrackedData(container, pkg + "/" + mixin.replace('.', '/') + ".class");
                }
            }
        }
        return found;
    }

    /** The mixin config files the mod declares, read out of its own fabric.mod.json. */
    private static List<String> mixinConfigs(ModContainer container) throws Exception {
        Optional<Path> path = container.findPath("fabric.mod.json");
        if (path.isEmpty()) {
            return List.of();
        }

        JsonObject json = JsonParser.parseString(Files.readString(path.get(), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> configs = new ArrayList<>();
        if (json.get("mixins") instanceof JsonArray array) {
            for (JsonElement element : array) {
                // An entry is either the file name on its own, or an object naming it alongside the
                // side it belongs to
                if (element.isJsonPrimitive()) {
                    configs.add(element.getAsString());
                } else if (element instanceof JsonObject object && object.has("config")) {
                    configs.add(object.get("config").getAsString());
                }
            }
        }
        return configs;
    }

    private static List<String> strings(JsonObject json, String key) {
        if (!(json.get(key) instanceof JsonArray array)) {
            return List.of();
        }

        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    /**
     * Whether one mixin class hands out a tracked data field.
     * <p>
     * Every method is read rather than only the static initialiser, because a mod is free to define its
     * field from a holder or a helper and the effect on the numbering is the same either way.
     */
    private static boolean definesTrackedData(ModContainer container, String classFile) {
        Optional<Path> path = container.findPath(classFile);
        if (path.isEmpty()) {
            return false;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(path.get())).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            boolean defines = false;
            Set<String> mixinTargets = null;
            for (MethodNode method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.name.equals(DEFINE_ID)
                        && call.owner.endsWith(SYNCHED_ENTITY_DATA)) {
                        if (mixinTargets == null) {
                            mixinTargets = targetsOf(node);
                        }
                        // Where the number it hands out is kept, so it can be read back once the game has
                        // handed it out: a field of the mixin itself, which ends up on the class it targets,
                        // or one in a holder class of the mod's own
                        FieldRef kept = storedIn(call, node.name);
                        String mod = container.getMetadata().getId();
                        synchronized (MODS_BY_TARGET) {
                            for (String target : mixinTargets) {
                                FIELDS_BY_TARGET.computeIfAbsent(target, key -> new HashMap<>())
                                    .computeIfAbsent(mod, key -> new ArrayList<>()).add(kept);
                            }
                        }
                        if (defines) {
                            continue;
                        }
                        defines = true;
                        // Which class it adds them to matters as much as that it adds them at all. A
                        // client carrying some of these mods but not others needs the fields it does
                        // have kept and only the ones it lacks taken away, and that can only be
                        // decided one class at a time
                        Set<String> targets = targetsOf(node);
                        synchronized (MODS_BY_TARGET) {
                            for (String target : targets) {
                                MODS_BY_TARGET.computeIfAbsent(target, key -> new HashSet<>())
                                    .add(container.getMetadata().getId());
                            }
                        }
                        PolymerPatcher.LOGGER.info("{} adds tracked data in {}, to {}, so entity fields are numbered differently wherever it is installed",
                            container.getMetadata().getId(), classFile, targets);
                    }
                }
            }
            return defines;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read {}", classFile, e);
        }
        return false;
    }

    /** A static field one {@code defineId} result is stored in; {@code merged} when it is the mixin's own. */
    private record FieldRef(@Nullable String owner, @Nullable String name, boolean merged) {
    }

    /** Which mod's numbers are kept in which fields, by the class they are added to and then by mod. */
    private static final Map<String, Map<String, List<FieldRef>>> FIELDS_BY_TARGET = new HashMap<>();

    /** The static field the result of this call is put in, or an unknown one if it is kept some other way. */
    private static FieldRef storedIn(MethodInsnNode call, String mixinName) {
        for (var next = call.getNext(); next != null; next = next.getNext()) {
            if (next instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                return new FieldRef(field.owner, field.name, field.owner.equals(mixinName));
            }
            // Labels, line numbers and casts sit between a call and where its result goes; anything else
            // means it went somewhere a field cannot be read back from
            if (next.getOpcode() >= 0 && next.getOpcode() != Opcodes.CHECKCAST) {
                break;
            }
        }
        return new FieldRef(null, null, false);
    }

    /**
     * The numbers each mod adding tracked data to this class was handed, by mod, one entry per number, read
     * back out of the fields the mods keep them in. A number that is not kept in a field anyone can read -
     * Alex's Caves hands all four of its straight to a method of its own - is there as {@code -1}, so that
     * how many a mod was given is still known even where which ones is not.
     */
    public static Map<String, List<Integer>> idsAddedTo(Class<?> type) {
        Map<String, List<FieldRef>> byMod;
        synchronized (MODS_BY_TARGET) {
            byMod = FIELDS_BY_TARGET.get(type.getName().replace('.', '/'));
            byMod = byMod == null ? Map.of() : Map.copyOf(byMod);
        }
        Map<String, List<Integer>> ids = new HashMap<>();
        for (Map.Entry<String, List<FieldRef>> entry : byMod.entrySet()) {
            List<Integer> mine = new ArrayList<>();
            for (FieldRef ref : entry.getValue()) {
                Integer id = read(type, ref);
                mine.add(id == null ? -1 : id);
            }
            ids.put(entry.getKey(), mine);
        }
        return ids;
    }

    private static @Nullable Integer read(Class<?> target, FieldRef ref) {
        if (ref.owner() == null || ref.name() == null) {
            return null;
        }
        try {
            Class<?> owner = ref.merged() ? target : Class.forName(ref.owner().replace('/', '.'), true, target.getClassLoader());
            java.lang.reflect.Field field = owner.getDeclaredField(ref.name());
            field.setAccessible(true);
            return field.get(null) instanceof net.minecraft.network.syncher.EntityDataAccessor<?> accessor ? accessor.id() : null;
        } catch (Throwable e) {
            return null;
        }
    }
    /** Which mods add tracked data to which class, by the class's internal name. */
    private static final Map<String, Set<String>> MODS_BY_TARGET = new HashMap<>();

    /**
     * The classes a mixin is patching, read from its own annotation.
     * <p>
     * Both forms are read: the usual one naming classes outright, and the one naming them as strings,
     * which is how a mixin names a class it cannot refer to at compile time.
     */
    private static Set<String> targetsOf(ClassNode node) {
        Set<String> targets = new HashSet<>();

        // @Mixin is kept with class retention rather than runtime retention, which puts it among a
        // class file's invisible annotations. Reading only the visible ones found every mixin that
        // adds tracked data and learned the target of not one of them - every mod came back adding to
        // nothing at all, so no client was ever credited with a field it actually had.
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) {
            annotations.addAll(node.visibleAnnotations);
        }
        if (node.invisibleAnnotations != null) {
            annotations.addAll(node.invisibleAnnotations);
        }

        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                Object name = annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if (!(value instanceof List<?> listed)) {
                    continue;
                }
                for (Object one : listed) {
                    if ("value".equals(name) && one instanceof Type type) {
                        targets.add(type.getInternalName());
                    } else if ("targets".equals(name) && one instanceof String named) {
                        targets.add(named.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    /**
     * The mods that add tracked data to this exact class.
     * <p>
     * This is what lets a client missing one of them be told apart from a client missing none. A
     * player who has Alex's Mobs but not Alex's Caves has the field the first adds and not the four
     * the second does, and their fields have to be renumbered to exactly that - not to what the game
     * ships with, which is what used to happen and which put their skin settings where their main
     * hand goes.
     */
    public static Set<String> modsAddingTo(Class<?> type) {
        shifting();
        synchronized (MODS_BY_TARGET) {
            return Set.copyOf(MODS_BY_TARGET.getOrDefault(type.getName().replace('.', '/'), Set.of()));
        }
    }
}
