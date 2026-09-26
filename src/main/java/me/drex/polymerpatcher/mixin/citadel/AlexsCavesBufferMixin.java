package me.drex.polymerpatcher.mixin.citadel;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.drex.polymerpatcher.entity.citadel.CitadelDraw;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which picture a buffer was handed out for, while a renderer is being run on purpose.
 * <p>
 * Alex's Caves draws through a shim of its own that keeps one recorder per render type. Everything a
 * renderer draws by hand arrives at {@code renderToBuffer} with one of those recorders and nothing else
 * to say what it is for - so a gummy bear's innards and the chest on a boat were drawn with whatever
 * texture the mob's main model was wearing. Recording the pairing here is what lets them wear their own.
 * <p>
 * Costs one field read when nothing is listening, which is every other call on this server.
 */
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.render.compat.ACSubmitBuffers", remap = false)
public abstract class AlexsCavesBufferMixin {

    @Inject(method = "getBuffer", at = @At("RETURN"), require = 0)
    private void polymer_patcher$rememberWhatItIsFor(RenderType renderType, CallbackInfoReturnable<VertexConsumer> callback) {
        if (CitadelDraw.listening()) {
            CitadelDraw.boundFor(callback.getReturnValue(), renderType);
        }
    }
}
