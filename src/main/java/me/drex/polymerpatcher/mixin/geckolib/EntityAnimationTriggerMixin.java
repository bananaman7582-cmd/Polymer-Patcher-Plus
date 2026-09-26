package me.drex.polymerpatcher.mixin.geckolib;

import com.geckolib.network.GeckoLibNetworkingFabric;
import com.geckolib.network.packet.MultiloaderPacket;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibTriggerBridge;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors GeckoLib's server-originated entity animation packets into the server's pose manager. */
@Mixin(value = GeckoLibNetworkingFabric.class, remap = false)
public abstract class EntityAnimationTriggerMixin {
    @Inject(method = "sendToAllPlayersTrackingEntity", at = @At("HEAD"), remap = false)
    private void polymer_patcher$mirrorTrigger(MultiloaderPacket packet, Entity entity, CallbackInfo ci) {
        GeckoLibTriggerBridge.mirror(packet, entity);
    }
}
