package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.EnumSet;

@Mixin(FabricRegistryBuilder.class)
public abstract class FabricRegistryBuilderMixin {
    @WrapOperation(method = "attribute", at = @At(value = "INVOKE", target = "Ljava/util/EnumSet;add(Ljava/lang/Object;)Z"))
    public boolean weDontSupportSync(EnumSet instance, Object o, Operation<Boolean> original) {
        if (o == RegistryAttribute.SYNCED) return false;
        return original.call(instance, o);
    }
}
