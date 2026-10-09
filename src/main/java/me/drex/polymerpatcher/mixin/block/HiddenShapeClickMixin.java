package me.drex.polymerpatcher.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.drex.polymerpatcher.block.HiddenShapeClicks;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives a click to the block the player was really looking at. See {@link HiddenShapeClicks}. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class HiddenShapeClickMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void handleUseItemOn(ServerboundUseItemOnPacket packet);

    @Unique
    private BlockPos polymerPatcher$clickedPos;

    @Unique
    private BlockState polymerPatcher$clickedState;

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER))
    private void polymerPatcher$rememberClicked(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        BlockPos pos = HiddenShapeClicks.retarget(this.player, packet.getHitResult()).getBlockPos();
        this.polymerPatcher$clickedPos = pos;
        this.polymerPatcher$clickedState = this.player.level().getBlockState(pos);
    }

    @Inject(method = "handleUseItemOn", at = @At("RETURN"))
    private void polymerPatcher$correctTheClientsGuess(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        BlockPos pos = this.polymerPatcher$clickedPos;
        BlockState before = this.polymerPatcher$clickedState;
        this.polymerPatcher$clickedPos = null;
        this.polymerPatcher$clickedState = null;
        if (pos != null && before != null) {
            HiddenShapeClicks.afterClick(this.player, packet.getHand(), pos, before);
        }
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;getHitResult()Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult polymerPatcher$clickWhatWasSeen(ServerboundUseItemOnPacket packet, Operation<BlockHitResult> original) {
        return HiddenShapeClicks.retarget(this.player, original.call(packet));
    }

    @Inject(method = "handleUseItem", cancellable = true, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER))
    private void polymerPatcher$clickOnUnseenBlock(ServerboundUseItemPacket packet, CallbackInfo ci) {
        BlockHitResult hit = HiddenShapeClicks.fromAirClick(this.player, packet.getYRot(), packet.getXRot());
        if (hit != null) {
            this.handleUseItemOn(new ServerboundUseItemOnPacket(packet.getHand(), hit, packet.getSequence()));
            ci.cancel();
        }
    }
}
