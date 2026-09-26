package me.drex.polymerpatcher.entity.render;

import me.drex.polymerpatcher.duck.IItemStackRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

public class ServerItemModelResolver extends ItemModelResolver {
    public ServerItemModelResolver() {
        super(null);
    }

    /**
     * Gives the state a layer, so that it does not read as empty.
     * <p>
     * A held item is only submitted when its render state says it holds something, and that is counted
     * in layers rather than asked of the stack. Storing the stack without adding one left every held
     * item looking like an empty hand: the layer that draws it checked, found nothing, and skipped -
     * so a mob carrying something was drawn empty-handed and nothing anywhere said why.
     * <p>
     * The layer is never drawn from. The stack is taken straight off the state when it is submitted;
     * this only has to exist to be counted.
     */
    private static void markNotEmpty(ItemStackRenderState state) {
        if (state.isEmpty()) {
            state.newLayer();
        }
    }

    @Override
    public float swapAnimationScale(ItemStack itemStack) {
        return 1.0f;
    }

    @Override
    public boolean shouldPlaySwapAnimation(ItemStack itemStack) {
        return false;
    }

    @Override
    public void appendItemLayers(ItemStackRenderState itemStackRenderState, ItemStack itemStack, ItemDisplayContext itemDisplayContext, @Nullable Level level, @Nullable ItemOwner itemOwner, int i) {
        ((IItemStackRenderState)itemStackRenderState).polymer_patcher$updateData(itemStack, itemDisplayContext);
        markNotEmpty(itemStackRenderState);
    }

    @Override
    public void updateForTopItem(ItemStackRenderState itemStackRenderState, ItemStack itemStack, ItemDisplayContext itemDisplayContext, @Nullable Level level, @Nullable ItemOwner itemOwner, int i) {
        ((IItemStackRenderState)itemStackRenderState).polymer_patcher$updateData(itemStack, itemDisplayContext);
        markNotEmpty(itemStackRenderState);
    }

    @Override
    public void updateForNonLiving(ItemStackRenderState itemStackRenderState, ItemStack itemStack, ItemDisplayContext itemDisplayContext, Entity entity) {
        ((IItemStackRenderState)itemStackRenderState).polymer_patcher$updateData(itemStack, itemDisplayContext);
        markNotEmpty(itemStackRenderState);
    }

    @Override
    public void updateForLiving(ItemStackRenderState itemStackRenderState, ItemStack itemStack, ItemDisplayContext itemDisplayContext, LivingEntity livingEntity) {
        ((IItemStackRenderState)itemStackRenderState).polymer_patcher$updateData(itemStack, itemDisplayContext);
        markNotEmpty(itemStackRenderState);
    }
}
