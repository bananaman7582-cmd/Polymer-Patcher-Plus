package me.drex.polymerpatcher.compat.witherstorm;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import me.drex.polymerpatcher.util.VanillaEntityData;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Storm's data and attributes, written so the player each packet goes to can read them.
 * <p>
 * The mod sends the Storm's entity data and attributes in packets of its own, both to draw it from far
 * off and to keep it up to date, written straight from what the server holds. That is numbered the way
 * this server numbers everything, with every other mod's additions included. A player with the Wither
 * Storm mod but not every other mod read it wrongly, and the first Storm summoned disconnected them. The
 * same correction every vanilla entity update gets is made here, for whoever is being written to.
 */
public final class WitherStormEntityData {
    private WitherStormEntityData() {
    }

    public static List<SynchedEntityData.DataValue<?>> data(List<SynchedEntityData.DataValue<?>> values, int entityId) {
        ServerPlayer player = player();
        if (player == null || values == null) {
            return values;
        }
        Entity entity = player.level().getEntity(entityId);
        return VanillaEntityData.sanitize(values, entity, player);
    }

    public static List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes(List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes) {
        if (attributes == null) {
            return null;
        }
        PacketContext context = PacketContext.get();
        List<ClientboundUpdateAttributesPacket.AttributeSnapshot> kept = new ArrayList<>(attributes.size());
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes) {
            if (PolymerSyncedObject.canSyncRawToClient(BuiltInRegistries.ATTRIBUTE, snapshot.attribute().value(), context)) {
                kept.add(snapshot);
            }
        }
        return kept.size() == attributes.size() ? attributes : kept;
    }

    private static @Nullable ServerPlayer player() {
        try {
            return eu.pb4.polymer.common.api.PolymerCommonUtils.getPlayer(PacketContext.get());
        } catch (Throwable e) {
            return null;
        }
    }
}
