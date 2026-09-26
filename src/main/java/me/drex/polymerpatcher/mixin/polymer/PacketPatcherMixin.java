package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.core.impl.networking.PacketPatcher;
import me.drex.polymerpatcher.block.AutomaticFactoryBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents the vanilla carrier and the shaped FactoryTools marker from drawing cracks together. */
@Mixin(value = PacketPatcher.class, remap = false)
public class PacketPatcherMixin {
    @Inject(method = "prevent", at = @At("HEAD"), cancellable = true)
    private static void polymer_patcher$hideCarrierBreakingTexture(ServerCommonPacketListenerImpl handler,
                                                                   Packet<?> packet,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!(handler instanceof ServerGamePacketListenerImpl game)
            || !(packet instanceof ClientboundBlockDestructionPacket breaking)
            || breaking.getProgress() < 0) {
            return;
        }

        var state = game.getPlayer().level().getBlockState(breaking.getPos());
        if (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, state.getBlock()) instanceof AutomaticFactoryBlock automatic
            && automatic.usesServerTimedBreakOverlay(state)) {
            cir.setReturnValue(true);
        }
    }
}
