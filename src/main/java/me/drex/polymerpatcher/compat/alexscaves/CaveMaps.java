package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.item.ConvertedMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a cave biome map into the treasure map it always wanted to be.
 * <p>
 * The first attempt at this read the picture out of the item - sixteen thousand three hundred and
 * eighty four biome ids, which is 128 by 128, exactly a map - and painted each one the nearest colour
 * the map palette had. It worked, in the sense that a real map arrived and the client drew it. It was
 * also useless. What you got was a field of coloured blobs with nothing to say which blob was the
 * biome you were looking for, no lettering, since the mod draws those rather than storing them, and
 * no sign of yourself anywhere on it. A map you cannot locate yourself on does not help you walk
 * anywhere.
 * <p>
 * The game already has the answer and has had for years. An explorer map is an ordinary map centred
 * on somewhere far away with a cross drawn on the thing you are looking for; the terrain fills itself
 * in as you travel, your own arrow shows where you are, and when you are off the edge it tells you
 * that too. All of that is vanilla, all of it is server-side, and none of it needs the mod.
 * <p>
 * So nothing is painted any more. The item says where the biome is - {@code getBiomeBlockPos} - and
 * that is the only part worth keeping. A real map is made centred there, marked with a cross, and
 * handed over. What a stranger gets is a treasure map to a cave biome, which is precisely what a cave
 * biome map is for.
 */
public final class CaveMaps {

    private CaveMaps() {
    }

    private static final String CAVE_MAP_ITEM = "com.github.alexmodguy.alexscaves.server.item.CaveMapItem";

    /**
     * How far out the map is drawn: 1:8, so a little over a thousand blocks across.
     * <p>
     * A cave biome is generally a long way off. Closer in and the cross sits off the edge from the
     * moment it is made, which is the complaint this is meant to answer.
     */
    private static final byte SCALE = 3;

    /** One map per place, so two copies of the same map are the same map. */
    private static final Map<String, ItemStack> BY_TARGET = new ConcurrentHashMap<>();

    private static Method isFilled;
    private static Method biomePos;
    private static boolean looked;
    private static boolean absent;

    /** Registers Alex's Caves' item semantics with the shared converted-map transport. */
    public static void init() {
        ConvertedMaps.register("alexscaves", CaveMaps::isFilledCaveMap, CaveMaps::treasureMapFor);
    }

    /** Whether this is a cave map with somewhere to point at. */
    public static boolean isFilledCaveMap(ItemStack stack) {
        if (stack.isEmpty() || absent) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !id.getNamespace().equals("alexscaves") || !id.getPath().startsWith("cave_map")) {
            return false;
        }
        look();
        if (isFilled == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isFilled.invoke(null, stack));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * A real map of the place this cave map points at, made once and kept.
     */
    @Nullable
    public static ItemStack treasureMapFor(ItemStack stack, ServerPlayer player) {
        look();
        if (biomePos == null || !(player.level() instanceof ServerLevel level)) {
            return null;
        }

        BlockPos target;
        try {
            if (!(biomePos.invoke(null, stack) instanceof BlockPos found)) {
                return null;
            }
            target = found;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("A cave map would not say where it points", e);
            return null;
        }

        String place = level.dimension().identifier() + "@" + target.getX() + "," + target.getZ();
        return BY_TARGET.computeIfAbsent(place, key -> {
            // Centred on the biome and tracking, so the terrain fills in as somebody walks towards it
            // and their own arrow shows where they have got to - which is the whole of what was missing
            ItemStack map = MapItem.create(level, target.getX(), target.getZ(), SCALE, true, true);
            MapItemSavedData.addTargetDecoration(map, target, "+", MapDecorationTypes.RED_X);
            PolymerPatcher.LOGGER.info("A cave biome map now points at {} as an explorer map would", target);

            // And the ground around the cross is fetched and drawn in the background, so the map is
            // worth looking at before the walk rather than only after it
            net.minecraft.world.level.saveddata.maps.MapId id = map.get(DataComponents.MAP_ID);
            if (id != null) {
                CaveMapPrefill.begin(level, id, target, player);
            }
            return map;
        }).copy();
    }

    /** Copies the map and its cross onto the stand-in going out. */
    public static void dressAsTreasureMap(ItemStack out, ItemStack original, ServerPlayer player) {
        ItemStack map = treasureMapFor(original, player);
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

    private static void look() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> type = Class.forName(CAVE_MAP_ITEM, false, CaveMaps.class.getClassLoader());
            isFilled = type.getMethod("isFilled", ItemStack.class);
            biomePos = type.getMethod("getBiomeBlockPos", ItemStack.class);
            isFilled.setAccessible(true);
            biomePos.setAccessible(true);
            PolymerPatcher.LOGGER.info("Cave biome maps will be sent as explorer maps, with a cross on the biome");
        } catch (Throwable e) {
            absent = true;
            PolymerPatcher.LOGGER.debug("No {} to read cave maps out of", CAVE_MAP_ITEM, e);
        }
    }
}
