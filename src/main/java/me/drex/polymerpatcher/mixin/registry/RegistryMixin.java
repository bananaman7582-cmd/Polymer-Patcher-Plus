package me.drex.polymerpatcher.mixin.registry;

import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Registry.class)
public interface RegistryMixin {
    @Inject(
        method = "register(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)Ljava/lang/Object;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/WritableRegistry;register(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;"
        )
    )
    private static <V, T extends V> void registerCreateModeTabsToPolymer(Registry<V> registry, ResourceKey<V> resourceKey, T object, CallbackInfoReturnable<T> cir) {
        if (registry == BuiltInRegistries.CREATIVE_MODE_TAB) {
            PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(resourceKey.identifier(), (CreativeModeTab) object);
        }
    }
}
