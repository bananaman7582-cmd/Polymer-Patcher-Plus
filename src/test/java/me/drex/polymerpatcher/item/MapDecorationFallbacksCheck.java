package me.drex.polymerpatcher.item;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone regression checks for the client-safe map-decoration bridge. */
public final class MapDecorationFallbacksCheck {
    private MapDecorationFallbacksCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Holder<MapDecorationType> vanilla = type("minecraft:red_x");
        Holder<MapDecorationType> caveCabin = type("alexscaves:underground_cabin");
        Holder<MapDecorationType> fallback = type("minecraft:target_x");

        Map<String, MapDecorations.Entry> entries = new LinkedHashMap<>();
        entries.put("home", new MapDecorations.Entry(vanilla, 1.25D, -3.5D, 17.0F));
        entries.put("cabin", new MapDecorations.Entry(caveCabin, 42.5D, 91.75D, 123.0F));
        MapDecorations source = new MapDecorations(Map.copyOf(entries));

        require(MapDecorationFallbacks.containsUnavailableDecoration(source, namespace -> false),
            "custom decoration must require a fallback for a vanilla client");
        require(!MapDecorationFallbacks.containsUnavailableDecoration(source,
                namespace -> namespace.equals("alexscaves")),
            "a client carrying the decoration namespace must keep it");

        MapDecorations nativeResult = MapDecorationFallbacks.sanitize(source,
            namespace -> namespace.equals("alexscaves"), fallback);
        require(nativeResult == source, "native clients should not receive a rewritten component");

        MapDecorations vanillaResult = MapDecorationFallbacks.sanitize(source, namespace -> false, fallback);
        require(vanillaResult != source, "vanilla clients should receive a rewritten component");
        require(vanillaResult.decorations().get("home").type() == vanilla,
            "vanilla decorations must remain untouched");
        MapDecorations.Entry cabin = vanillaResult.decorations().get("cabin");
        require(cabin.type() == fallback, "custom decoration must use the safe fallback holder");
        require(cabin.x() == 42.5D && cabin.z() == 91.75D && cabin.rotation() == 123.0F,
            "fallback must preserve marker position and rotation");

        System.out.println("Map decoration fallback checks passed");
    }

    private static Holder<MapDecorationType> type(String id) {
        return Holder.direct(new MapDecorationType(Identifier.parse(id), false,
            MapDecorationType.NO_MAP_COLOR, true, false));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
