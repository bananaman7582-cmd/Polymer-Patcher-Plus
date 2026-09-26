package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.sugar.Local;
import me.drex.polymerpatcher.util.ModdedMenus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Catches a screen the moment it opens, and shuts it again where the client cannot draw it.
 * <p>
 * A modded inventory reaches a vanilla client as whatever the registry substituted for it, which is
 * the wrong shape - and a menu of the wrong shape does not merely look wrong, it scrambles the
 * inventory, because the slots being clicked and the slots being moved are two different sets.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMenuMixin {

    /**
     * Replaces supported mod menus before their custom menu type can reach a vanilla client. This is
     * intentionally done at the same safe point used by established Polymer GUI patches: the real
     * menu has been constructed, but no open-screen packet has been sent and the player has not been
     * attached to it yet.
     */
    @Inject(
        method = "openMenu",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
            shift = At.Shift.BEFORE
        ),
        cancellable = true
    )
    private void polymer_patcher$openSupportedModMenu(
        MenuProvider provider,
        CallbackInfoReturnable<OptionalInt> callback,
        @Local AbstractContainerMenu menu
    ) {
        if (ModdedMenus.openForVanillaClient((ServerPlayer) (Object) this, provider, menu)) {
            // The replacement GUI opened itself with a fresh vanilla container id. Letting the outer
            // method continue would send the unsafe mod menu immediately afterwards.
            callback.setReturnValue(OptionalInt.empty());
        } else if (ModdedMenus.rejectBeforeOpen((ServerPlayer) (Object) this, menu)) {
            // The menu's registry placeholder is not a usable UI. Reject it before the open-screen
            // packet and, critically, before initMenu queues custom DataSlot property packets.
            callback.setReturnValue(OptionalInt.empty());
        }
    }
}
