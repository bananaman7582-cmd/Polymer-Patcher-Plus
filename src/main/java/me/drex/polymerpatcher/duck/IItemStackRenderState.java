package me.drex.polymerpatcher.duck;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public interface IItemStackRenderState {
    void polymer_patcher$updateData(ItemStack item, ItemDisplayContext context);
}
