package me.drex.polymerpatcher.block;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

/**
 * Points a player's click at the block they were actually looking at.
 * <p>
 * A client works out what it clicked from the shapes it knows, and for a modded block those are the
 * shapes of whatever stands in for it. Often that is close enough. Sometimes it is nothing like it: a block
 * with no carrier left is a structure void under its display, and all a client can click on is a small box
 * in its middle. Webbed's hanging webs are full-height sheets, and a web is hung beneath another by clicking
 * the underside of that sheet - so aiming at the web you could see went straight past the little box and
 * hung the new web from the ceiling beside the chain, unless the angle happened to land on the box. That is
 * why a row of them connected from some angles and not from others.
 * <p>
 * The server knows the real shapes. It follows the line the player clicked along and, if a block whose
 * shape their client could not know is in the way, the click is given to that block, on the face the line
 * really meets - which is what a client with the mod would have clicked. A click that met nothing at all,
 * because the only thing there was such a block, is treated the same way.
 */
public final class HiddenShapeClicks {
    /** How far past the point the client stopped at to keep looking, so the rest of the block it clicked is seen. */
    private static final double PAST_THE_CLICK = 1.5;

    private HiddenShapeClicks() {
    }

    /** The block click a player sent, pointed at what they were really looking at. */
    public static BlockHitResult retarget(ServerPlayer player, BlockHitResult sent) {
        try {
            Vec3 eye = player.getEyePosition();
            Vec3 along = sent.getLocation().subtract(eye);
            if (along.lengthSqr() < 1.0E-6) {
                return sent;
            }
            Vec3 end = sent.getLocation().add(along.normalize().scale(PAST_THE_CLICK));
            BlockHitResult real = clip(player, eye, end);
            if (real == null) {
                return sent;
            }
            if (real.getBlockPos().equals(sent.getBlockPos())) {
                // The block they clicked, met on the face its real shape has there
                return real;
            }
            // Something else, and nearer than what the client clicked: only taken if the client could not see it
            return eye.distanceToSqr(real.getLocation()) <= eye.distanceToSqr(sent.getLocation()) ? real : sent;
        } catch (RuntimeException e) {
            return sent;
        }
    }

    /** A click on nothing, which may really have been on a block the client could not see the shape of. */
    public static @Nullable BlockHitResult fromAirClick(ServerPlayer player, float yRot, float xRot) {
        try {
            Vec3 eye = player.getEyePosition();
            Vec3 end = eye.add(Vec3.directionFromRotation(xRot, yRot).scale(player.blockInteractionRange()));
            return clip(player, eye, end);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Puts right what the client guessed wrongly about a click, because it was shown something else there.
     * <p>
     * A client acts on a click before the server answers, by what it was shown. Shown real waxed copper as
     * the unwaxed block it looks just like - or a modded block drawn by unwaxed copper - it waxed it, played
     * the wax and spent the honeycomb, all on its own; the server, knowing better, did none of it, and the
     * block came back but the honeycomb never did. So the held stack is sent again whenever the block clicked
     * was not what the client was shown. And the other way about: real copper shown as waxed that the player
     * really did wax gave them nothing to see, so the wax is played for them.
     */
    public static void afterClick(ServerPlayer player, net.minecraft.world.InteractionHand hand, BlockPos pos, BlockState before) {
        try {
            if (!clientSeesOtherwise(player, before)) {
                return;
            }
            int slot = hand == net.minecraft.world.InteractionHand.MAIN_HAND
                ? player.getInventory().getSelectedSlot()
                : net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket(slot, player.getItemInHand(hand).copy()));

            BlockState after = player.level().getBlockState(pos);
            if (after != before && net.minecraft.world.item.HoneycombItem.getWaxed(before).map(waxed -> waxed == after).orElse(false)) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(
                    net.minecraft.world.level.block.LevelEvent.PARTICLES_AND_SOUND_WAX_ON, pos, 0, false));
            }
        } catch (RuntimeException e) {
            // Nothing worse than before: the client finds out at the next full update
        }
    }

    /** Whether this player's client was shown something other than this state - its twin, or a stand-in. */
    private static boolean clientSeesOtherwise(ServerPlayer player, BlockState state) {
        if (eu.pb4.polymer.blocks.impl.BlockExtBlockMapper.INSTANCE.stateMap.containsKey(state)) {
            return true;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && !"minecraft".equals(id.getNamespace()) && !NativeClients.carries(player, id.getNamespace());
    }

    /** The first block along the line, by real shapes, if it is one this player's client gets wrong. */
    private static @Nullable BlockHitResult clip(ServerPlayer player, Vec3 from, Vec3 to) {
        ServerLevel level = player.level();
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return clientGetsWrong(player, level, hit.getBlockPos(), level.getBlockState(hit.getBlockPos())) ? hit : null;
    }

    /** Whether the shape this player's client has for this block is not the real one. */
    private static boolean clientGetsWrong(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || "minecraft".equals(id.getNamespace()) || NativeClients.carries(player, id.getNamespace())) {
            return false;
        }
        BlockState seen = PolymerBlockUtils.getPolymerBlockState(state, null);
        if (seen == state) {
            return false;
        }
        CollisionContext context = CollisionContext.of(player);
        return !Shapes.equal(state.getShape(level, pos, context), seen.getShape(level, pos, context));
    }
}
