package me.drex.polymerpatcher.block;

import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Opt-in test with the actual backport, real tags, carriers, tools, and both placement orders. */
public final class SlabIntegration implements ModInitializer {
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
                var pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
                int states = 0;
                for (String color : colors) {
                    var slab = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + color + "_concrete_slab"));
                    require(slab instanceof SlabBlock, "Missing actual concrete slab: " + color);
                    var material = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + color + "_concrete")).defaultBlockState();
                    var overlay = (PolymerBlock) PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, slab);
                    require(overlay instanceof AutomaticFactoryBlock, "Missing automatic slab overlay");
                    for (var state : slab.getStateDefinition().getPossibleStates()) {
                        var shown = overlay.getPolymerBlockState(state, null);
                        require(shown.getLightEmission() <= state.getLightEmission(), "Glowing carrier: " + state + " -> " + shown);
                        require(pickaxe.getDestroySpeed(state) == pickaxe.getDestroySpeed(material), "Wrong pickaxe speed: " + state);
                        if (state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                            require(shown == material, "Double did not reuse material: " + state + " -> " + shown);
                            require(!((AutomaticFactoryBlock) overlay).requiresElementHolder().contains(state), "Double wasted a display");
                        }
                        states++;
                    }
                }
                require(SlabMaterials.miningState(Blocks.STONE.defaultBlockState()) == Blocks.STONE.defaultBlockState(), "Changed vanilla stone");
                require(SlabMaterials.miningState(Blocks.OAK_SLAB.defaultBlockState()) == Blocks.OAK_SLAB.defaultBlockState(), "Changed vanilla slabs");

                var level = server.overworld();
                var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "SlabTest"), ClientInformation.createDefault());
                player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false));
                var slab = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:gray_concrete_slab"));
                for (boolean topFirst : new boolean[]{false, true}) {
                    var pos = new BlockPos(topFirst ? 2 : 0, 100, 0);
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(pos.west(), Blocks.STONE.defaultBlockState());
                    var stack = new ItemStack(slab.asItem(), 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    var first = topFirst
                        ? new BlockHitResult(new Vec3(pos.getX(), pos.getY() + .75, .5), Direction.EAST, pos.west(), false)
                        : new BlockHitResult(new Vec3(pos.getX() + .5, pos.getY(), .5), Direction.UP, pos.below(), false);
                    require(((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, first)).consumesAction(), "First placement failed");
                    require(level.getBlockState(pos).getValue(SlabBlock.TYPE) == (topFirst ? SlabType.TOP : SlabType.BOTTOM), "Wrong first slab half");
                    var second = new BlockHitResult(new Vec3(pos.getX() + .5, pos.getY() + .5, .5),
                        topFirst ? Direction.DOWN : Direction.UP, pos, false);
                    require(((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, second)).consumesAction(), "Second placement failed");
                    var doubled = level.getBlockState(pos);
                    require(doubled.getValue(SlabBlock.TYPE) == SlabType.DOUBLE, "Slabs did not merge");
                    require(SlabMaterials.doubleCarrier(doubled) == BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:gray_concrete")).defaultBlockState(), "Placement order lost double texture");
                }
                System.out.println("SLAB_INTEGRATION_PASS: " + states + " concrete states, tool speed, non-emissive carriers, vanilla double reuse, both real placement orders");
            } catch (Throwable error) {
                System.err.println("SLAB_INTEGRATION_FAIL");
                error.printStackTrace();
            } finally {
                server.halt(false);
            }
        });
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
