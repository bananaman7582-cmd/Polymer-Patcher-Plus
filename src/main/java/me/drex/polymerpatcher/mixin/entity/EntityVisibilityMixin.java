package me.drex.polymerpatcher.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers "is this mob hidden from you" when there is no you.
 * <p>
 * A renderer asks this before drawing a name tag, and it asks on behalf of the player holding the
 * camera. Here there is no such player: the stand-in client these models are posed against was built
 * without one, and the game's own answer begins by asking whether that player is spectating. So the
 * question threw, and it threw from inside the check for whether to draw a name - which happens before
 * the mob is drawn at all, so Alex's Mobs' underminer was never drawn.
 * <p>
 * Without a player to hide from, the honest answer is simply whether the mob is invisible. Team-based
 * hiding is the only thing lost, and that is a thing one player sees about another's team, which has
 * no meaning with nobody asking.
 * <p>
 * Only when there is genuinely no player. A real one is answered by the game as always.
 */
@Mixin(Entity.class)
public class EntityVisibilityMixin {

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void polymer_patcher$noOneToHideFrom(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player == null) {
            cir.setReturnValue(((Entity) (Object) this).isInvisible());
        }
    }
}
