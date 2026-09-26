package me.drex.polymerpatcher.mixin.item;

import me.drex.polymerpatcher.item.ItemDataWatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands a listening tag to whoever asks an item for its own data, while this mod is listening.
 * <p>
 * This is the one door: a mod that keeps something on an item - how far a bow is drawn, which way a
 * shield is polarised - reads it back through here. See {@link ItemDataWatch} for what the listening is
 * for. The field read in front of everything else is false at every moment except during a measurement,
 * so on an ordinary tick this is a single field read and a return.
 */
@Mixin(CustomData.class)
public class CustomDataMixin {

    @Inject(method = "copyTag", at = @At("RETURN"), cancellable = true)
    private void polymerPatcher$sayWhatWasAskedFor(CallbackInfoReturnable<CompoundTag> cir) {
        if (ItemDataWatch.watching) {
            cir.setReturnValue(ItemDataWatch.record(cir.getReturnValue()));
        }
    }
}
