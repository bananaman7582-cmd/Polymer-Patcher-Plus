package me.drex.polymerpatcher.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, RenderSetup.TextureBinding> polymer_patcher$textures();

    @Accessor("pipeline")
    RenderPipeline polymer_patcher$pipeline();

}
