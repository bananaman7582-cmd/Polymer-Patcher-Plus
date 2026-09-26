package me.drex.polymerpatcher.entity.armor;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps each player's armour drawing attached to them for as long as they are wearing something that
 * needs it.
 * <p>
 * This is reconciled every tick rather than hung off joining and leaving, because the entity a player
 * <i>is</i> does not survive dying or changing dimension - the old one is thrown away and a new one put
 * in its place, taking any attachment with it. Checking each tick costs a map lookup per player and
 * cannot drift out of step with what they are actually wearing.
 */
public final class ArmorAttachments {

    private static final Map<UUID, Attached> ATTACHED = new HashMap<>();

    private record Attached(PlayerArmorModel holder, EntityAttachment attachment, ServerPlayer player) {
    }

    private ArmorAttachments() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ArmorModels.isEmpty()) {
                return;
            }

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Attached attached = ATTACHED.get(player.getUUID());
                boolean wanted = PlayerArmorModel.wearsAny(player);

                // A player who respawned or changed dimension is a different entity now, so the old
                // attachment is following something that no longer exists
                if (attached != null && (!wanted || attached.player() != player)) {
                    // The ordinary entity tracker never considers a player to be watching their own
                    // entity. Destroy the holder, rather than only its attachment, so the explicit
                    // self-viewer added below is stopped as well.
                    attached.holder().destroy();
                    ATTACHED.remove(player.getUUID());
                    attached = null;
                }

                if (wanted && attached == null) {
                    PlayerArmorModel holder = new PlayerArmorModel(player);
                    EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
                    ATTACHED.put(player.getUUID(), new Attached(holder, attachment, player));
                }
            }

            ATTACHED.entrySet().removeIf(entry -> {
                if (entry.getValue().player().hasDisconnected()) {
                    entry.getValue().holder().destroy();
                    return true;
                }
                return false;
            });
        });
    }
}
