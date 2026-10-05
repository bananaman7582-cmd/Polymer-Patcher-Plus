package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.util.ClientNumbering;
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
 * Reads an item or item data type a renumbered client sent as the one it meant. See {@link ClientNumbering}.
 * <p>
 * The target is the codec behind {@code ByteBufCodecs.registry} and {@code holderRegistry}, which every item
 * and data type number goes through - the one Polymer itself hooks to write registry numbers. Only those two
 * registries are touched.
 */
@Mixin(targets = "net/minecraft/network/codec/ByteBufCodecs$29")
public abstract class ClientNumberCodecMixin {
    @SuppressWarnings({"rawtypes", "ShadowModifiers"})
    @Shadow
    @Final
    private ResourceKey val$registryKey;

    @Inject(method = "decode(Lnet/minecraft/network/RegistryFriendlyByteBuf;)Ljava/lang/Object;", at = @At("RETURN"),
        cancellable = true, require = 0)
    private void polymerPatcher$readClientNumber(RegistryFriendlyByteBuf buf, CallbackInfoReturnable<Object> cir) {
        ResourceKey<?> registry = this.val$registryKey;
        if (!Registries.DATA_COMPONENT_TYPE.equals(registry) && !Registries.ITEM.equals(registry)) {
            return;
        }
        Object decoded = cir.getReturnValue();
        Object meant = ClientNumbering.fromClient(registry, decoded);
        if (meant != decoded) {
            cir.setReturnValue(meant);
        }
    }
}
