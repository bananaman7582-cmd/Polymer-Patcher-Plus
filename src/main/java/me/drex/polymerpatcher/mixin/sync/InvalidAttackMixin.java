package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a swing at something unhittable from ending the connection.
 * <p>
 * The game treats an attack on an item lying on the ground, an experience orb, an arrow in flight or on
 * yourself as impossible, and a client that asks for one as a client that has been tampered with - so it is
 * disconnected. That reasoning holds for a client playing the game the game knows about.
 * <p>
 * On a server like this one it does not. A watcher takes the camera out of the player's own head, and from
 * there the crosshair falls on their own body - so swinging while possessed asks to attack yourself, and the
 * player is thrown off the server for it. A volley of dreadbow arrows fills the air with arrows that cannot
 * be attacked, and a swing into that volley is the same. In both cases the player did nothing but press the
 * button, and there is nothing to defend against: the attack is refused either way.
 * <p>
 * So the swing is dropped and the player stays. Everything the game would have refused is still refused.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class InvalidAttackMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(
        method = "handleAttack",
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"
        ),
        cancellable = true
    )
    private void polymerPatcher$dropRatherThanDisconnect(ServerboundAttackPacket packet, CallbackInfo ci) {
        ServerLevel level = this.player.level();
        Entity target = level.getEntityOrPart(packet.entityId());
        if (target == null) {
            // Already ignored by the game, and not what it disconnects for
            return;
        }

        boolean unhittable = target == this.player
            || target instanceof ItemEntity
            || target instanceof ExperienceOrb
            || (target instanceof AbstractArrow arrow && !arrow.isAttackable());

        if (unhittable) {
            ci.cancel();
        }
    }
}
