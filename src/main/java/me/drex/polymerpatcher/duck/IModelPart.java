package me.drex.polymerpatcher.duck;

import net.minecraft.client.model.geom.ModelLayerLocation;

public interface IModelPart {
    void polymer_patcher$setId(int id);

    int polymer_patcher$getId();

    void polymer_patcher$setModelLayerLocation(ModelLayerLocation location);

    ModelLayerLocation polymer_patcher$getModelLayerLocation();
}
