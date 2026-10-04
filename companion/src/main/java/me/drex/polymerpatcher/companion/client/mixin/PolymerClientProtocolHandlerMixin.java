package me.drex.polymerpatcher.companion.client.mixin;

import eu.pb4.polymer.core.impl.client.networking.PolymerClientProtocolHandler;
import eu.pb4.polymer.core.impl.networking.payloads.s2c.PolymerBlockUpdateS2CPayload;
import eu.pb4.polymer.core.impl.networking.payloads.s2c.PolymerSectionUpdateS2CPayload;
import me.drex.polymerpatcher.companion.client.CompanionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redraws the blocks Polymer swapped.
 * <p>
 * Polymer puts the real block in place of the carrier straight into the chunk, without telling the
 * renderer anything changed - so a section drawn a moment earlier kept showing the carrier until
 * something else happened to touch it. Each swap now marks its section for redrawing, queued behind
 * Polymer's own work so the redraw sees the real block.
 */
@Mixin(value = PolymerClientProtocolHandler.class, remap = false)
public abstract class PolymerClientProtocolHandlerMixin {

    @Inject(method = "handleSetBlock", at = @At("TAIL"), require = 0)
    private static void polymerPatcherClient$redrawBlock(Minecraft client, ClientPacketListener handler,
                                                         PolymerBlockUpdateS2CPayload payload, CallbackInfo ci) {
        if (CompanionState.decoding()) {
            var pos = payload.pos();
            redraw(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        }
    }

    @Inject(method = "handleWorldSectionUpdate", at = @At("TAIL"), require = 0)
    private static void polymerPatcherClient$redrawSection(Minecraft client, ClientPacketListener handler,
                                                           PolymerSectionUpdateS2CPayload payload, CallbackInfo ci) {
        if (CompanionState.decoding()) {
            SectionPos section = payload.chunkPos();
            redraw(section.x(), section.y(), section.z());
        }
    }

    private static void redraw(int x, int y, int z) {
        Minecraft.getInstance().execute(() -> {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                level.setSectionDirtyWithNeighbors(x, y, z);
            }
        });
    }
}
