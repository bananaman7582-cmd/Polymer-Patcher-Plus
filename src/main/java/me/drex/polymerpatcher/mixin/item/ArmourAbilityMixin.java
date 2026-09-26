package me.drex.polymerpatcher.mixin.item;

import me.drex.polymerpatcher.item.ArmourAbility;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crouch and press the swap-hands key to use modded armour that has an ability of its own.
 * <p>
 * See {@link ArmourAbility} for why there is no other way to ask for one. The hands still swap unless
 * something answered, so nothing is taken away from anybody: the only players who lose a crouching
 * hand-swap are the ones wearing armour with an ability that was ready to fire.
 * <p>
 * After the packet has been handed to the server thread rather than before, because what this ends up
 * calling is the mod's own code, and that is not written to be run from a network thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ArmourAbilityMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(
        method = "handlePlayerAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void polymerPatcher$useArmourAbility(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
            && this.player.isShiftKeyDown() && ArmourAbility.use(this.player)) {
            callback.cancel();
        }
    }
}
