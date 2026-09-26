package me.drex.polymerpatcher.dump.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.drex.polymerpatcher.dump.generation.RenderRegistryGenerator;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderers.class)
public abstract class EntityRenderersMixin {
    @WrapOperation(
        method = "lambda$createEntityRenderers$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRendererProvider;create(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"
        )
    )
    private static EntityRenderer<?, ?> captureContext(
        EntityRendererProvider<?> instance, EntityRendererProvider.Context context,
        Operation<EntityRenderer<?, ?>> original, @Local(argsOnly = true) EntityType<?> entityType
    ) {
        return RenderRegistryGenerator.captureEntityRenderer(entityType, () -> original.call(instance, context));
    }
}
