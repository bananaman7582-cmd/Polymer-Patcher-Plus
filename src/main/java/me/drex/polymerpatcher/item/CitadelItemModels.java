package me.drex.polymerpatcher.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.citadel.CitadelModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModels;
import me.drex.polymerpatcher.entity.citadel.CitadelPart;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Builds a real shape for an item whose shape only exists in a mod's own drawing code.
 * <p>
 * A few items are not described by a model at all. Their model file carries the transforms - how the
 * thing sits in a hand, how big it is on the hotbar - and nothing else, and the shape itself is drawn
 * by a piece of the mod's Java that a server never runs. Alex's Caves does this for its gauntlet, its
 * shield, its spears and its bow. What reaches a stranger is therefore the fallback of last resort: a
 * flat square wearing whatever texture could be found, which is what a paper-thin gauntlet looks like.
 * <p>
 * The shape is not really missing, though - it is a Citadel model sitting in a class beside the item,
 * of exactly the kind this mod already reads for mobs. So it is read the same way, flattened into one
 * model, and written into the item's own file: the mod's transforms are kept exactly as they are, and
 * only the geometry is added where there was none.
 * <p>
 * Flattened, because an item is one model and a Citadel model is a jointed skeleton. Each piece is
 * taken at the pose the model file gives it, its corners moved to where that pose puts them, and
 * written out as its own small box. A jointed model cannot animate in an item frame anyway, so nothing
 * is lost that an item could have shown.
 */
public final class CitadelItemModels {

    private CitadelItemModels() {
    }

    private static final float PIXELS_PER_BLOCK = 16.0F;

    /** Where each mod keeps its models, learned from the ones already read for its mobs. */
    private static final Map<String, String> PACKAGES = new ConcurrentHashMap<>();

    /** Where this mod keeps its model classes, or null where none of its models has been seen yet. */
    @Nullable
    public static String modelPackage(String namespace) {
        return PACKAGES.get(namespace);
    }

    /**
     * Remembers where a mod keeps its model classes, so an item's can be found beside its mobs'.
     */
    public static void noteModelPackage(Object model) {
        Class<?> type = model.getClass();
        String name = type.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (name.contains("." + id.replace('-', '_') + ".") || name.toLowerCase(Locale.ROOT).contains("." + id.replace("-", "") + ".")) {
                PACKAGES.putIfAbsent(id, name.substring(0, lastDot));
                return;
            }
        }
    }

    /**
     * Writes a shape for this item, and says which model to point it at, or null where there is none.
     */
    @Nullable
    public static Identifier bake(ResourcePackBuilder builder, String namespace, String itemPath) {
        return bake(builder, namespace, itemPath, "", null);
    }

    /**
     * The same, for a shape the mod's renderer only ever draws in a pose of its own - a bow at full draw.
     * The model is posed before its parts are read, and the shape is written under a name of its own so
     * that the resting one stays where it was.
     */
    @Nullable
    public static Identifier bake(ResourcePackBuilder builder, String namespace, String itemPath,
                                  String suffix, @Nullable Consumer<Object> pose) {
        Object model = modelFor(namespace, itemPath);
        if (model == null) {
            return null;
        }

        if (pose != null) {
            try {
                pose.accept(model);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not pose {}:{} for its {} shape", namespace, itemPath, suffix, e);
                return null;
            }
        }

        return write(builder, namespace, itemPath, suffix, model);
    }

    /**
     * The same, for a model the mod's own renderer has already posed - which is the only way to get a
     * pose nobody has worked out how to ask for.
     * <p>
     * Taken as it stands, so it has to be written out while it is still in that pose: these models are
     * kept one per class and posed in place, so the next thing to draw one moves the same object.
     */
    @Nullable
    public static Identifier bakeAsDrawn(ResourcePackBuilder builder, String namespace, String itemPath,
                                         String suffix, Object model) {
        return write(builder, namespace, itemPath, suffix, model);
    }

    @Nullable
    private static Identifier write(ResourcePackBuilder builder, String namespace, String itemPath,
                                    String suffix, Object model) {
        Identifier texture = textureFor(namespace, itemPath);
        if (texture == null) {
            PolymerPatcher.LOGGER.debug("Found the shape of {}:{} but no picture to put on it", namespace, itemPath);
            return null;
        }

        JsonArray elements = new JsonArray();
        float geometryScale = HeldItemPresentations.geometryScale(namespace, itemPath);
        try {
            CitadelModels.Resolved resolved = CitadelModels.resolve(model);
            PoseStack poseStack = new PoseStack();
            for (CitadelPart root : resolved.roots()) {
                root.visit(poseStack, (part, matrix) -> addPart(elements, part, matrix, geometryScale));
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read the shape of {}:{}", namespace, itemPath, e);
            return null;
        }

        if (elements.isEmpty()) {
            return null;
        }

        // Started from the mod's own file so its transforms survive untouched - where the thing sits in
        // a hand, how it is angled on the hotbar. Only the shape is added
        // A renderer that draws the model a second time for its glowing parts has that pass folded into
        // the one picture a model file can wear; see TexturePasses
        texture = me.drex.polymerpatcher.resources.TexturePasses.over(builder, texture);

        JsonObject built = baseModel(builder, namespace, itemPath);
        JsonObject textures = built.has("textures") && built.get("textures").isJsonObject()
            ? built.getAsJsonObject("textures")
            : new JsonObject();
        textures.addProperty("txt", texture.toString());
        if (!textures.has("particle")) {
            textures.addProperty("particle", texture.toString());
        }
        built.add("textures", textures);
        built.add("elements", elements);
        built.remove("parent");

        // A picture only reaches an item model through the block atlas, and only textures a blockstate or a
        // mob model asked for are put in it. These were asked for by neither: a thrown spear has a mob model
        // and so its picture was already there, which is why spears looked right and the dreadbow - an item
        // and nothing else - was a magenta and black shape in the hand. Every shape built here says so itself
        me.drex.polymerpatcher.resources.ResourcePackGenerator.EXTRA_SPRITES.add(texture);

        Identifier modelId = PolymerPatcher.id("item_shape/" + namespace + "/" + itemPath + suffix);
        // Alex's Caves draws the coloured light on two of its items as a second texture pass. A
        // vanilla model cannot run that renderer, so its compatibility layer writes equivalent
        // flattened red and blue models beside this neutral shape.
        HeldItemPresentations.addTextureVariants(
            builder, namespace, itemPath, built, modelId);
        builder.addData("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json",
            built.toString().getBytes(StandardCharsets.UTF_8));
        return modelId;
    }

    /**
     * Where every piece of a model is, as one list of numbers.
     * <p>
     * Two of these from the same model are comparable: equal means the model is posed identically, and
     * the largest difference between them is how far the biggest piece moved. That is all that is needed
     * to watch a motion - to say when it starts, when it has finished, and which moments along the way
     * are different enough from each other to be worth keeping as separate shapes.
     */
    public static float @Nullable [] shape(Object model) {
        try {
            CitadelModels.Resolved resolved = CitadelModels.resolve(model);
            java.util.List<Float> numbers = new java.util.ArrayList<>();
            PoseStack poseStack = new PoseStack();
            float[] row = new float[16];
            for (CitadelPart root : resolved.roots()) {
                root.visit(poseStack, (part, matrix) -> {
                    matrix.get(row);
                    for (float number : row) {
                        numbers.add(number);
                    }
                });
            }

            if (numbers.isEmpty()) {
                return null;
            }

            float[] shape = new float[numbers.size()];
            for (int number = 0; number < shape.length; number++) {
                shape[number] = numbers.get(number);
            }
            return shape;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read where the pieces of {} are", model.getClass(), e);
            return null;
        }
    }

    /** How far the furthest piece is from where it was in the other shape, or -1 if they cannot be compared. */
    public static float apart(float @Nullable [] one, float @Nullable [] other) {
        if (one == null || other == null || one.length != other.length) {
            return -1.0F;
        }

        float furthest = 0.0F;
        for (int number = 0; number < one.length; number++) {
            furthest = Math.max(furthest, Math.abs(one[number] - other[number]));
        }
        return furthest;
    }

    private static JsonObject baseModel(ResourcePackBuilder builder, String namespace, String itemPath) {
        byte[] data = builder.getDataOrSource("assets/" + namespace + "/models/item/" + itemPath + ".json");
        if (data != null) {
            try {
                return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Throwable ignored) {
            }
        }
        return new JsonObject();
    }

    /** One piece of the skeleton, written out where its pose puts it. */
    private static void addPart(JsonArray elements, CitadelPart part, Matrix4f matrix, float geometryScale) {
        Tilt tilt = tiltOf(matrix);
        for (Object cube : part.cubes) {
            for (Object quad : CitadelModel.quadsOf(cube)) {
                JsonObject element = quadElement(quad, matrix, geometryScale, tilt);
                if (element != null) {
                    elements.add(element);
                }
            }
        }
    }

    /**
     * A turn a model file can make for itself, taken back out of a part's pose so it is not baked flat.
     * <p>
     * Every piece here is written as a box, and a box has no angle: it is described by two opposite
     * corners and nothing else. A part turned by a quarter turn is still a box - its corners simply swap
     * places - so a spear, whose head is turned ninety degrees, comes out exact. A part turned by
     * anything else is not, and taking its corners gives the box that <i>contains</i> the turned piece,
     * which is fatter than the piece in every direction. Alex's Caves' dreadbow is built from limbs at
     * forty-five degrees, and that is why it arrived looking melted.
     * <p>
     * A model file can turn one element, about one axis, by one of five angles - and forty-five is one of
     * them. So where a part's turn is one of those (give or take whole quarter turns, which boxes survive
     * anyway), the turn is written into the element and undone before the corners are taken. What is left
     * is the shape the mod drew.
     */
    private record Tilt(Direction.Axis axis, float degrees, Vector3f origin, Matrix4f undo) {
    }

    /** The angles a model file may turn an element by, together with leaving it alone. */
    private static final float[] ALLOWED_ANGLES = {-45.0F, -22.5F, 0.0F, 22.5F, 45.0F};

    /** How near a rebuilt turn has to be to the real one to be believed. */
    private static final float MATRIX_TOLERANCE = 1.0E-3F;

    /** Tiny decorative tilts are more faithfully represented by snapping the face back to a box. */
    private static final float SMALL_TILT_SNAP = 5.0F;

    /**
     * The turn to write into this part's elements, or null where the corners already tell the truth.
     */
    private static @Nullable Tilt tiltOf(Matrix4f matrix) {
        Matrix3f rotation = matrix.get3x3(new Matrix3f());

        for (Direction.Axis axis : Direction.Axis.values()) {
            double radians = switch (axis) {
                case X -> Math.atan2(rotation.m12, rotation.m11);
                case Y -> Math.atan2(rotation.m20, rotation.m00);
                case Z -> Math.atan2(rotation.m01, rotation.m00);
            };

            if (!isTurnAbout(rotation, axis, (float) radians)) {
                continue;
            }

            // Whole quarter turns are left in the corners, which survive them exactly; only what is left
            // over has to be written down
            float degrees = (float) Math.toDegrees(radians);
            float leftOver = degrees - 90.0F * Math.round(degrees / 90.0F);

            // The nearest turn a model file is allowed to hold. Where the part's own turn is one of them
            // this is exact, which is what makes the dreadbow's forty-five degree limbs come out as they
            // were drawn. Where it is not - a bow's arms bend by twenty degrees as it is pulled - the few
            // degrees between the two are left in the corners, and that is a far smaller error than
            // leaving the whole turn there, which is what happened before
            float allowed = nearestAllowed(leftOver);
            if (allowed == 0.0F) {
                // A slight tilt which is left in the corners expands every paper-thin face to its
                // axis-aligned bounding box. On almost-square parts that creates visible gaps and a
                // warped rim (the resistor shield is turned by 87.5 degrees). Snap only small residual
                // decoration back to a true box; larger unsupported turns keep their honest bounds.
                if (Math.abs(leftOver) > SMALL_TILT_SNAP) {
                    return null;
                }
            }

            Vector3f origin = matrix.getTranslation(new Vector3f()).mul(PIXELS_PER_BLOCK);
            Matrix4f undo = new Matrix4f();
            float removed = allowed == 0.0F ? leftOver : allowed;
            switch (axis) {
                case X -> undo.rotateX((float) Math.toRadians(-removed));
                case Y -> undo.rotateY((float) Math.toRadians(-removed));
                case Z -> undo.rotateZ((float) Math.toRadians(-removed));
            }
            return new Tilt(axis, allowed, origin, undo);
        }

        return null;
    }

    /** The angle a model file can hold that is nearest this one; zero where it is nearest no turn at all. */
    private static float nearestAllowed(float degrees) {
        float best = 0.0F;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (float allowed : ALLOWED_ANGLES) {
            float distance = Math.abs(degrees - allowed);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = allowed;
            }
        }
        return best;
    }

    /** Whether this really is a turn about one axis, rather than something a single angle cannot describe. */
    private static boolean isTurnAbout(Matrix3f rotation, Direction.Axis axis, float radians) {
        Matrix3f rebuilt = new Matrix3f();
        switch (axis) {
            case X -> rebuilt.rotationX(radians);
            case Y -> rebuilt.rotationY(radians);
            case Z -> rebuilt.rotationZ(radians);
        }

        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                if (Math.abs(rebuilt.get(column, row) - rotation.get(column, row)) > MATRIX_TOLERANCE) {
                    return false;
                }
            }
        }
        return true;
    }

    @Nullable
    private static JsonObject quadElement(Object quad, Matrix4f matrix, float geometryScale, @Nullable Tilt tilt) {
        Object[] vertices = CitadelModel.verticesOf(quad);
        Vector3fc normal = CitadelModel.normalOf(quad);
        if (vertices.length == 0 || normal == null) {
            return null;
        }

        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Object first = vertices[0];
        Object last = vertices[0];
        float nearest = Float.POSITIVE_INFINITY;
        float furthest = Float.NEGATIVE_INFINITY;

        for (Object vertex : vertices) {
            Vector3fc local = CitadelModel.positionOf(vertex);
            if (local == null) {
                return null;
            }
            // Citadel draws a vertex at a sixteenth of its stored position, under a matrix whose
            // translations are already in blocks - so it is scaled the same way here and brought back to
            // sixteenths after. Left as it was, every pivot counted for a sixteenth of what it should, and a
            // spear's crosspiece sat halfway down its shaft
            Vector3f moved = matrix.transformPosition(new Vector3f(local).div(PIXELS_PER_BLOCK)).mul(PIXELS_PER_BLOCK);
            if (tilt != null) {
                // Measured as if the part had not been turned, because the element carries the turn itself
                moved = tilt.undo().transformPosition(moved.sub(tilt.origin())).add(tilt.origin());
            }
            min.min(moved);
            max.max(moved);

            float along = moved.x + moved.y + moved.z;
            if (along < nearest) {
                nearest = along;
                first = vertex;
            }
            if (along > furthest) {
                furthest = along;
                last = vertex;
            }
        }

        // Centred on the middle of the block and reduced enough to stay inside a vanilla model's
        // element bounds. The item definition restores this scale after applying the original model
        // and renderer transforms.
        JsonObject element = new JsonObject();
        element.add("from", box(min, geometryScale));
        element.add("to", box(max, geometryScale));

        if (tilt != null && tilt.degrees() != 0.0F) {
            JsonObject turn = new JsonObject();
            turn.add("origin", box(new Vector3f(tilt.origin()), geometryScale));
            turn.addProperty("axis", tilt.axis().getSerializedName());
            turn.addProperty("angle", tilt.degrees());
            element.add("rotation", turn);
        }

        Vector3f facing = matrix.transformDirection(new Vector3f(normal));
        if (tilt != null) {
            // The face is named in the same unturned frame the corners were measured in
            facing = tilt.undo().transformDirection(facing);
        }
        Direction direction = nearest(facing);
        if ((direction.getAxisDirection() == Direction.AxisDirection.NEGATIVE) == (direction.getAxis() == Direction.Axis.Z)) {
            direction = direction.getOpposite();
        }

        // Held to 0..16, for the same reason the mob models are: a face whose uv leaves that range is
        // refused at bake time without a word, taking its part with it. A held item has few parts to
        // spare - lose two of a resistor shield's and what is left is the chip of nothing it was
        float u1 = uv(CitadelModel.textureU(first));
        float v1 = uv(CitadelModel.textureV(first));
        float u2 = uv(CitadelModel.textureU(last));
        float v2 = uv(CitadelModel.textureV(last));

        JsonObject faces = new JsonObject();
        // Both ways round, because a single quad has no thickness and would otherwise vanish when
        // looked at from behind
        faces.add(direction.getName(), face(u1, v2, u2, v1));
        faces.add(direction.getOpposite().getName(), face(u2, v2, u1, v1));
        element.add("faces", faces);
        return element;
    }

    /**
     * The face a direction points most nearly along.
     * <p>
     * Worked out by hand rather than handed to the game, which wants whole numbers - a normal that has
     * been turned by a pose is all fractions, and rounding those to whole numbers turns most of them
     * into zero and loses the direction entirely.
     */
    private static Direction nearest(Vector3f facing) {
        Direction nearest = Direction.NORTH;
        float best = Float.NEGATIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            var step = direction.getUnitVec3i();
            float along = facing.x * step.getX() + facing.y * step.getY() + facing.z * step.getZ();
            if (along > best) {
                best = along;
                nearest = direction;
            }
        }
        return nearest;
    }

    private static JsonArray box(Vector3f corner, float geometryScale) {
        JsonArray array = new JsonArray();
        array.add(corner.x * geometryScale + 8);
        array.add(corner.y * geometryScale + 8);
        array.add(corner.z * geometryScale + 8);
        return array;
    }

    /**
     * Builds a model class, which does not always have the no-argument constructor this used to
     * insist on.
     * <p>
     * A galena gauntlet's model takes a boolean saying which hand it is for. Asking only for the empty
     * constructor found nothing, the search gave up, and the gauntlet stayed the flat sprite every
     * item falls back to. Any constructor whose arguments can be answered with a default will do here:
     * only the shape is being read off the model, and the shape is the same either way.
     */
    @Nullable
    private static Object construct(Class<?> type) {
        java.lang.reflect.Constructor<?>[] all = type.getDeclaredConstructors();
        // Fewest arguments first, so the plainest constructor is tried before any that has to be guessed at
        java.util.Arrays.sort(all, java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount));

        for (java.lang.reflect.Constructor<?> constructor : all) {
            Class<?>[] params = constructor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = defaultFor(params[i]);
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            } catch (Throwable ignored) {
                // A constructor that will not take defaults is not the one; try the next
            }
        }
        return null;
    }

    /** Something harmless to hand a constructor that asks for one. */
    @Nullable
    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        // A reference argument gets nothing, which most models never touch while being built
        return null;
    }

    /** A texture coordinate the game will bake; see {@code PolyModelInstance#uv}. */
    private static float uv(float normalized) {
        return Math.clamp(normalized * 16.0F, 0.0F, 16.0F);
    }

    private static JsonObject face(float u1, float v1, float u2, float v2) {
        JsonArray uv = new JsonArray();
        uv.add(u1);
        uv.add(v1);
        uv.add(u2);
        uv.add(v2);

        JsonObject face = new JsonObject();
        face.add("uv", uv);
        face.addProperty("texture", "#txt");
        return face;
    }

    /**
     * The model class that draws this item, found beside the mod's own mob models by the item's name.
     */
    @Nullable
    private static Object modelFor(String namespace, String itemPath) {
        String where = PACKAGES.get(namespace);
        if (where == null) {
            return null;
        }

        StringBuilder camel = new StringBuilder();
        for (String word : itemPath.split("_")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }

        try {
            Class<?> type = Class.forName(where + "." + camel + "Model", true,
                CitadelItemModels.class.getClassLoader());
            Object model = construct(type);
            return model != null && CitadelModel.isCitadelModel(model) ? model : null;
        } catch (Throwable e) {
            // Most items have no such class, which is not a failure - it is the ordinary case
            return null;
        }
    }

    /** The picture the shape wears, which for these lives with the mob textures rather than the icons. */
    @Nullable
    private static Identifier textureFor(String namespace, String itemPath) {
        // The last of these is for a mod that gives a thing a folder of its own: the raygun keeps its
        // picture at entity/raygun/raygun.png, and looking only beside the other entity textures found
        // nothing, so it was drawn as a flat icon instead of the model it has
        for (String folder : new String[]{"entity/", "item/", "entity/" + itemPath + "/"}) {
            Identifier candidate = Identifier.fromNamespaceAndPath(namespace, folder + itemPath);
            if (ResourceHelper.getAsset(namespace, "textures/" + candidate.getPath() + ".png") != null) {
                return candidate;
            }
        }
        return null;
    }
}
