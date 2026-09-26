package me.drex.polymerpatcher.entity.render;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;

import java.util.Map;

public class ServerEntityModelSet extends EntityModelSet {
    private final Map<ModelLayerLocation, ModelPart> bakedModels;

    public ServerEntityModelSet(Map<ModelLayerLocation, ModelPart> bakedModels) {
        super(null);
        this.bakedModels = bakedModels;
    }

    @Override
    public ModelPart bakeLayer(ModelLayerLocation modelLayerLocation) {
        ModelPart modelPart = bakedModels.get(modelLayerLocation);
        if (modelPart == null) {
            throw new IllegalArgumentException("No model for layer " + modelLayerLocation);
        } else {
            return modelPart;
        }
    }
}
