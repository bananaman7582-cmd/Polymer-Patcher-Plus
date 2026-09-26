package me.drex.polymerpatcher.mixin.citadel;

import me.drex.polymerpatcher.entity.citadel.CitadelDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hears a model being told how far through a motion it is.
 * <p>
 * A model that moves while an item is held is posed from one number: nought at rest, one when the
 * motion is finished. That number is the only place the end of a motion is written down anywhere a
 * server can reach, and the end is the one thing about a motion that cannot be seen from outside - a
 * part turning steadily goes on turning past it, and looks exactly like a part that has not finished.
 * The mod stops feeding it, and nothing in the drawing says so. See
 * {@link me.drex.polymerpatcher.item.HeldItemMotion}.
 * <p>
 * Heard here, where the renderer hands it to the model, rather than inside the model where it is used:
 * Citadel's own model class is marked client-only and a server refuses to load it at all, which leaves
 * nothing there to patch. The renderer is not marked, and passes the same number.
 */
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.render.item.ACItemstackRenderer", remap = false)
public abstract class AlexsCavesProgressMixin {

    @ModifyArg(
        method = "renderByItem",
        at = @At(value = "INVOKE", target = "setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"),
        index = 1,
        require = 0
    )
    private float polymerPatcher$hearHowFarThrough(float progress) {
        CitadelDraw.noteProgress(progress);
        return progress;
    }
}
