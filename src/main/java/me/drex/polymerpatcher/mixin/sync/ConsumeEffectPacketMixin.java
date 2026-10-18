package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.pb4.polymer.core.impl.networking.TransformingPacketCodec;
import me.drex.polymerpatcher.item.ClientSafeConsumeEffects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Covers consumables, death protection and any nested uses without item/mod name lists. */
@Mixin(ConsumeEffect.class)
public interface ConsumeEffectPacketMixin {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/codec/StreamCodec;dispatch(Ljava/util/function/Function;Ljava/util/function/Function;)Lnet/minecraft/network/codec/StreamCodec;"))
    private static StreamCodec<RegistryFriendlyByteBuf, ConsumeEffect> polymerPatcher$matchingEffectSchema(
        StreamCodec<RegistryFriendlyByteBuf, ConsumeEffect> original) {
        return TransformingPacketCodec.encodeOnly(original, ClientSafeConsumeEffects::forWire);
    }
}
