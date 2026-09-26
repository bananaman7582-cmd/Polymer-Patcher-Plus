package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Turns mod items which point at a place into ordinary filled maps for vanilla clients.
 *
 * <p>Compat modules only supply recognition and the corresponding vanilla map. Carrier selection,
 * component copying and map update packets are shared by every mod using the capability.</p>
 */
public final class ConvertedMaps {
    private ConvertedMaps() {
    }

    private record Adapter(String modId, Predicate<ItemStack> matches,
                           BiFunction<ItemStack, ServerPlayer, ItemStack> map) {
    }

    private static final List<Adapter> ADAPTERS = new CopyOnWriteArrayList<>();
    private static boolean initialized;

    public static void register(String modId, Predicate<ItemStack> matches,
                                BiFunction<ItemStack, ServerPlayer, ItemStack> map) {
        ADAPTERS.add(new Adapter(modId, matches, map));
    }

    public static boolean isConverted(ItemStack stack) {
        return adapter(stack) != null;
    }

    public static void dress(ItemStack out, ItemStack original, ServerPlayer player) {
        ItemStack map = vanillaMapFor(original, player);
        if (map == null) {
            return;
        }
        if (map.has(DataComponents.MAP_ID)) {
            out.set(DataComponents.MAP_ID, map.get(DataComponents.MAP_ID));
        }
        if (map.has(DataComponents.MAP_DECORATIONS)) {
            out.set(DataComponents.MAP_DECORATIONS, map.get(DataComponents.MAP_DECORATIONS));
        }
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 4 != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    sendCarriedMaps(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not bring {}'s converted maps up to date",
                        player.getGameProfile().name(), e);
                }
            }
        });
    }

    private static void sendCarriedMaps(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            Adapter adapter = adapter(stack);
            if (adapter == null || NativeClients.carries(player, adapter.modId())) {
                continue;
            }

            ItemStack map = adapter.map().apply(stack, player);
            if (map == null) {
                continue;
            }
            MapId id = map.get(DataComponents.MAP_ID);
            if (id == null) {
                continue;
            }
            MapItemSavedData saved = level.getMapData(id);
            if (saved == null) {
                continue;
            }

            saved.tickCarriedBy(player, map, null);
            if (player.level().dimension() == saved.dimension) {
                ((me.drex.polymerpatcher.mixin.map.MapItemSavedDataInvoker) saved).polymerPatcher$addDecoration(
                    net.minecraft.world.level.saveddata.maps.MapDecorationTypes.PLAYER, level,
                    player.getPlainTextName(), player.getX(), player.getZ(), player.getYRot(), null);
            }
            if (!saved.locked) {
                ((MapItem) Items.FILLED_MAP).update(level, player, saved);
            }

            Packet<?> packet = ((me.drex.polymerpatcher.mixin.map.MapHoldingPlayerInvoker)
                saved.getHoldingPlayer(player)).polymerPatcher$nextUpdatePacket(id);
            if (packet != null) {
                player.connection.send(packet);
            }
        }
    }

    private static @Nullable ItemStack vanillaMapFor(ItemStack stack, ServerPlayer player) {
        Adapter adapter = adapter(stack);
        return adapter == null ? null : adapter.map().apply(stack, player);
    }

    private static @Nullable Adapter adapter(ItemStack stack) {
        for (Adapter adapter : ADAPTERS) {
            if (adapter.matches().test(stack)) {
                return adapter;
            }
        }
        return null;
    }
}
