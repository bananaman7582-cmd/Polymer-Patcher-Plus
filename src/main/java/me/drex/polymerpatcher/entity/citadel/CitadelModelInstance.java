package me.drex.polymerpatcher.entity.citadel;

import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.extras.api.format.atlas.AtlasAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelTransformation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.math.Quadrant;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

import java.util.function.BiConsumer;

/**
 * The Citadel counterpart to {@link me.drex.polymerpatcher.entity.PolyModelInstance}: one entity's
 * model turned into a display model per part, plus the parts themselves ready to be posed.
 * <p>
 * The two build the same thing out of the same numbers. A Citadel box states its corners in pixels and
 * its texture coordinates as fractions of the whole texture, exactly as a vanilla one does, so a quad
 * converts the same way here as it does there.
 */
public record CitadelModelInstance<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>>(
    EntityRenderer<Entity, RenderState> renderer,
    String layer,
    List<CitadelPart> roots,
    Identifier texture
) {

    /**
     * @param roots the model's parts, resolved once by the caller so every texture a mob can wear
     *              shares one numbering - the number is what ties a posed part to its generated model
     */
    public static <Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> CitadelModelInstance<Entity, RenderState, Model> create(
        EntityRenderer<Entity, RenderState> renderer,
        CitadelModels.Resolved resolved,
        Identifier texture
    ) {
        return new CitadelModelInstance<>(renderer, resolved.layer(), resolved.roots(), texture);
    }

    /**
     * Where the model generated for a part lives, which is how a posed part finds it again.
     */
    public Identifier modelPath(int id) {
        return modelPath(texture, id);
    }

    /**
     * The same, for a texture only known once the mob is in front of you. A renderer picks its texture
     * per entity - a grizzly bear turns white in the snow - so the pose reads it back off the render
     * type rather than assuming the one this instance was built for.
     */
    public Identifier modelPath(Identifier texture, int id) {
        return modelPath(texture, layer, id);
    }

    /**
     * The same again, for a model other than the one this instance was built for - a renderer that
     * keeps several and picks between them draws through whichever it chose this tick, and its parts
     * are filed under that model's own name.
     */
    public Identifier modelPath(Identifier texture, String layer, int id) {
        return texture.withSuffix("/" + layer + "/part_" + id);
    }

    public List<CitadelPart> parts() {
        List<CitadelPart> parts = new ArrayList<>();
        for (CitadelPart root : roots) {
            root.flatten(parts);
        }
        return parts;
    }

    public void generateAssets(BiConsumer<String, byte[]> writer, AtlasAsset.Builder atlas) {
        atlas.single(texture);

        for (CitadelPart part : parts()) {
            if (part.id < 0) continue;

            var model = ModelAsset.builder();
            model.texture("txt", texture);
            model.textureReference("particle", "txt");

            for (Object cube : part.cubes) {
                for (Object quad : CitadelModel.quadsOf(cube)) {
                    ModelElement element = element(quad);
                    if (element != null) {
                        model.element(element);
                    }
                }
            }

            model.transformation(ItemDisplayContext.FIXED, new ModelTransformation(new Vec3(0, 180, 0), Vec3.ZERO, new Vec3(4, 4, 4)));

            writer.accept(AssetPaths.model(modelPath(part.id)) + ".json", model.build().toBytes());
        }
    }

    /**
     * One face of a box as a flat element carrying it on both sides, so it reads the same from behind.
     */
    private static ModelElement element(Object quad) {
        Object[] vertices = CitadelModel.verticesOf(quad);
        Vector3fc normal = CitadelModel.normalOf(quad);
        if (vertices.length == 0 || normal == null) {
            return null;
        }

        var min = new Vector3f(Float.POSITIVE_INFINITY);
        var max = new Vector3f(Float.NEGATIVE_INFINITY);

        for (Object vertex : vertices) {
            Vector3fc pos = CitadelModel.positionOf(vertex);
            if (pos == null) return null;
            min.min(pos);
            max.max(pos);
        }

        Object v1 = vertices[0];
        Object v2 = vertices[0];
        for (Object vertex : vertices) {
            Vector3fc pos = CitadelModel.positionOf(vertex);
            if (min.equals(pos)) {
                v1 = vertex;
            }
            if (max.equals(pos)) {
                v2 = vertex;
            }
        }

        var b = ModelElement.builder(new Vec3(min.x, min.y, min.z).scale(0.25).add(8), new Vec3(max.x, max.y, max.z).scale(0.25).add(8));

        var dir = Direction.getNearest((int) normal.x(), (int) normal.y(), (int) normal.z(), null);

        if ((dir.getAxisDirection() == Direction.AxisDirection.NEGATIVE) == (dir.getAxis() == Direction.Axis.Z)) {
            dir = dir.getOpposite();
        }

        // Held inside the texture, as everywhere else that writes one of these. A face whose uv leaves
        // 0..16 is refused at bake time without a word and takes its part with it - which is what left
        // a raycat as a handful of floating pieces long after the same fault was fixed elsewhere
        float u1 = uv(CitadelModel.textureU(v1));
        float v1v = uv(CitadelModel.textureV(v1));
        float u2 = uv(CitadelModel.textureU(v2));
        float v2v = uv(CitadelModel.textureV(v2));

        b.face(dir, u1, v2v, u2, v1v, "#txt", dir, Quadrant.R0, 0);
        b.face(dir.getOpposite(), u2, v2v, u1, v1v, "#txt", dir.getOpposite(), Quadrant.R0, 0);

        return b.build();
    }

    /** A texture coordinate the game will bake; see {@code PolyModelInstance#uv}. */
    private static float uv(float normalized) {
        return Math.clamp(normalized * 16.0F, 0.0F, 16.0F);
    }
}
