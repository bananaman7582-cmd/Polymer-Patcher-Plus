package me.drex.polymerpatcher.mixin.item;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipePropertySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(RecipePropertySet.class)
public interface RecipePropertySetAccessor {
    @Accessor("items")
    Set<Holder<Item>> polymerPatcher$items();

    @Invoker("<init>")
    static RecipePropertySet polymerPatcher$create(Set<Holder<Item>> items) {
        throw new AssertionError();
    }
}
