package me.drex.polymerpatcher.compat.witherstorm;

import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.blocks.impl.BlockExtBlockMapper;
import eu.pb4.polymer.core.api.block.BlockMapper;
import io.netty.buffer.Unpooled;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import me.drex.polymerpatcher.util.NativeClients;
import me.drex.polymerpatcher.util.NativeItemConnection;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in test mod. Run ONLY on a throwaway Fabric server, never the live server. */
public final class WitherStormWireIntegration implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                verify(server);
                System.out.println("WITHERSTORM_WIRE_CHECK_PASS");
            } catch (Throwable error) {
                System.err.println("WITHERSTORM_WIRE_CHECK_FAIL");
                error.printStackTrace();
            } finally {
                server.halt(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void verify(MinecraftServer server) throws Exception {
        EntityDataSerializer<Map<BlockPos, BlockState>> serializer = (EntityDataSerializer<Map<BlockPos, BlockState>>)
            Class.forName("nonamecrackers2.witherstormmod.common.serializer.WitherStormModDataSerializers")
                .getField("BLOCK_STATE_POS_MAP").get(null);
        int checked = 0;
        for (boolean nativeClient : new boolean[] {false, true}) {
            GameProfile profile = new GameProfile(UUID.randomUUID(), nativeClient ? "NativeStormCheck" : "CarrierStormCheck");
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            ((NativeItemConnection) connection).polymerPatcher$setSameVersionMods(
                nativeClient ? Set.of("witherstormmod") : Set.of());
            ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
            player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));
            var listener = Connection.class.getDeclaredField("packetListener");
            listener.setAccessible(true);
            listener.set(connection, player.connection);
            // A real connection enables this mapper after accepting the server resource pack. The
            // socketless fixture has no pack handshake, so enable the same mapper explicitly.
            BlockMapper.set(player.connection, BlockExtBlockMapper.INSTANCE);
            if (nativeClient && !NativeClients.carries(player, "witherstormmod")) {
                throw new AssertionError("Native connection fixture was not recognized");
            }
            for (var entry : BlockExtBlockMapper.INSTANCE.stateMap.entrySet()) {
                BlockState original = entry.getKey();
                if (!(original.getBlock() instanceof LeavesBlock)) continue;
                BlockState canonical = entry.getValue();
                Map<BlockPos, BlockState> blocks = Map.of(BlockPos.ZERO, original);
                int wire = PacketContext.supplyWithContext(player.connection,
                    () -> encode(serializer, blocks, server));
                int expected = BlockSyncCheck.clientRawId(canonical);
                if (wire != expected || blocks.get(BlockPos.ZERO) != original) {
                    throw new AssertionError("Wrong cluster leaf for native=" + nativeClient + ": "
                        + original + " expected " + canonical + " at " + expected + " got " + wire);
                }
                // No recipient: local writes must retain the actual state, not its client picture.
                if (encode(serializer, blocks, server) != Block.getId(original)) {
                    throw new AssertionError("Local cluster state was changed");
                }
                checked++;
            }
            NativeClients.forget(player);
        }
        if (checked == 0) throw new AssertionError("No borrowed leaf states; regression fixture not exercised");
        System.out.println("Verified " + checked + " actual Wither Storm cluster leaf writes for native and carrier contexts");
    }

    private static int encode(EntityDataSerializer<Map<BlockPos, BlockState>> serializer,
                              Map<BlockPos, BlockState> blocks, MinecraftServer server) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            serializer.codec().encode(buffer, blocks);
            if (buffer.readVarInt() != 1 || !buffer.readBlockPos().equals(BlockPos.ZERO)) {
                throw new AssertionError("Cluster map wire format changed");
            }
            int state = buffer.readVarInt();
            if (buffer.isReadable()) throw new AssertionError("Unexpected trailing cluster bytes");
            return state;
        } finally {
            buffer.release();
        }
    }
}
