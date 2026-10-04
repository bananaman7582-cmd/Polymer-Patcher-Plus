package me.drex.polymerpatcher.mixin.item;

import me.drex.polymerpatcher.item.ArmorEnchantability;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets correctly componentised armour survive a mod's missing vanilla item tags. */
@Mixin(Enchantment.class)
public class EnchantmentMixin {
    @Inject(method = "canEnchant", at = @At("RETURN"), cancellable = true)
    private void polymerPatcher$recognizeSemanticArmor(ItemStack stack,
                                                        CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue() && ArmorEnchantability.supports((Enchantment) (Object) this, stack)) {
            callback.setReturnValue(true);
        }
    }
}
