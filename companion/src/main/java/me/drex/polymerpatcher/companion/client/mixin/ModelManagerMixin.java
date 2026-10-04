package me.drex.polymerpatcher.companion.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import me.drex.polymerpatcher.companion.client.ProxyBlock;
import me.drex.polymerpatcher.companion.client.ProxyLiquidBlock;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the companion's blocks out of the "missing model" warnings.
 * <p>
 * Their models come from the server's resource pack, which is only loaded while connected. Every time the
 * game loads without it - starting up, or after leaving the server - each of tens of thousands of states
 * has no model, and the game said so once per state.
 */
@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {

    @WrapWithCondition(method = "lambda$createBlockStateToModelDispatch$0",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"), require = 0)
    private static boolean polymerPatcherClient$quietForCopies(Logger logger, String message, Object argument,
                                                              @Local(argsOnly = true) BlockState state) {
        return !(state.getBlock() instanceof ProxyBlock || state.getBlock() instanceof ProxyLiquidBlock);
    }
}
