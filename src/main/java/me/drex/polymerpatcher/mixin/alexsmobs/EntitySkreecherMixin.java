package me.drex.polymerpatcher.mixin.alexsmobs;

import me.drex.polymerpatcher.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a skreecher set off sculk sensors.
 * <p>
 * <b>This changes how Alex's Mobs behaves, rather than repairing something broken.</b> The skreecher
 * declares itself as dampening vibrations - the same flag wool carries - and Minecraft's vibration
 * system therefore skips every game event it makes, so no sensor or shrieker ever hears it. That is a
 * deliberate choice by the mod, not a fault, and nothing about running on a server caused it: the flag
 * is read by the game's own code and behaves identically with this mod uninstalled.
 * <p>
 * It is here because it was asked for. The skreecher goes on making its game event either way; all
 * this does is stop the event being thrown away before a sensor can hear it.
 * <p>
 * Turn it off with {@code entities.skreechersTriggerSculk} to get the mod's own behaviour back.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.EntitySkreecher", remap = false)
public class EntitySkreecherMixin {

    @Inject(method = "dampensVibrations()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void polymer_patcher$hearMe(CallbackInfoReturnable<Boolean> cir) {
        if (ConfigManager.config().entities.skreechersTriggerSculk) {
            cir.setReturnValue(false);
        }
    }
}
