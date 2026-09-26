package me.drex.polymerpatcher.mixin.borrowedecho;

import me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoPlayerSkins;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Borrowed Echo's server-side default skin with the mimicked player's baked real skin. */
@Mixin(targets = "com.borrowedecho.client.render.BorrowedEchoRenderer", remap = false)
public abstract class BorrowedEchoRendererMixin {
    @Inject(
        method = "extractRenderState(Lcom/borrowedecho/entity/BorrowedEchoEntity;Lcom/borrowedecho/client/model/BorrowedEchoRenderState;F)V",
        at = @At("RETURN"),
        require = 0
    )
    private void polymer_patcher$realPlayerSkin(@Coerce Entity echo, @Coerce Object state,
                                                float tickDelta, CallbackInfo ci) {
        BorrowedEchoPlayerSkins.apply(echo, state, this);
    }
}
