package me.drex.polymerpatcher.mixin.sync;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.impl.interfaces.EntityAttachedPacket;
import me.drex.polymerpatcher.util.VanillaEntityData;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import java.util.List;

/**
 * Last look at an entity update before it goes on the wire.
 * <p>
 * Polymer already trims these down to what the stand-in entity type can legally carry, but only for
 * entities it has been given a replacement for. A vanilla mob, or the player, goes out untouched -
 * including the numbering a mod shifted by adding tracked data of its own to a class the whole game
 * inherits from. The priority is above Polymer's own so this runs on what Polymer decided to send,
 * rather than before it.
 */
@Mixin(value = ClientboundSetEntityDataPacket.class, priority = 1500)
public abstract class ClientboundSetEntityDataPacketMixin {
    @Shadow
    @Final
    private int id;

    @ModifyArg(
        method = "write",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;pack(Ljava/util/List;Lnet/minecraft/network/RegistryFriendlyByteBuf;)V"
        ),
        index = 0
    )
    private List<SynchedEntityData.DataValue<?>> polymerPatcher$vanillaifyEntries(List<SynchedEntityData.DataValue<?>> values) {
        return VanillaEntityData.sanitize(values, EntityAttachedPacket.get(this, this.id), PolymerCommonUtils.getPlayer(PacketContext.get()));
    }
}
