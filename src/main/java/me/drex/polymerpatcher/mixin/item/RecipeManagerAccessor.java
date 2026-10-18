package me.drex.polymerpatcher.mixin.item;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipePropertySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {
    @Accessor("propertySets")
    Map<ResourceKey<RecipePropertySet>, RecipePropertySet> polymerPatcher$propertySets();

    @Accessor("propertySets")
    void polymerPatcher$setPropertySets(Map<ResourceKey<RecipePropertySet>, RecipePropertySet> sets);
}
