package me.drex.polymerpatcher.dump.mixin;

import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers models baked directly by custom renderers, outside EntityModelSet. */
@Mixin(LayerDefinition.class)
public abstract class LayerDefinitionMixin {
    @Inject(method = "bakeRoot", at = @At("RETURN"))
    private void polymerPatcher$captureDirectBake(CallbackInfoReturnable<ModelPart> cir) {
        RenderRegistryGenerator.captureRawBakedModel((LayerDefinition) (Object) this, cir.getReturnValue());
    }
}
