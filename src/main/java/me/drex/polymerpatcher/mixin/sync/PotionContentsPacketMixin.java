package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.item.ClientSafePotionContents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import eu.pb4.polymer.networking.api.ContextByteBuf;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers every use of a potion value, including custom components and nested payloads. */
@Mixin(PotionContents.class)
public abstract class PotionContentsPacketMixin {
    @Shadow @Final @Mutable
    public static StreamCodec<RegistryFriendlyByteBuf, PotionContents> STREAM_CODEC;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void polymerPatcher$safePotionCodec(CallbackInfo ci) {
        STREAM_CODEC = eu.pb4.polymer.core.impl.networking.TransformingPacketCodec.encodeOnly(STREAM_CODEC,
            (buf, value) -> {
                var context = PacketContext.get();
                return buf instanceof ContextByteBuf || context != null && context.get(PacketContext.CONNECTION) != null
                    ? ClientSafePotionContents.sanitize(value) : value;
            });
    }
}
