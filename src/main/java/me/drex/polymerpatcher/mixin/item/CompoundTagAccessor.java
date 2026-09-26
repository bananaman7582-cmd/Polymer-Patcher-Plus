package me.drex.polymerpatcher.mixin.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reaches the map a tag keeps its values in.
 * <p>
 * Every way of reading a value out of a tag - and there are two dozen, one per type - goes straight to
 * this map. Putting a different map underneath is therefore the only way to hear all of them at once,
 * and the tag itself is final, so there is no other way in. Used by {@link
 * me.drex.polymerpatcher.item.ItemDataWatch} and nowhere else.
 */
@Mixin(CompoundTag.class)
public interface CompoundTagAccessor {

    @Accessor("tags")
    Map<String, Tag> polymerPatcher$contents();

    @Mutable
    @Accessor("tags")
    void polymerPatcher$setContents(Map<String, Tag> contents);
}
