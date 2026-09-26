package me.drex.polymerpatcher.entity.geckolib;

import com.mojang.math.Quadrant;
import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.extras.api.format.atlas.AtlasAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelTransformation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The GeckoLib counterpart to {@link me.drex.polymerpatcher.entity.citadel.CitadelModelInstance}: one
 * entity's model turned into a display model per piece, plus the bones themselves ready to be posed.
 * <p>
 * The conversion is the Citadel one with a change of units. A GeckoLib box states its corners in
 * blocks where a Citadel or vanilla one states them in pixels; its texture coordinates are fractions
 * of the whole texture in both.
 */
public record GeckoLibModelInstance<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>>(
    Object renderer,
    String layer,
    List<GeckoLibBone> roots,
    Identifier texture
) {

    public static <Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> GeckoLibModelInstance<Entity, RenderState, Model> create(
        Object renderer,
        GeckoLibModel.Baked baked,
        List<GeckoLibBone> roots
    ) {
        return new GeckoLibModelInstance<>(renderer, layerOf(baked.modelId()), roots, baked.texture());
    }

    /**
     * The same model, filed under another texture the mob can wear.
     * <p>
     * A renderer picks its texture per mob, so the pieces have to exist under each one it might pick.
     * Everything else is shared: the same bones, numbered the same way, under the same layer name.
     */
    public GeckoLibModelInstance<Entity, RenderState, Model> withTexture(Identifier other) {
        return new GeckoLibModelInstance<>(renderer, layer, roots, other);
    }

    /**
     * A name to file this model's pieces under, standing in for the {@code ModelLayerLocation} a
     * vanilla model would have. The model file's own id is the only thing that distinguishes it, since
     * two mobs can share a texture and still be built from different models.
     */
    private static String layerOf(Identifier modelId) {
        String name = modelId.getPath().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return name.isEmpty() ? "geckolib" : name;
    }

    /**
     * Where the model generated for a piece lives, which is how a posed piece finds it again.
     */
    public Identifier modelPath(int id) {
        return modelPath(texture, id);
    }

    /**
     * The same, for a texture only known once the mob is in front of you - a renderer picks its texture
     * per entity, so the pose reads it back rather than assuming the one this instance was built for.
     */
    public Identifier modelPath(Identifier texture, int id) {
        return texture.withSuffix("/" + layer + "/part_" + id);
    }

    public List<GeckoLibBone> bones() {
        List<GeckoLibBone> bones = new ArrayList<>();
        for (GeckoLibBone root : roots) {
            root.flatten(bones);
        }
        return bones;
    }

    /**
     * The picture GeckoLib's glow layer lays over this one at full brightness, if the mob has one: the
     * texture's own name with {@code _glowmask} on the end, which is the name {@code AutoGlowingGeoLayer}
     * looks for. Null when there is none.
     */
    private Identifier glowmask() {
        Identifier glow = texture.withSuffix("_glowmask");
        try (var stream = me.drex.polymerpatcher.resources.ResourceHelper.getAsset(glow.getNamespace(),
            "textures/" + glow.getPath() + ".png").get()) {
            return stream != null ? glow : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public void generateAssets(BiConsumer<String, byte[]> writer, AtlasAsset.Builder atlas) {
        atlas.single(texture);
        // The glowing parts of the mob - Sculk Horde's are the sculk veins across nearly every one - drawn
        // a second time in the same model, a hair proud of the surface and lit from within, which is how
        // the glow layer draws them on a client
        Identifier glow = glowmask();
        if (glow != null) {
            atlas.single(glow);
        }

        for (GeckoLibBone bone : bones()) {
            for (GeckoLibBone.Piece piece : bone.pieces) {
                var model = ModelAsset.builder();
                model.texture("txt", texture);
                model.textureReference("particle", "txt");
                if (glow != null) {
                    model.texture("glow", glow);
                }

                for (Object cube : piece.cubes()) {
                    for (Object quad : GeckoLibModel.quadsOf(renderer, cube)) {
                        ModelElement element = element(quad, "#txt", 0.0F);
                        if (element != null) {
                            model.element(element);
                        }
                        if (glow != null) {
                            ModelElement glowing = element(quad, "#glow", GLOW_LIFT);
                            if (glowing != null) {
                                model.element(new ModelElement(glowing.from(), glowing.to(), glowing.faces(),
                                    glowing.rotation(), false, 15));
                            }
                        }
                    }
                }

                model.transformation(ItemDisplayContext.FIXED, new ModelTransformation(new Vec3(0, 180, 0), Vec3.ZERO, new Vec3(4, 4, 4)));

                writer.accept(AssetPaths.model(modelPath(piece.id())) + ".json", model.build().toBytes());
            }
        }
    }

    /**
     * One face of a box as a flat element carrying it on both sides, so it reads the same from behind.
     */
    /** How far the glowing copy of a face stands off the face, in model units: enough not to flicker. */
    private static final float GLOW_LIFT = 0.02F;

    private ModelElement element(Object quad, String textureReference, float lift) {
        List<Object> vertices = GeckoLibModel.verticesOf(renderer, quad);
        Vector3fc normal = GeckoLibModel.normalOf(renderer, quad);
        if (vertices.isEmpty() || normal == null) {
            return null;
        }

        var min = new Vector3f(Float.POSITIVE_INFINITY);
        var max = new Vector3f(Float.NEGATIVE_INFINITY);
        List<float[]> read = new ArrayList<>(vertices.size());

        for (Object vertex : vertices) {
            float[] values = GeckoLibModel.vertexOf(renderer, vertex);
            read.add(values);
            min.min(new Vector3f(values[0], values[1], values[2]));
            max.max(new Vector3f(values[0], values[1], values[2]));
        }

        float[] low = read.getFirst();
        float[] high = read.getFirst();
        for (float[] values : read) {
            if (min.x == values[0] && min.y == values[1] && min.z == values[2]) {
                low = values;
            }
            if (max.x == values[0] && max.y == values[1] && max.z == values[2]) {
                high = values;
            }
        }

        Vec3 offset = new Vec3(normal.x() * lift, normal.y() * lift, normal.z() * lift);
        var b = ModelElement.builder(corner(min).add(offset), corner(max).add(offset));

        var dir = Direction.getNearest((int) normal.x(), (int) normal.y(), (int) normal.z(), null);

        if ((dir.getAxisDirection() == Direction.AxisDirection.NEGATIVE) == (dir.getAxis() == Direction.Axis.Z)) {
            dir = dir.getOpposite();
        }

        // Held inside the texture, as everywhere else that writes one of these - a face whose uv leaves
        // 0..16 is refused at bake time in silence, and the part it belonged to simply never appears
        float u1 = uv(low[3]);
        float v1 = uv(low[4]);
        float u2 = uv(high[3]);
        float v2 = uv(high[4]);

        b.face(dir, u1, v2, u2, v1, textureReference, dir, Quadrant.R0, 0);
        b.face(dir.getOpposite(), u2, v2, u1, v1, textureReference, dir.getOpposite(), Quadrant.R0, 0);

        return b.build();
    }

    /** A texture coordinate the game will bake; see {@code PolyModelInstance#uv}. */
    private static float uv(float normalized) {
        return Math.clamp(normalized * 16.0F, 0.0F, 16.0F);
    }

    private static Vec3 corner(Vector3f position) {
        return new Vec3(
            position.x * GeckoLibModel.BLOCKS_TO_MODEL_UNITS + GeckoLibModel.MODEL_ORIGIN,
            position.y * GeckoLibModel.BLOCKS_TO_MODEL_UNITS + GeckoLibModel.MODEL_ORIGIN,
            position.z * GeckoLibModel.BLOCKS_TO_MODEL_UNITS + GeckoLibModel.MODEL_ORIGIN
        );
    }
}
