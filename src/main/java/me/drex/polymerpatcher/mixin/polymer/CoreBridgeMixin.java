package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.soundpatcher.impl.CoreBridge;
import me.drex.polymerpatcher.companion.CompanionSounds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells Polymer's sound patcher what a companion client really hears from a block.
 * <p>
 * The patcher asks what sound group the client sees for a block, and when that client cannot play the
 * sound itself the server plays it to them too. For an ordinary client the answer is the carrier's, which
 * it can never play. A companion client has the real block and plays its own sounds - so being sent them
 * as well put two of every footstep under it. See {@link CompanionSounds}.
 */
@Mixin(value = CoreBridge.class, remap = false)
public abstract class CoreBridgeMixin {

    @Inject(method = "getClientSideSoundGroup", at = @At("HEAD"), cancellable = true, require = 0)
    private static void polymerPatcher$companionHearsItsOwn(BlockState state, Entity entity, CallbackInfoReturnable<SoundType> cir) {
        if (entity instanceof ServerPlayer player) {
            SoundType heard = CompanionSounds.heardLocally(player, state);
            if (heard != null) {
                cir.setReturnValue(heard);
            }
        }
    }

    @Inject(method = "getClientSideSoundGroupBreaking", at = @At("HEAD"), cancellable = true, require = 0)
    private static void polymerPatcher$companionHearsItsOwnBreaking(BlockState state, Entity entity, CallbackInfoReturnable<SoundType> cir) {
        if (entity instanceof ServerPlayer player) {
            SoundType heard = CompanionSounds.heardLocally(player, state);
            if (heard != null) {
                cir.setReturnValue(heard);
            }
        }
    }
}
