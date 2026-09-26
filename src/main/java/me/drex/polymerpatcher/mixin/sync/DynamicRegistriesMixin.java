package me.drex.polymerpatcher.mixin.sync;

import com.mojang.serialization.Codec;
import eu.pb4.polymer.core.api.utils.PolymerUtils;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanilla client cannot decode a mod's dynamic-registry codecs. Keep those registries authoritative
 * on the server for every mod; their contents can still be represented by the patched objects that
 * reference them without attempting to teach the client an unknown codec.
 */
@Mixin(DynamicRegistries.class)
public abstract class DynamicRegistriesMixin {
    @Inject(
        method = "registerSynced(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;[Lnet/fabricmc/fabric/api/event/registry/DynamicRegistries$SyncOption;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static <T> void polymerPatcher$makeServerOnly(
        ResourceKey<? extends Registry<T>> key,
        Codec<T> codec,
        DynamicRegistries.SyncOption[] options,
        CallbackInfo ci
    ) {
        DynamicRegistries.register(key, codec);
        PolymerUtils.markAsServerOnlyRegistry(key);
        ci.cancel();
    }

    @Inject(
        method = "registerSynced(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;Lcom/mojang/serialization/Codec;[Lnet/fabricmc/fabric/api/event/registry/DynamicRegistries$SyncOption;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static <T> void polymerPatcher$makeServerOnly(
        ResourceKey<? extends Registry<T>> key,
        Codec<T> serverCodec,
        Codec<T> clientCodec,
        DynamicRegistries.SyncOption[] options,
        CallbackInfo ci
    ) {
        DynamicRegistries.register(key, serverCodec);
        PolymerUtils.markAsServerOnlyRegistry(key);
        ci.cancel();
    }
}
