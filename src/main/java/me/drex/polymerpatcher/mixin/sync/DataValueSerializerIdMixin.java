package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.util.ModdedSerializerIds;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Writes a mod's kind of tracked value under the number the receiving client knows it by. */
@Mixin(SynchedEntityData.DataValue.class)
public abstract class DataValueSerializerIdMixin {
    @Redirect(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/syncher/EntityDataSerializers;getSerializedId(Lnet/minecraft/network/syncher/EntityDataSerializer;)I"))
    private int polymerPatcher$clientSerializerId(EntityDataSerializer<?> serializer) {
        return ModdedSerializerIds.forClient(serializer, EntityDataSerializers.getSerializedId(serializer));
    }
}
