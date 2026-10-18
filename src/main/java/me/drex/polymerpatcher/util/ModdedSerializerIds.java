package me.drex.polymerpatcher.util;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Registry;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * The number a player's client knows a mod's kind of tracked value by.
 * <p>
 * Fabric numbers the kinds mods add after the game's own, in the order the server has them. A client
 * only has the kinds of the mods it carries, so everything after the first kind it is missing sits lower
 * on its list than on the server's. Sent as the server numbers it, the Wither Storm's own kinds arrived
 * as numbers that client had nothing at - "Unknown serializer type 61" - and the player was disconnected.
 * So the number is counted again for the player it is going to: the game's own kinds, then every mod kind
 * before this one that their client also has.
 */
public final class ModdedSerializerIds {
    private static final String IMPL = "net.fabricmc.fabric.impl.object.builder.FabricEntityDataRegistryImpl";

    private static boolean looked;
    private static @Nullable Registry<EntityDataSerializer<?>> registry;
    private static int vanillaCount = -1;

    private ModdedSerializerIds() {
    }

    /** The id this serializer should be written with for the player being written to. */
    public static int forClient(EntityDataSerializer<?> serializer, int serverId) {
        if (serverId < 0) {
            return serverId;
        }
        lookUp();
        if (registry == null || serverId < vanillaCount) {
            return serverId;
        }
        ServerPlayer player;
        try {
            player = PolymerCommonUtils.getPlayer(PacketContext.get());
        } catch (Throwable e) {
            return serverId;
        }
        // A client with every mod numbers them exactly as the server does
        if (player == null || NativeClients.hasAll(player)) {
            return serverId;
        }
        Identifier own = registry.getKey(serializer);
        if (own == null) {
            // Not one Fabric numbers, so nothing is known about where the client keeps it
            return serverId;
        }
        int ownRaw = registry.getId(serializer);
        int id = vanillaCount;
        for (EntityDataSerializer<?> other : registry) {
            if (registry.getId(other) >= ownRaw) {
                continue;
            }
            Identifier key = registry.getKey(other);
            if (key != null && NativeClients.carries(player, key.getNamespace())) {
                id++;
            }
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static void lookUp() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> impl = Class.forName(IMPL);
            Field handlers = impl.getDeclaredField("handlerRegistry");
            handlers.setAccessible(true);
            Field vanilla = impl.getDeclaredField("VANILLA_HANDLERS");
            vanilla.setAccessible(true);
            registry = (Registry<EntityDataSerializer<?>>) handlers.get(null);
            vanillaCount = ((List<?>) vanilla.get(null)).size();
        } catch (Throwable e) {
            registry = null;
            PolymerPatcher.LOGGER.warn("Could not read how Fabric numbers modded tracked values; a player with only some mods may be disconnected by them", e);
        }
    }
}
