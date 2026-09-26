package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swings the arm for a block a stranger was never shown as a block.
 * <p>
 * Nothing sends the swing when a player places something: the client plays it itself, the moment it
 * decides its own click will place a block, and only then tells the server it swung. A modded block
 * reaches a stranger as some vanilla item that is not a block at all - a trial key - so that decision
 * comes out the other way, and the arm stays still while the block appears. It looks like the game
 * missed the click.
 * <p>
 * So where the client had no way of knowing, the server says it: only for a placement that actually
 * happened, and only where the item the player was shown could not have placed anything. A player who
 * was shown a real block item swung on their own and is left alone, because saying it twice is a
 * second swing.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void polymer_patcher$giveRangedWeaponPriorityOverShield(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (me.drex.polymerpatcher.compat.alexscaves.ResistorShieldEffects
            .suppressOffhandUse(player, hand, stack)) {
            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                player.stopUsingItem();
            }
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }

    /** useItemOn is allowed to consume the last item before its RETURN injection runs. */
    @Unique
    private ItemStack polymer_patcher$placingStack = ItemStack.EMPTY;
    @Unique private BlockPos polymer_patcher$clickedPos;
    @Unique private BlockPos polymer_patcher$adjacentPos;
    @Unique private BlockState polymer_patcher$clickedBefore;
    @Unique private BlockState polymer_patcher$adjacentBefore;

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void polymer_patcher$rememberPlacedItem(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> callback
    ) {
        polymer_patcher$placingStack = stack.copy();
        polymer_patcher$clickedPos = hitResult.getBlockPos().immutable();
        polymer_patcher$adjacentPos = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
        polymer_patcher$clickedBefore = level.getBlockState(polymer_patcher$clickedPos);
        polymer_patcher$adjacentBefore = level.getBlockState(polymer_patcher$adjacentPos);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void polymer_patcher$swingForBlocksShownAsItems(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> callback
    ) {
        ItemStack placed = polymer_patcher$placingStack;
        polymer_patcher$placingStack = ItemStack.EMPTY;
        BlockPos clickedPos = polymer_patcher$clickedPos;
        BlockPos adjacentPos = polymer_patcher$adjacentPos;
        BlockState clickedBefore = polymer_patcher$clickedBefore;
        BlockState adjacentBefore = polymer_patcher$adjacentBefore;
        polymer_patcher$clickedPos = null;
        polymer_patcher$adjacentPos = null;
        polymer_patcher$clickedBefore = null;
        polymer_patcher$adjacentBefore = null;
        if (!callback.getReturnValue().consumesAction() || placed.isEmpty()
            || clickedPos == null || adjacentPos == null) {
            return;
        }

        try {
            // Detect the result rather than guessing from an item class. Several mods place blocks
            // from ordinary Items, while some Polymer BlockItem carriers with custom definitions are
            // not predicted by the client. A changed legal placement position is the global fact.
            boolean changed = clickedBefore != level.getBlockState(clickedPos)
                || adjacentBefore != level.getBlockState(adjacentPos);
            if (!changed) {
                return;
            }
            // Sent to the player as well as to everyone watching - the swing they never played is the
            // whole point, and the onlookers never got one either because none was ever reported
            player.swing(hand, true);
        } catch (Throwable ignored) {
            // A swing is not worth an interaction
        }
    }
}
