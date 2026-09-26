package me.drex.polymerpatcher.mixin.client;

import me.drex.polymerpatcher.client.ServerMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the shadow out while a model is being posed here.
 * <p>
 * Working out an entity's shadow starts by asking the client whether shadows are switched on at all,
 * and the stand-in client this mod poses models against has no settings to ask - it was built without
 * running a constructor, so everything on it that nothing has needed yet is null. That question threw,
 * and it is asked from inside {@code finalizeRenderState}, which GeckoLib calls while building a render
 * state. So every GeckoLib mob failed before it had a state at all, and none of them were ever drawn.
 * <p>
 * Nothing is lost by skipping it. A shadow is a thing a client draws on the ground beneath a mob; there
 * is no ground being drawn here, and the mob's own shadow is the client's business either way.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererShadowMixin {

    @Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
    private void polymer_patcher$skipShadow(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        if (ServerMinecraft.rendering()) {
            ci.cancel();
        }
    }
}
