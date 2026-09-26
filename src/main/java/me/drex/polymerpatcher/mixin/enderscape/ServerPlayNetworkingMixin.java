package me.drex.polymerpatcher.mixin.enderscape;

import me.drex.polymerpatcher.compat.enderscape.EnderscapePacketHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworking.class)
public abstract class ServerPlayNetworkingMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void polymerPatcher$translateEnderscapePayload(ServerPlayer player,
                                                                  CustomPacketPayload payload,
                                                                  CallbackInfo ci) {
        if (!payload.type().id().getNamespace().equals("enderscape")) return;

        // A native Enderscape client still receives and handles its own payloads.
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            return;
        }

        EnderscapePacketHandler.handle(player, payload);
        ci.cancel();
    }
}
