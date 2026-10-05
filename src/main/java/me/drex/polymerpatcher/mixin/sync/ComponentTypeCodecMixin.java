package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.util.ComponentNumbering;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reads an item data type a renumbered client sent as the type it meant. See {@link ComponentNumbering}.
 * <p>
 * The target is the codec behind {@code ByteBufCodecs.registry}, which every data type number goes through -
 * the one Polymer itself hooks to write registry numbers. Only the data type registry is touched.
 */
@Mixin(targets = "net/minecraft/network/codec/ByteBufCodecs$29")
public abstract class ComponentTypeCodecMixin {
    @SuppressWarnings({"rawtypes", "ShadowModifiers"})
    @Shadow
    @Final
    private ResourceKey val$registryKey;

    @Inject(method = "decode(Lnet/minecraft/network/RegistryFriendlyByteBuf;)Ljava/lang/Object;", at = @At("RETURN"),
        cancellable = true, require = 0)
    private void polymerPatcher$readClientComponentNumber(RegistryFriendlyByteBuf buf, CallbackInfoReturnable<Object> cir) {
        if (!Registries.DATA_COMPONENT_TYPE.equals(this.val$registryKey)) {
            return;
        }
        Object decoded = cir.getReturnValue();
        Object meant = ComponentNumbering.fromClient(decoded);
        if (meant != decoded) {
            cir.setReturnValue(meant);
        }
    }
}
