package me.drex.polymerpatcher.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.duck.IItemStackRenderState;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements IItemStackRenderState {
    @Shadow
    ItemDisplayContext displayContext;
    @Unique
    private ItemStack polymer_patcher$Item = null;

    @Override
    public void polymer_patcher$updateData(ItemStack item, ItemDisplayContext context) {
        polymer_patcher$Item = item;
        this.displayContext = context;
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    public void updateItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int i, int j, int k, CallbackInfo ci) {
        SimpleEntityModel simpleEntityModel = ServerSubmitNodeCollector.ACTIVE_ENTITY.get();
        if (simpleEntityModel != null && polymer_patcher$Item != null) {
            simpleEntityModel.updateItem(polymer_patcher$Item, displayContext, poseStack.last().pose());
            // Nothing below this knows what to do with the empty layer the resolver added purely to be
            // counted, and the item has already been dealt with
            ci.cancel();
        }
    }
}
