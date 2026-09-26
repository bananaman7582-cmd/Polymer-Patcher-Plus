package me.drex.polymerpatcher.mixin.enderscape;

import me.drex.polymerpatcher.compat.enderscape.EnderscapePacketHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.penumbra.enderscape.network.ServerboundRespawnFromEndHavenPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Shadow
    public abstract void disconnect(Component reason);

    @Inject(method = "handleCustomClickAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"), cancellable = true)
    private void polymerPatcher$handleDeathChoice(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (!(this instanceof ServerGamePacketListener listener)) {
            return;
        }

        if (packet.id().equals(EnderscapePacketHandler.END_HAVEN_RESPAWN_ACTION)) {
            listener.handleCustomPayload(new ServerboundCustomPayloadPacket(new ServerboundRespawnFromEndHavenPayload()));
            listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ci.cancel();
        } else if (packet.id().equals(EnderscapePacketHandler.RESPAWN_ACTION)) {
            listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            ci.cancel();
        } else if (packet.id().equals(EnderscapePacketHandler.DISCONNECT_ACTION)) {
            this.disconnect(Component.translatable("multiplayer.disconnect.generic"));
            ci.cancel();
        }
    }
}
