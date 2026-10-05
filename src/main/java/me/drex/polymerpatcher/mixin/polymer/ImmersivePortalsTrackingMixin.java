package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.virtualentity.impl.compat.ImmersivePortalsUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Polymer virtual entities usable when a portal mod advertises the Immersive Portals mod id.
 *
 * <p>Polymer 0.17.5 enables its portal compatibility branch whenever {@code immersive_portals} is
 * loaded, but that branch is currently only a placeholder: {@code getPlayerTracking} returns
 * {@code null} and {@code isPlayerTracking} always returns {@code false}. The null result crashes
 * creation of FactoryTools' virtual destroy-stage holder, while the false result immediately drops
 * ordinary viewers from every other virtual entity.</p>
 *
 * <p>The normal chunk-map viewers are always valid, including when a portal implementation later
 * supplies extra viewers. Merge rather than replace so a future Polymer implementation can retain
 * its portal-side tracking.</p>
 */
@Mixin(value = ImmersivePortalsUtils.class, remap = false)
public abstract class ImmersivePortalsTrackingMixin {
    @Inject(method = "getPlayerTracking", at = @At("RETURN"), cancellable = true, require = 0)
    private static void polymer_patcher$includeVanillaChunkViewers(LevelChunk chunk,
                                                                   CallbackInfoReturnable<List<ServerPlayer>> cir) {
        // Portal implementations can replace PlayerChunkSender and deliberately throw from its pending
        // query. Test the public tracking view directly instead of going through ChunkMap#getPlayers.
        List<ServerPlayer> ordinaryViewers = chunk.getLevel().getServer().getPlayerList().getPlayers().stream()
            .filter(player -> player.level() == chunk.getLevel())
            .filter(player -> player.getChunkTrackingView().contains(chunk.getPos().x(), chunk.getPos().z()))
            .toList();
        List<ServerPlayer> portalViewers = cir.getReturnValue();

        if (portalViewers == null || portalViewers.isEmpty()) {
            // Copy the chunk map's internal result so Polymer never receives null and does not retain
            // a list whose ownership belongs to Minecraft's tracking implementation.
            cir.setReturnValue(new ArrayList<>(ordinaryViewers));
            return;
        }

        if (!portalViewers.containsAll(ordinaryViewers)) {
            List<ServerPlayer> combined = new ArrayList<>(portalViewers.size() + ordinaryViewers.size());
            combined.addAll(portalViewers);
            for (ServerPlayer viewer : ordinaryViewers) {
                if (!combined.contains(viewer)) {
                    combined.add(viewer);
                }
            }
            cir.setReturnValue(combined);
        }
    }

    @Inject(method = "isPlayerTracking", at = @At("RETURN"), cancellable = true, require = 0)
    private static void polymer_patcher$recognizeVanillaChunkViewer(ServerPlayer player, LevelChunk chunk,
                                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
            && player.level() == chunk.getLevel()
            && player.getChunkTrackingView().contains(chunk.getPos().x(), chunk.getPos().z())) {
            cir.setReturnValue(true);
        }
    }
}
