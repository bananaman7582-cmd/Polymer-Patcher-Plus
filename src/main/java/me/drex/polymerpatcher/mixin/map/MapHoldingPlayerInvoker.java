package me.drex.polymerpatcher.mixin.map;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the packet that carries a map's picture.
 * <p>
 * The game keeps this to itself because it has only ever needed it in one place - the inventory tick of
 * a real map, which knows when its holder has moved far enough to be worth redrawing. A cave map never
 * reaches that tick: on the server it is a cave map, not a map, so the item whose tick would have sent
 * the picture is not the item anybody is holding.
 * <p>
 * It is asked for from outside instead, which needs it to be callable from outside.
 */
@Mixin(MapItemSavedData.HoldingPlayer.class)
public interface MapHoldingPlayerInvoker {
    @Invoker("nextUpdatePacket")
    Packet<?> polymerPatcher$nextUpdatePacket(MapId mapId);
}
