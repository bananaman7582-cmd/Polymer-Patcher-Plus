package me.drex.polymerpatcher.mixin.witherstorm;

import me.drex.polymerpatcher.compat.witherstorm.WitherStormPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the Wither Storm mod sends each of its packets to one player. It leaves out any player without
 * the mod, as Forge's channel used to; this hands that player the vanilla packets that do the same job.
 */
@Pseudo
@Mixin(targets = "nonamecrackers2.witherstormmod.port.network.PacketDistributor")
public abstract class PacketDistributorMixin {
    @Inject(method = "sendTo", at = @At("HEAD"))
    private static void polymerPatcher$forPlayersWithoutTheMod(ServerPlayer player, @Coerce CustomPacketPayload packet, CallbackInfo ci) {
        if (player != null && packet != null && !ServerPlayNetworking.canSend(player, packet.type())) {
            WitherStormPackets.handle(player, packet);
        }
    }
}
