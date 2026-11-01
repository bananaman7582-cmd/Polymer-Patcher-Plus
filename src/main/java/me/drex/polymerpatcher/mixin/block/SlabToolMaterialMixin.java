package me.drex.polymerpatcher.mixin.block;

import me.drex.polymerpatcher.block.SlabMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Tool speed and harvest suitability must agree when a slab mod omitted its mining tags. */
@Mixin(ItemStack.class)
public abstract class SlabToolMaterialMixin {
    @ModifyVariable(method = {"getDestroySpeed", "isCorrectToolForDrops"}, at = @At("HEAD"), argsOnly = true)
    private BlockState polymerPatcher$missingSlabToolTags(BlockState state) {
        return SlabMaterials.miningState(state);
    }
}
