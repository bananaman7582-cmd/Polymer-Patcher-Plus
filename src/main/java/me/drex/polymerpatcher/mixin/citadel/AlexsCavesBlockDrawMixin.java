package me.drex.polymerpatcher.mixin.citadel;

import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.entity.citadel.CitadelDraw;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches a whole block drawn as part of something else.
 * <p>
 * Not everything Alex's Caves draws is a model. A magnetron is a body of blocks it has torn out of the
 * world and wrapped around itself, and there are blocks being flung, crushed and toppled besides. Each
 * is drawn through a helper of the mod's own, which on a server reaches for a block renderer that is
 * not there - so a magnetron arrives as a head and two hands with nothing in between.
 * <p>
 * The block is the one thing that helper is handed, and drawing a block as part of an entity is
 * something this mod already does for falling blocks. So it is taken here and drawn that way, at the
 * pose the renderer had built for it.
 * <p>
 * The buffer argument is declared loosely because its type belongs to the mod and cannot be named from
 * here; nothing is done with it either way.
 */
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.ACClientCompat", remap = false)
public abstract class AlexsCavesBlockDrawMixin {

    @Inject(
        method = "renderSingleBlock",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void polymer_patcher$takeTheBlockInstead(
        BlockState blockState, PoseStack poseStack, @Coerce Object buffers, int light, int overlay,
        CallbackInfo callback
    ) {
        if (CitadelDraw.takeBlock(blockState, poseStack)) {
            callback.cancel();
        }
    }

    /**
     * The same again for a block drawn with a colour over it - what a nuclear bomb is drawn as, and
     * why it arrived as nothing at all. The tint is dropped: a block display has no way to carry one,
     * and an untinted block is a great deal closer than an absent one.
     */
    @Inject(
        method = "renderTintedBlock",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void polymer_patcher$takeTheTintedBlockInstead(
        BlockState blockState, PoseStack poseStack, @Coerce Object buffers,
        float red, float green, float blue, int light, int overlay,
        CallbackInfo callback
    ) {
        if (CitadelDraw.takeBlock(blockState, poseStack)) {
            callback.cancel();
        }
    }

    /**
     * An item drawn as part of something else - a teletor's magnetic weapon is drawn this way, as the
     * item it is carrying rather than as a model of its own.
     */
    @Inject(
        method = "renderItemStatic",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void polymer_patcher$takeTheItemInstead(
        net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.ItemDisplayContext context,
        int light, int overlay, PoseStack poseStack, @Coerce Object buffers,
        net.minecraft.world.level.Level level, int seed,
        CallbackInfo callback
    ) {
        if (CitadelDraw.takeItem(stack, poseStack)) {
            callback.cancel();
        }
    }
}
