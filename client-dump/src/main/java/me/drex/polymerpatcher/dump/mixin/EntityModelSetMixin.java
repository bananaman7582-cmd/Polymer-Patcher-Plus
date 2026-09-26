package me.drex.polymerpatcher.dump.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityModelSet.class)
public abstract class EntityModelSetMixin {
    @WrapOperation(
        method = "bakeLayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/geom/builders/LayerDefinition;bakeRoot()Lnet/minecraft/client/model/geom/ModelPart;"
        )
    )
    private ModelPart captureContext(LayerDefinition layerDefinition, Operation<ModelPart> original,
                                     @Local(argsOnly = true) ModelLayerLocation modelLayerLocation) {
        RenderRegistryGenerator.inBuilderContext(builder -> builder.modelLayer(modelLayerLocation, layerDefinition));
        ModelPart root = original.call(layerDefinition);
        RenderRegistryGenerator.captureBakedModel(modelLayerLocation, layerDefinition, root);
        return root;
    }
}
