package me.drex.polymerpatcher.dump.data.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MaterialDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LayerDefinitionCodecs {
    private static final Codec<Vector3fc> VECTOR_3F = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.fieldOf("x").forGetter(Vector3fc::x),
        Codec.FLOAT.fieldOf("y").forGetter(Vector3fc::y),
        Codec.FLOAT.fieldOf("z").forGetter(Vector3fc::z)
    ).apply(instance, Vector3f::new));

    private static final Codec<UVPair> UV_PAIR = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.fieldOf("u").forGetter(UVPair::u),
        Codec.FLOAT.fieldOf("v").forGetter(UVPair::v)
    ).apply(instance, UVPair::new));

    private static final Codec<CubeDeformation> CUBE_DEFORMATION = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.fieldOf("growX").forGetter(deformation -> deformation.growX),
        Codec.FLOAT.fieldOf("growY").forGetter(deformation -> deformation.growY),
        Codec.FLOAT.fieldOf("growZ").forGetter(deformation -> deformation.growZ)
    ).apply(instance, CubeDeformation::new));

    private static final Codec<PartPose> PART_POSE = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.fieldOf("x").forGetter(PartPose::x),
        Codec.FLOAT.fieldOf("y").forGetter(PartPose::y),
        Codec.FLOAT.fieldOf("z").forGetter(PartPose::z),
        Codec.FLOAT.fieldOf("xRot").forGetter(PartPose::xRot),
        Codec.FLOAT.fieldOf("yRot").forGetter(PartPose::yRot),
        Codec.FLOAT.fieldOf("zRot").forGetter(PartPose::zRot),
        Codec.FLOAT.fieldOf("xScale").forGetter(PartPose::xScale),
        Codec.FLOAT.fieldOf("yScale").forGetter(PartPose::yScale),
        Codec.FLOAT.fieldOf("zScale").forGetter(PartPose::zScale)
    ).apply(instance, PartPose::new));

    private static final Codec<Set<Direction>> VISIBLE_FACES = Direction.CODEC.listOf().xmap(
        LayerDefinitionCodecs::directionSet,
        ArrayList::new
    );

    private static final Codec<CubeDefinition> CUBE_DEFINITION = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("comment").forGetter(cube -> Optional.ofNullable(cube.comment)),
        VECTOR_3F.fieldOf("origin").forGetter(cube -> cube.origin),
        VECTOR_3F.fieldOf("dimensions").forGetter(cube -> cube.dimensions),
        CUBE_DEFORMATION.fieldOf("grow").forGetter(cube -> cube.grow),
        Codec.BOOL.fieldOf("mirror").forGetter(cube -> cube.mirror),
        UV_PAIR.fieldOf("texCoord").forGetter(cube -> cube.texCoord),
        UV_PAIR.fieldOf("texScale").forGetter(cube -> cube.texScale),
        VISIBLE_FACES.fieldOf("visibleFaces").forGetter(cube -> cube.visibleFaces)
    ).apply(instance, LayerDefinitionCodecs::createCube));

    private static final Codec<PartDefinition> PART_DEFINITION = Codec.recursive(
        "PartDefinition",
        codec -> RecordCodecBuilder.create(instance -> instance.group(
            CUBE_DEFINITION.listOf().fieldOf("cubes").forGetter(part -> part.cubes),
            PART_POSE.fieldOf("partPose").forGetter(part -> part.partPose),
            Codec.unboundedMap(Codec.STRING, codec).fieldOf("children").forGetter(LayerDefinitionCodecs::children)
        ).apply(instance, LayerDefinitionCodecs::createPart))
    );

    private static final Codec<PartDefinition> MESH_DEFINITION = PART_DEFINITION.fieldOf("root").codec();

    private static final Codec<MaterialDefinition> MATERIAL_DEFINITION = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("xTexSize").forGetter(material -> material.xTexSize),
        Codec.INT.fieldOf("yTexSize").forGetter(material -> material.yTexSize)
    ).apply(instance, MaterialDefinition::new));

    public static final Codec<LayerDefinition> LAYER_DEFINITION = RecordCodecBuilder.create(instance -> instance.group(
        MESH_DEFINITION.fieldOf("mesh").forGetter(layer -> layer.mesh.getRoot()),
        MATERIAL_DEFINITION.fieldOf("material").forGetter(layer -> layer.material)
    ).apply(instance, LayerDefinitionCodecs::createLayer));

    private LayerDefinitionCodecs() {
    }

    private static Set<Direction> directionSet(List<Direction> directions) {
        Set<Direction> result = EnumSet.noneOf(Direction.class);
        result.addAll(directions);
        return result;
    }

    private static CubeDefinition createCube(
        Optional<String> comment,
        Vector3fc origin,
        Vector3fc dimensions,
        CubeDeformation deformation,
        boolean mirror,
        UVPair textureCoordinates,
        UVPair textureScale,
        Set<Direction> visibleFaces
    ) {
        // CubeDefinition's constructor places the UV offset before origin and dimensions.
        return new CubeDefinition(
            comment.orElse(null),
            textureCoordinates.u(),
            textureCoordinates.v(),
            origin.x(),
            origin.y(),
            origin.z(),
            dimensions.x(),
            dimensions.y(),
            dimensions.z(),
            deformation,
            mirror,
            textureScale.u(),
            textureScale.v(),
            visibleFaces
        );
    }

    private static Map<String, PartDefinition> children(PartDefinition part) {
        Map<String, PartDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, PartDefinition> child : part.getChildren()) {
            result.put(child.getKey(), child.getValue());
        }
        return result;
    }

    private static PartDefinition createPart(
        List<CubeDefinition> cubes,
        PartPose pose,
        Map<String, PartDefinition> children
    ) {
        PartDefinition result = new PartDefinition(cubes, pose);
        children.forEach(result::addOrReplaceChild);
        return result;
    }

    private static LayerDefinition createLayer(PartDefinition root, MaterialDefinition material) {
        return LayerDefinition.create(new MeshDefinition(root), material.xTexSize, material.yTexSize);
    }
}
