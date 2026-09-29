package me.drex.polymerpatcher.entity;

import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.extras.api.format.atlas.AtlasAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelTransformation;
import me.drex.polymerpatcher.duck.IModelPart;
import me.drex.polymerpatcher.mixin.client.ModelPartAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.math.Quadrant;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.BiConsumer;

public record PolyModelInstance<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>>(
    EntityRenderer<Entity, RenderState> renderer,
    ModelLayerLocation modelLayer,
    List<ModelPart> allParts,
    Identifier texture
) {

    public static <Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> PolyModelInstance<Entity, RenderState, Model> create(
        EntityRenderer<Entity, RenderState> renderer,
        ModelLayerLocation modelLayer,
        List<ModelPart> allParts,
        Identifier texture
    ) {
        return new PolyModelInstance<>(renderer, modelLayer, allParts, texture);
    }

    public void generateAssets(BiConsumer<String, byte[]> writer, AtlasAsset.Builder atlas) {
        // A renderer that draws an item rather than a model of its own has neither a layer nor a
        // texture, and there is nothing here to write for it - the item already has a model
        if (texture == null || modelLayer == null) {
            return;
        }
        atlas.single(texture);

        int id = 0;

        for (var part : allParts) {
            if (part.isEmpty()) continue;
            ((IModelPart) (Object) part).polymer_patcher$setId(id);
            ((IModelPart) (Object) part).polymer_patcher$setModelLayerLocation(modelLayer);

            var modelId = texture.withSuffix("/" + ModelLayerNames.of(modelLayer) + "/part_" + (id++));
            var model = ModelAsset.builder();
            model.texture("txt", texture);
            model.textureReference("particle", "txt");

            for (ModelPart.Cube cuboid : ((ModelPartAccessor) (Object) part).getCubes()) {
                for (var quad : cuboid.polygons) {
                    var min = new Vector3f(Float.POSITIVE_INFINITY);
                    var max = new Vector3f(Float.NEGATIVE_INFINITY);
                    ModelPart.Vertex v1 = quad.vertices()[0];
                    ModelPart.Vertex v2 = quad.vertices()[0];

                    for (var vert : quad.vertices()) {
                        var pos = new Vector3f(vert.x(), vert.y(), vert.z());
                        min.min(pos);
                        max.max(pos);
                    }

                    for (var vert : quad.vertices()) {
                        var pos = new Vector3f(vert.x(), vert.y(), vert.z());
                        if (min.equals(pos)) {
                            v1 = vert;
                        }
                        if (max.equals(pos)) {
                            v2 = vert;
                        }
                    }


                    var b = ModelElement.builder(modelPoint(min), modelPoint(max));

                    var dir = Direction.getNearest((int) quad.normal().x(), (int) quad.normal().y(), (int) quad.normal().z(), null);

                    if ((dir.getAxisDirection() == Direction.AxisDirection.NEGATIVE) == (dir.getAxis() == Direction.Axis.Z)) {
                        dir = dir.getOpposite();
                    }

                    b.face(dir, uv(v1.u()), uv(v2.v()), uv(v2.u()), uv(v1.v()), "#txt", dir, Quadrant.R0, 0);
                    b.face(dir.getOpposite(), uv(v2.u()), uv(v2.v()), uv(v1.u()), uv(v1.v()), "#txt", dir.getOpposite(), Quadrant.R0, 0);


                    model.element(b.build());
                }
            }

            model.transformation(ItemDisplayContext.FIXED, new ModelTransformation(new Vec3(0, 180, 0), Vec3.ZERO, new Vec3(4, 4, 4)));

            writer.accept(AssetPaths.model(modelId) + ".json", model.build().toBytes());

            // FactoryTools normally writes the item definition which points an ItemDisplay's stack
            // at this model. It intentionally leaves the minecraft namespace alone, though, because
            // that namespace normally belongs to vanilla. A mod is allowed to register there (the
            // FallDrop cushion does), and in that case the generated geometry and texture both exist
            // while the item displayed in the world has no definition and appears missing/untextured.
            // Writing the small bridge explicitly is harmless for ordinary mod namespaces and makes
            // namespace choice irrelevant to every model-layer entity, not only cushions.
            writer.accept("assets/" + modelId.getNamespace() + "/items/-/" + modelId.getPath() + ".json",
                new eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset(
                    new eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel(modelId,
                        java.util.List.of(new eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource(0xFFFFFF))),
                    eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset.Properties.DEFAULT).toBytes());
        }
    }

    /**
     * A texture coordinate the game will actually bake.
     * <p>
     * A face whose uv leaves 0..16 is refused, and refused in silence: the part it belonged to simply
     * never appears, while the parts either side of it draw as normal. What that looks like in the
     * world is a mob missing pieces, or a held item reduced to a scrap - a resistor shield as a chip of
     * untextured nothing, a gummy bear with limbs a fraction of their size.
     * <p>
     * Modded models routinely map a little past the texture size they declare. A raycat reaches 17
     * where the game stops at 16, and lost three parts of eight to it. Held to the edge instead: the
     * parts that overrun give up a sliver of texture, and every part is drawn.
     */
    private static float uv(float normalized) {
        return Math.clamp(normalized * 16.0F, 0.0F, 16.0F);
    }

    /**
     * Converts an entity-model vertex to an item-model coordinate accepted by a vanilla client.
     * Vanilla rejects an entire generated model when even one element extends outside -16..32.
     * Large modded model parts can cross that boundary (the Sculk Ghast does), so clipping the
     * exceptional outer edge preserves the rest of the part instead of losing it completely.
     */
    private static Vec3 modelPoint(Vector3f point) {
        return new Vec3(
            Math.clamp(point.x * 0.25D + 8.0D, -16.0D, 32.0D),
            Math.clamp(point.y * 0.25D + 8.0D, -16.0D, 32.0D),
            Math.clamp(point.z * 0.25D + 8.0D, -16.0D, 32.0D)
        );
    }

}
