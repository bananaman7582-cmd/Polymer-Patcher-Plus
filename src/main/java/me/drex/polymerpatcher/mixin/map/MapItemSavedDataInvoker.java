package me.drex.polymerpatcher.mixin.map;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MapItemSavedData.class)
public interface MapItemSavedDataInvoker {
    /** The game's own way of putting a marker on a map, including the off-the-edge forms of a player's arrow. */
    @Invoker("addDecoration")
    void polymerPatcher$addDecoration(Holder<MapDecorationType> type, LevelAccessor level, String key,
                                      double x, double z, double rotation, @Nullable Component name);
}
