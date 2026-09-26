package me.drex.polymerpatcher.entity.citadel;

import net.minecraft.client.renderer.entity.EntityRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every Citadel model a renderer might draw with, resolved once and found again by the model itself.
 * <p>
 * A renderer does not necessarily have one model. Alex's Mobs builds three for its catfish - small,
 * medium and large - and hands back whichever fits the fish in front of it; its centipede does the
 * same for head, body and tail segments. Asking a renderer for its model at start-up therefore answers
 * for one of them and says nothing about the rest.
 * <p>
 * That mattered more than it looks. The mob was posed through whichever model the renderer chose, and
 * then drawn by walking the one captured at start-up - a different object, holding parts nothing had
 * touched. What came out was a mob stuck in its bind pose, built from the wrong model's parts: a large
 * catfish shaped like a small one, a centipede whose head belonged to another segment, animations that
 * never moved.
 * <p>
 * So every model a renderer holds is found and resolved up front, and the one actually in use is
 * looked up per frame by identity.
 */
public final class CitadelModels {

    /**
     * A model's parts, and the name its generated files are filed under.
     */
    public record Resolved(String layer, List<CitadelPart> roots) {
    }

    /**
     * Keyed by identity - two models of the same class hold different part objects, and a part is only
     * meaningful together with the model it was read from. Written while the server starts and only
     * read afterwards.
     */
    private static final Map<Object, Resolved> BY_MODEL = Collections.synchronizedMap(new IdentityHashMap<>());

    private CitadelModels() {
    }

    /**
     * Resolves a model's parts, or returns what was resolved for it before.
     */
    @NotNull
    public static Resolved resolve(@NotNull Object model) {
        Resolved existing = BY_MODEL.get(model);
        if (existing != null) {
            return existing;
        }

        List<CitadelPart> roots = CitadelPart.resolve(model);
        Resolved resolved = new Resolved(layerOf(model, roots), roots);
        BY_MODEL.put(model, resolved);
        return resolved;
    }

    /**
     * The model a piece of one belongs to, or null where it will not say.
     * <p>
     * Every piece of a Citadel model keeps a way back to the model it is part of, which is the only
     * thing that makes a piece drawn on its own placeable: on its own it is a box with no idea where it
     * sits or what it is wearing. See {@link CitadelBoxPatch}.
     */
    @Nullable
    public static Object modelOf(@Nullable Object box) {
        if (box == null) {
            return null;
        }

        java.lang.reflect.Field field = BACK_TO_MODEL.computeIfAbsent(box.getClass(), type -> {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (java.lang.reflect.Field candidate : current.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                        || !candidate.getType().getName().endsWith("EntityModel")) {
                        continue;
                    }
                    if (candidate.trySetAccessible()) {
                        return candidate;
                    }
                }
            }
            return NO_MODEL;
        });

        if (field == NO_MODEL) {
            return null;
        }

        try {
            Object model = field.get(box);
            return CitadelModel.isCitadelModel(model) ? model : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static final Map<Class<?>, java.lang.reflect.Field> BACK_TO_MODEL = new java.util.concurrent.ConcurrentHashMap<>();

    /** Stands for "this kind of piece keeps no way back", since a map will not hold null. */
    private static final java.lang.reflect.Field NO_MODEL;

    static {
        try {
            NO_MODEL = CitadelModels.class.getDeclaredField("BACK_TO_MODEL");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * What was resolved for this exact model, or null when it is one nothing was resolved for - a model
     * the renderer built later, or one this never found.
     */
    @Nullable
    public static Resolved get(@Nullable Object model) {
        return model == null ? null : BY_MODEL.get(model);
    }

    /**
     * Every Citadel model this renderer could draw with: the one it is holding now, followed by every
     * other it keeps a reference to.
     * <p>
     * Read off the renderer's own fields, because there is no other way to ask - the choice between
     * them is made inside {@code getModel()} from the entity being drawn, which is not something that
     * can be enumerated. A field that turns out not to be a model is simply not one of the answers.
     */
    @NotNull
    @SuppressWarnings("rawtypes")
    public static List<Object> candidates(@NotNull EntityRenderer renderer, @Nullable Object current) {
        List<Object> models = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        if (CitadelModel.isCitadelModel(current) && seen.add(current)) {
            models.add(current);
        }

        for (Class<?> type = renderer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (!field.trySetAccessible()) continue;

                    collect(field.get(renderer), models, seen, 0);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field that will not be read is one model fewer, not a failure
                }
            }
        }

        return models;
    }

    /** How far inside a field this will look for a model. Deep enough for a map of lists, and no deeper. */
    private static final int DEPTH_LIMIT = 3;

    /** How many entries of one collection are worth looking through. */
    private static final int WIDTH_LIMIT = 256;

    /**
     * Takes the models out of whatever a renderer keeps them in.
     * <p>
     * A renderer that draws one mob holds its model in a field, and looking at the fields was enough. A
     * renderer that draws a family of them holds a map instead, keyed by whatever tells them apart:
     * Alex's Caves keeps {@code HashMap<Type, ACBoatModel>} for its boats and one plain field for the
     * chest that sits on top. Only the field was found, so every boat was written out with the chest's
     * shape wearing the boat's picture, and arrived looking like nothing at all.
     */
    private static void collect(@Nullable Object value, List<Object> models, Set<Object> seen, int depth) {
        if (value == null || depth > DEPTH_LIMIT || (!(value instanceof Iterable) && !(value instanceof Map)
            && !(value instanceof Object[]) && !CitadelModel.isCitadelModel(value))) {
            return;
        }

        if (CitadelModel.isCitadelModel(value)) {
            if (seen.add(value)) {
                models.add(value);
            }
            return;
        }

        // Guarded and counted: a renderer may hold a list of anything at all, including one that is
        // being written to on another thread
        try {
            int width = 0;
            for (Object held : value instanceof Map<?, ?> map ? map.values()
                : value instanceof Object[] array ? java.util.Arrays.asList(array) : (Iterable<?>) value) {
                if (++width > WIDTH_LIMIT) {
                    return;
                }
                collect(held, models, seen, depth + 1);
            }
        } catch (RuntimeException e) {
            // Whatever was read so far stands
        }
    }

    /**
     * A name to file a model's parts under, standing in for the {@code ModelLayerLocation} a vanilla
     * model would have.
     * <p>
     * The class name alone is not enough. A cave centipede is three entities - a head, a body and a
     * tail - and all three are drawn by {@code ModelCaveCentipede} on one texture, built with a number
     * saying which segment to assemble. Naming all three after the class filed their parts in one
     * place, where they overwrote each other part for part: whichever segment was generated last won,
     * and the other two came out wearing its geometry or, where it had fewer parts than they did,
     * wearing nothing at all. That is why the centipede had no head.
     * <p>
     * So the shape of the model is folded in as well. Two models built the same way still share a
     * name - they are interchangeable, and sharing is what lets a model the renderer built later find
     * the parts generated for its twin - while two built differently no longer can.
     */
    public static String layerOf(@NotNull Object model, @NotNull List<CitadelPart> roots) {
        String name = model.getClass().getSimpleName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (name.isEmpty()) {
            name = "citadel";
        }
        return name + "_" + Integer.toHexString(shapeOf(roots));
    }

    /**
     * A number standing for how a model is put together - which parts hang off which, and the boxes
     * each one carries. Two models of one class that assemble different segments differ here.
     */
    private static int shapeOf(List<CitadelPart> roots) {
        int hash = 1;
        for (CitadelPart root : roots) {
            hash = hash * 31 + shapeOf(root);
        }
        return hash;
    }

    private static int shapeOf(CitadelPart part) {
        int hash = part.id;

        for (Object cube : part.cubes) {
            hash = hash * 31 + 7;
            for (Object quad : CitadelModel.quadsOf(cube)) {
                for (Object vertex : CitadelModel.verticesOf(quad)) {
                    var pos = CitadelModel.positionOf(vertex);
                    if (pos != null) {
                        // Rounded, so a value that is only a rounding step different between two runs
                        // cannot rename a whole model's worth of parts
                        hash = hash * 31 + Math.round(pos.x() * 16);
                        hash = hash * 31 + Math.round(pos.y() * 16);
                        hash = hash * 31 + Math.round(pos.z() * 16);
                    }
                }
            }
        }

        for (CitadelPart child : part.children) {
            hash = hash * 31 + shapeOf(child);
        }

        return hash;
    }
}
