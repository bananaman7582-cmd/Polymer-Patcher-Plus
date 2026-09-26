package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.DynamicOps;
import me.drex.polymerpatcher.registry.ReadableRegistryData;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Takes what a client cannot read out of the world data it is sent. See {@link ReadableRegistryData}.
 * <p>
 * Done here rather than where the packet is built because this is where the entries are still handed over as
 * a list, and because it is the only place that knows they are on their way to a client rather than to disk.
 */
@Mixin(SynchronizeRegistriesTask.class)
public class SynchronizeRegistriesTaskMixin {

    @WrapOperation(
        method = "sendRegistries",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/RegistrySynchronization;packRegistries(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/core/RegistryAccess;Ljava/util/Set;Ljava/util/function/BiConsumer;)V"
        )
    )
    private void polymerPatcher$onlyWhatCanBeRead(
        DynamicOps<Tag> ops,
        RegistryAccess registries,
        Set<KnownPack> theirs,
        BiConsumer<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> send,
        Operation<Void> original
    ) {
        original.call(ops, registries, theirs,
            (BiConsumer<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>>)
                (registry, entries) -> send.accept(registry, ReadableRegistryData.readable(registry, registries, entries)));
    }
}
