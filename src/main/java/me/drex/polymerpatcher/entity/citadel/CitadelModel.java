package me.drex.polymerpatcher.entity.citadel;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads entity models built with Citadel's own model system, which Alex's Mobs and its siblings use
 * instead of the game's.
 * <p>
 * Every vanilla entity model is a {@code LayerDefinition} registered in one place, which is how the
 * client dump finds them and how {@link me.drex.polymerpatcher.entity.AnimatedEntities} rebuilds them
 * on the server. Citadel models are not: a renderer builds its parts itself in the model's
 * constructor, so nothing enumerates them, no layer is ever baked, and an Alex's Mobs world dumps as
 * having no entity models in it at all. They still describe the same thing though - named parts, each
 * holding boxes - so given a model object the shape can be read out just as well.
 * <p>
 * <b>Everything here is reflection, deliberately.</b> Citadel is shaded inside each mod that uses it -
 * Alex's Mobs carries its own copy under {@code com.github.alexthe666.alexsmobs.citadel} - so there is
 * no one class to compile against, and a mod built on a differently-shaded copy would not match it
 * anyway. Matching on the shape of the class instead means any mod carrying Citadel works without
 * knowing which mod it is.
 * <p>
 * A model that does not fit this shape is left alone rather than guessed at; the caller falls back to
 * treating the entity as having no model, which is what already happens today.
 */
public final class CitadelModel {
    /**
     * The class every Citadel model extends, by name only, since the package differs per mod.
     * {@code AdvancedEntityModel} and {@code TabulaModel} both sit below it.
     */
    private static final String MODEL_CLASS = "BasicEntityModel";

    /**
     * The part class, likewise. {@code AdvancedModelBox} extends it and adds a per-part scale.
     */
    static final String PART_CLASS = "BasicModelPart";

    private CitadelModel() {
    }

    /**
     * Whether this looks like a model Citadel built.
     */
    public static boolean isCitadelModel(@Nullable Object model) {
        return model != null && inherits(model.getClass(), MODEL_CLASS);
    }

    /** The animator support class a posing model reaches for, once it is time to move. */
    private static final String TRANSFORM = "container.Transform";

    private static boolean warmedModelClasses;

    /**
     * Brings a mod's copy of the Citadel model classes in before any renderer is built.
     * <p>
     * A server refuses a client-only class when something asks for it, and this mod's usual answer is to
     * load it by hand and try again. That works while the refusal is the only thing that went wrong. It
     * does not work when the asking was done by a class setting itself up: the set-up fails, and the JVM
     * remembers that class as broken for the rest of the run, so the second attempt fails in a new way
     * that no amount of loading can undo. Alex's Caves' boats are exactly that - their renderer reaches
     * for {@code AdvancedEntityModel} while it is being prepared, and both boats have been drawn as
     * nothing ever since.
     * <p>
     * Loading the package up front means nothing is ever refused in the middle of setting itself up.
     */
    public static void warmModelClasses() {
        if (warmedModelClasses) {
            return;
        }
        warmedModelClasses = true;

        for (String modelPackage : discoverModelPackages()) {
            int brought = 0;
            for (String subPackage : List.of("", "basic", "container")) {
                String folder = (modelPackage + (subPackage.isEmpty() ? "" : "." + subPackage)).replace('.', '/');
                for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                    Optional<Path> path = mod.findPath(folder);
                    if (path.isEmpty() || !Files.isDirectory(path.get())) {
                        continue;
                    }
                    brought += bringIn(path.get(), folder.replace('/', '.'));
                    break;
                }
            }

            if (brought > 0) {
                me.drex.polymerpatcher.PolymerPatcher.LOGGER.info(
                    "Brought in {} class(es) of {} before any renderer is built, so none of them can be refused midway through setting one up",
                    brought, modelPackage);
            }
        }
    }

    /** Finds every shaded Citadel copy by its own base model, independent of mod id or Java package. */
    private static Set<String> discoverModelPackages() {
        Set<String> found = new LinkedHashSet<>();
        String suffix = "/basic/BasicEntityModel.class";

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(suffix) || !relative.contains("/citadel/client/model/")) {
                            continue;
                        }
                        found.add(relative.substring(0, relative.length() - suffix.length())
                            .replace('/', '.'));
                    }
                } catch (Throwable e) {
                    me.drex.polymerpatcher.PolymerPatcher.LOGGER.debug(
                        "Could not scan {} for a shaded Citadel model package", root, e);
                }
            }
        }
        return found;
    }

    /** Loads every class sitting directly in this folder, counting the ones that took. */
    private static int bringIn(Path folder, String packageName) {
        int brought = 0;
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.toList()) {
                String file = entry.getFileName().toString();
                if (!file.endsWith(".class")) {
                    continue;
                }
                String name = packageName + "." + file.substring(0, file.length() - ".class".length());
                if (me.drex.polymerpatcher.dump.ClientOnlyClasses.loadQuietly(name) != null) {
                    brought++;
                }
            }
        } catch (Throwable e) {
            me.drex.polymerpatcher.PolymerPatcher.LOGGER.debug("Could not read {}", folder, e);
        }
        return brought;
    }

    private static boolean warmedAnimator;

    /**
     * Loads the classes Citadel's animator needs before any model asks for one.
     * <p>
     * A model only reaches for these once it actually animates - a bison that starts eating, a grizzly
     * that starts walking - and a server refuses to load them, being client-only. Everywhere else this
     * mod recovers by loading the refused class and going round again; here it cannot. The call fails
     * inside a lambda, and a lambda whose set-up fails is poisoned by the JVM for good: every later
     * call through it throws the same error again, however available the class has since become. That
     * is why an idle bison looked perfect and a feeding one lost its skin for the rest of the run.
     * <p>
     * So they are loaded up front, before any model has had the chance to poison itself.
     */
    public static void warmAnimator(@NotNull Object model) {
        if (warmedAnimator) {
            return;
        }
        warmedAnimator = true;

        // Citadel travels shaded inside whichever mod uses it, so its package is read off a model that
        // extends it rather than assumed
        for (Class<?> current = model.getClass(); current != null; current = current.getSuperclass()) {
            String name = current.getName();
            int cut = name.lastIndexOf('.');
            if (cut < 0 || !name.contains(".citadel.")) {
                continue;
            }

            String target = name.substring(0, cut + 1) + TRANSFORM;
            if (me.drex.polymerpatcher.dump.ClientOnlyClasses.load(target) != null) {
                me.drex.polymerpatcher.PolymerPatcher.LOGGER.info("Loaded {} up front, so animating models cannot poison themselves on it", target);
                return;
            }
        }
    }

    /**
     * The parts a model draws from, each the top of its own branch.
     * <p>
     * {@code renderToBuffer} walks {@code parts()}, so that is what is asked for first - whatever it
     * returns is by definition what the client draws. A model that will not answer is walked from
     * {@code getAllParts()} instead, which hands back every part rather than just the tops, so the
     * ones something else already owns are dropped.
     */
    @NotNull
    public static List<Object> rootParts(@NotNull Object model) {
        List<Object> parts = invokeParts(model, "parts");
        if (!parts.isEmpty()) {
            return parts;
        }

        List<Object> all = invokeParts(model, "getAllParts");
        if (all.isEmpty()) {
            return all;
        }

        Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object part : all) {
            owned.addAll(childrenOf(part));
        }

        List<Object> roots = new ArrayList<>();
        for (Object part : all) {
            if (!owned.contains(part)) {
                roots.add(part);
            }
        }

        return roots;
    }

    @NotNull
    private static List<Object> invokeParts(@NotNull Object model, @NotNull String name) {
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    if (method.invoke(model) instanceof Iterable<?> iterable) {
                        return copyParts(iterable);
                    }
                } catch (Throwable t) {
                    // A model that will not hand its parts over is left to the caller's fallback
                }
            }
        }

        return List.of();
    }

    @NotNull
    static List<Object> childrenOf(@NotNull Object part) {
        Object children = readField(part, "childModels");
        return children instanceof Iterable<?> iterable ? copyParts(iterable) : List.of();
    }

    @NotNull
    static List<Object> cubesOf(@NotNull Object part) {
        Object cubes = readField(part, "cubeList");
        return cubes instanceof Iterable<?> iterable ? copyAll(iterable) : List.of();
    }

    /**
     * The faces of a box, each with four corners and a normal.
     * <p>
     * A Citadel box holds its faces the same way a vanilla one does, down to the units: corner
     * positions in pixels and texture coordinates as fractions of the whole texture. That is what lets
     * one conversion serve both.
     */
    public static Object[] quadsOf(@NotNull Object cube) {
        return readField(cube, "quads") instanceof Object[] quads ? quads : new Object[0];
    }

    public static Object[] verticesOf(@NotNull Object quad) {
        return readField(quad, "vertexPositions") instanceof Object[] vertices ? vertices : new Object[0];
    }

    @Nullable
    public static Vector3fc normalOf(@NotNull Object quad) {
        return readField(quad, "normal") instanceof Vector3fc normal ? normal : null;
    }

    @Nullable
    public static Vector3fc positionOf(@NotNull Object vertex) {
        return readField(vertex, "position") instanceof Vector3fc position ? position : null;
    }

    public static float textureU(@NotNull Object vertex) {
        return floatField(vertex, "textureU", 0);
    }

    public static float textureV(@NotNull Object vertex) {
        return floatField(vertex, "textureV", 0);
    }

    @NotNull
    private static List<Object> copyParts(@NotNull Iterable<?> iterable) {
        List<Object> parts = new ArrayList<>();
        for (Object part : iterable) {
            if (part != null && inherits(part.getClass(), PART_CLASS)) {
                parts.add(part);
            }
        }
        return parts;
    }

    @NotNull
    private static List<Object> copyAll(@NotNull Iterable<?> iterable) {
        List<Object> copied = new ArrayList<>();
        iterable.forEach(copied::add);
        return copied;
    }

    /**
     * Whether a class, or anything it extends, is called this.
     */
    static boolean inherits(@Nullable Class<?> type, @NotNull String simpleName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getSimpleName().equals(simpleName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The field of this name declared closest to the object's own class.
     * <p>
     * Which end the search starts from matters: {@code AdvancedModelBox} redeclares {@code cubeList}
     * and {@code childModels} over the ones {@code BasicModelPart} keeps private, and only the
     * redeclared pair is ever filled. Walking up from the object's own class finds those first.
     */
    @Nullable
    static Field findField(@NotNull Class<?> owner, @NotNull String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up
            }
        }

        return null;
    }

    @Nullable
    static Object readField(@NotNull Object owner, @NotNull String name) {
        Field field = findField(owner.getClass(), name);
        if (field == null) {
            return null;
        }

        try {
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static float floatField(@NotNull Object owner, @NotNull String name, float fallback) {
        return readField(owner, name) instanceof Number number ? number.floatValue() : fallback;
    }
}
