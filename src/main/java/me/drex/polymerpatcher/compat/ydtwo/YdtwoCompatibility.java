package me.drex.polymerpatcher.compat.ydtwo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.mixin.client.ModelPartAccessor;
import me.drex.polymerpatcher.resources.ItemModelFallbacks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Yazz's Dungeons: Sequel.
 * <p>
 * The Twisted Shrieker is drawn entirely by its block renderer, and its block model is a particle and
 * nothing else. A block is always sent as a carrier - even to a player with the mod, whose numbering of
 * block states is not the server's - so it wore that empty model and vanished: there for a moment while
 * the player's own game guessed the placed block, then gone as soon as the server's answer arrived.
 * <p>
 * The renderer draws one model, at one fixed transform, so its shape is written into the block model
 * instead: the mod's own cubes and texture, put where the renderer would have put them.
 */
public final class YdtwoCompatibility {
    private static final String MOD = "ydtwo";
    private static final String SHRIEKER_MODEL = "com.yd2.client.model.block.TwistedShriekerModel";
    private static final String SHRIEKER_TEXTURE = "ydtwo:block/twisted_shieker";
    private static final String LANTERN_ITEM = "com.yd2.ydtwo.item.WraithlightLanternItem";

    private YdtwoCompatibility() {
    }

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded(MOD)) {
            return;
        }
        bridgeLantern();
        // The block says it is invisible because its renderer draws it; it has a model of its own now
        Identifier shrieker = Identifier.fromNamespaceAndPath(MOD, "twisted_shrieker");
        me.drex.polymerpatcher.block.BlockPresentationRules.registerModelled(state ->
            shrieker.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(YdtwoCompatibility::writeModels);
    }

    /** The lantern picks its full or empty model by asking whether it holds souls, which only the mod can ask. */
    private static void bridgeLantern() {
        try {
            Class<?> lantern = Class.forName(LANTERN_ITEM);
            Method hasSouls = lantern.getMethod("hasSouls", ItemStack.class);
            ItemModelFallbacks.bridgeCondition(Identifier.fromNamespaceAndPath(MOD, "has_souls"), stack -> {
                try {
                    return lantern.isInstance(stack.getItem()) && (boolean) hasSouls.invoke(null, stack);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            });
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read how a Wraithlight Lantern knows it is full; it will always look empty", e);
        }
    }

    private static void writeModels(ResourcePackBuilder builder) {
        if (!(builder instanceof DefaultRPBuilder<?> pack)) {
            return;
        }
        // After the mod's own assets have been copied in, so this one is not written over by the empty original
        pack.buildEvent.register(credits -> {
            JsonObject shrieker = shriekerModel();
            if (shrieker != null) {
                pack.addData("assets/ydtwo/models/block/twisted_shrieker.json", DefaultRPBuilder.GSON.toJson(shrieker).getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    private static @Nullable JsonObject shriekerModel() {
        ModelPart root;
        try {
            LayerDefinition layer = (LayerDefinition) Class.forName(SHRIEKER_MODEL).getMethod("createBodyLayer").invoke(null);
            root = layer.bakeRoot();
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read the Twisted Shrieker's model; it will stay invisible", e);
            return null;
        }

        JsonArray elements = new JsonArray();
        addPart(root, 0, 0, 0, elements);
        if (elements.isEmpty()) {
            return null;
        }

        JsonObject textures = new JsonObject();
        textures.addProperty("particle", SHRIEKER_TEXTURE);
        textures.addProperty("t", SHRIEKER_TEXTURE);
        JsonObject model = new JsonObject();
        // The ordinary block's hand and inventory poses, since the item is shown with this model too
        model.addProperty("parent", "minecraft:block/block");
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    private static void addPart(ModelPart part, float x, float y, float z, JsonArray elements) {
        if (part.xRot != 0 || part.yRot != 0 || part.zRot != 0) {
            // An element turns about one axis at a time. The teeth turn about all three, and are left out
            return;
        }
        x += part.x;
        y += part.y;
        z += part.z;
        for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).getCubes()) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                JsonObject element = element(polygon, x, y, z);
                if (element != null) {
                    elements.add(element);
                }
            }
        }
        for (ModelPart child : ((ModelPartAccessor) (Object) part).getChildren().values()) {
            addPart(child, x, y, z, elements);
        }
    }

    /**
     * One face of a cube, as a block model element.
     * <p>
     * The renderer moves to (0.5, 1.5, 0.5), turns half way round and flips upside down, which in pixels
     * takes a point (x, y, z) of the model to (8 - x, 24 - y, 8 + z) in the block.
     */
    private static @Nullable JsonObject element(ModelPart.Polygon polygon, float ox, float oy, float oz) {
        ModelPart.Vertex[] vertices = polygon.vertices();
        float[][] points = new float[vertices.length][];
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < vertices.length; i++) {
            ModelPart.Vertex vertex = vertices[i];
            points[i] = new float[]{8 - (ox + vertex.x()), 24 - (oy + vertex.y()), 8 + (oz + vertex.z())};
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], points[i][axis]);
                max[axis] = Math.max(max[axis], points[i][axis]);
            }
        }
        int flatAxes = 0;
        for (int axis = 0; axis < 3; axis++) {
            if (max[axis] - min[axis] < 1.0E-4F) {
                flatAxes++;
            }
        }
        if (flatAxes != 1) {
            // The edges of a box with no depth, which have no area to draw
            return null;
        }

        Vector3fc normal = polygon.normal();
        Direction face = Direction.getApproximateNearest(-normal.x(), -normal.y(), normal.z());

        // Which way the texture runs across this face of a block: the axis and sign of u, then of v
        int uAxis, uSign, vAxis, vSign;
        switch (face) {
            case NORTH -> { uAxis = 0; uSign = -1; vAxis = 1; vSign = -1; }
            case SOUTH -> { uAxis = 0; uSign = 1; vAxis = 1; vSign = -1; }
            case WEST -> { uAxis = 2; uSign = 1; vAxis = 1; vSign = -1; }
            case EAST -> { uAxis = 2; uSign = -1; vAxis = 1; vSign = -1; }
            case UP -> { uAxis = 0; uSign = 1; vAxis = 2; vSign = 1; }
            default -> { uAxis = 0; uSign = 1; vAxis = 2; vSign = -1; }
        }
        int first = -1, last = -1;
        for (int i = 0; i < points.length; i++) {
            float u = points[i][uAxis] * uSign, v = points[i][vAxis] * vSign;
            if (atEnd(u, min[uAxis] * uSign, max[uAxis] * uSign, false) && atEnd(v, min[vAxis] * vSign, max[vAxis] * vSign, false)) {
                first = i;
            }
            if (atEnd(u, min[uAxis] * uSign, max[uAxis] * uSign, true) && atEnd(v, min[vAxis] * vSign, max[vAxis] * vSign, true)) {
                last = i;
            }
        }
        if (first < 0 || last < 0) {
            return null;
        }

        JsonArray uv = new JsonArray();
        uv.add(vertices[first].u() * 16);
        uv.add(vertices[first].v() * 16);
        uv.add(vertices[last].u() * 16);
        uv.add(vertices[last].v() * 16);
        JsonObject faceJson = new JsonObject();
        faceJson.add("uv", uv);
        faceJson.addProperty("texture", "#t");
        JsonObject faces = new JsonObject();
        faces.add(face.getSerializedName(), faceJson);

        JsonObject element = new JsonObject();
        element.add("from", point(min));
        element.add("to", point(max));
        element.add("faces", faces);
        return element;
    }

    /** Whether a coordinate, already turned to run the way the texture does, is at its start or its end. */
    private static boolean atEnd(float value, float a, float b, boolean end) {
        float target = end ? Math.max(a, b) : Math.min(a, b);
        return Math.abs(value - target) < 1.0E-4F;
    }

    private static JsonArray point(float[] point) {
        JsonArray out = new JsonArray();
        for (float coordinate : point) {
            out.add(Math.max(-16, Math.min(32, coordinate)));
        }
        return out;
    }
}
