package me.drex.polymerpatcher.item;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Keeps filled maps readable when their decoration component contains a type added by a server mod.
 *
 * <p>Map decorations are registry holders, so even an otherwise vanilla filled map disconnects a
 * client as soon as one unknown marker is encoded. This bridge deliberately operates at Polymer's
 * final item boundary instead of changing the real stack: compatible clients retain the custom
 * marker, while other clients receive a vanilla red X at the same coordinates and rotation.</p>
 */
public final class MapDecorationFallbacks {
    private static boolean initialized;

    private MapDecorationFallbacks() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Vanilla items normally bypass Polymer's stack-copying path. Force only maps which need a
        // per-client registry downgrade through it; ordinary stacks remain completely untouched.
        PolymerItemUtils.CONTEXT_ITEM_CHECK.register((item, context) -> {
            MapDecorations decorations = item.get(DataComponents.MAP_DECORATIONS);
            if (decorations == null || decorations.decorations().isEmpty()) {
                return false;
            }
            ServerPlayer player = PolymerCommonUtils.getPlayer(context);
            return containsUnavailableDecoration(decorations,
                namespace -> NativeClients.carries(player, namespace));
        });

        // Run after Polymer has made the client copy so this never edits the server inventory or the
        // saved map. It also covers modded/Polymer items which were already going through conversion.
        PolymerItemUtils.ITEM_MODIFICATION_EVENT.register((original, client, context) -> {
            MapDecorations decorations = client.get(DataComponents.MAP_DECORATIONS);
            if (decorations == null || decorations.decorations().isEmpty()) {
                return client;
            }
            ServerPlayer player = PolymerCommonUtils.getPlayer(context);
            MapDecorations safe = sanitize(decorations,
                namespace -> NativeClients.carries(player, namespace), MapDecorationTypes.RED_X);
            if (safe != decorations) {
                client.set(DataComponents.MAP_DECORATIONS, safe);
            }
            return client;
        });
    }

    static boolean containsUnavailableDecoration(MapDecorations decorations,
                                                  Predicate<String> carriesNamespace) {
        for (MapDecorations.Entry entry : decorations.decorations().values()) {
            Identifier id = typeId(entry.type());
            if (id != null && !isAvailable(id, carriesNamespace)) {
                return true;
            }
        }
        return false;
    }

    static MapDecorations sanitize(MapDecorations decorations, Predicate<String> carriesNamespace,
                                   Holder<MapDecorationType> fallback) {
        Map<String, MapDecorations.Entry> replacement = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, MapDecorations.Entry> mapEntry : decorations.decorations().entrySet()) {
            MapDecorations.Entry entry = mapEntry.getValue();
            Identifier id = typeId(entry.type());
            if (id == null || isAvailable(id, carriesNamespace)) {
                replacement.put(mapEntry.getKey(), entry);
                continue;
            }
            changed = true;
            replacement.put(mapEntry.getKey(), new MapDecorations.Entry(
                fallback, entry.x(), entry.z(), entry.rotation()));
        }
        return changed ? new MapDecorations(Map.copyOf(replacement)) : decorations;
    }

    private static boolean isAvailable(Identifier id, Predicate<String> carriesNamespace) {
        return id.getNamespace().equals("minecraft") || carriesNamespace.test(id.getNamespace());
    }

    private static Identifier typeId(Holder<MapDecorationType> type) {
        return type.unwrapKey().map(key -> key.identifier()).orElseGet(() -> type.value().assetId());
    }
}
