package me.drex.polymerpatcher.mixin.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractBoatRenderer.class)
public interface AbstractBoatRendererAccessor {
    @Invoker
    EntityModel<BoatRenderState> invokeModel();
}
