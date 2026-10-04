package me.drex.polymerpatcher.companion.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.TreeMap;

/**
 * An unattended check for development, never active unless asked for.
 * <p>
 * Started with {@code -Dpolymerpatcher.companion.selftest=cmd1;cmd2}, it waits until the player is in a
 * world, runs those commands, counts which blocks around the player are this mod's copies and which are
 * still carriers, saves a screenshot and closes the game. That is enough to tell from a log and a
 * picture whether the copies are being swapped in and drawn.
 */
final class SelfTest {

    private SelfTest() {
    }

    private static int ticks = -1;
    private static int sinceStart;
    private static int stopAt = -1;
    private static int next;
    private static int resumeAt;
    private static int shift;
    private static boolean reportedScreen;

    /** Stands in for a payload of a mod this client does not really have; never actually received. */
    private record FakePayload(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<FakePayload> type) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
    }

    static void init() {
        // Opens a channel in some other mod's name, so the client looks as if it had that mod at another
        // version than the server's - which is what the server's version check is there to catch
        // Given as "late:<channel>", it is only opened once in the game, the way a client that did not list its
        // channels while joining would open it
        String fakeChannel = System.getProperty("polymerpatcher.companion.fakechannel");
        if (fakeChannel != null && !fakeChannel.isBlank()) {
            boolean late = fakeChannel.startsWith("late:");
            var type = new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<FakePayload>(
                net.minecraft.resources.Identifier.parse(late ? fakeChannel.substring(5) : fakeChannel));
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(type,
                net.minecraft.network.codec.StreamCodec.unit(new FakePayload(type)));
            if (late) {
                ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerReceiver(type, (payload, context) -> {
                    }));
            } else {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
                });
            }
        }

        String spec = System.getProperty("polymerpatcher.companion.selftest");
        if (spec == null) {
            return;
        }
        String[] commands = spec.isBlank() ? new String[0] : spec.split(";");
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ticks = 0);
        // Never left running: a failed join, or anything else, still ends the test
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopAt = sinceStart + 100);
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> stopAt = sinceStart + 100);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            sinceStart++;
            // What a player turned away would actually read, rather than what the server meant to say
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen screen && !reportedScreen) {
                reportedScreen = true;
                CompanionMod.LOGGER.info("selftest: disconnect screen says: {}", screen.getNarrationMessage().getString().replace("\n", " | "));
            }
            if (sinceStart == 20 * 60 * 10 || sinceStart == stopAt) {
                CompanionMod.LOGGER.info("selftest: stopping (disconnected or out of time)");
                client.stop();
                return;
            }
            if (ticks < 0 || client.player == null || client.level == null) {
                return;
            }
            ticks++;
            // The first command on its own and early, so it can be the one that lets the rest through
            if (ticks == 200 && commands.length > 0) {
                client.player.connection.sendCommand(commands[0].trim());
            }
            // The rest in order from here; "wait N" holds the next ones back N ticks, and the whole test with them
            if (ticks >= 300 && ticks >= resumeAt && next < commands.length) {
                if (next == 0) {
                    next = 1;
                }
                while (next < commands.length && ticks >= resumeAt) {
                    String command = commands[next++].trim();
                    if (command.equals("screenshot")) {
                        Screenshot.grab(client, false);
                    } else if (command.startsWith("wait ")) {
                        int wait = Integer.parseInt(command.substring(5).trim());
                        resumeAt = ticks + wait;
                        shift += wait;
                    } else {
                        client.player.connection.sendCommand(command);
                    }
                }
            }
            int at = ticks - shift;
            if (at == 600 || at == 900) {
                report(client);
            }
            if (at == 620 || at == 920) {
                Screenshot.grab(client, false);
            }
            if (at == 960) {
                CompanionMod.LOGGER.info("selftest: done");
                client.stop();
            }
        });
    }

    private static void report(Minecraft client) {
        Map<String, Integer> copies = new TreeMap<>();
        int carriers = 0;
        int total = 0;
        ChunkPos centre = client.player.chunkPosition();
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = client.level.getChunkSource().getChunk(centre.x() + dx, centre.z() + dz, false);
                if (chunk == null) {
                    continue;
                }
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section.hasOnlyAir()) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                Block block = state.getBlock();
                                total++;
                                if (ProxyRegistry.isCopy(block)) {
                                    copies.merge(BuiltInRegistries.BLOCK.getKey(block).toString(), 1, Integer::sum);
                                } else if (isCarrierish(block)) {
                                    carriers++;
                                }
                            }
                        }
                    }
                }
            }
        }
        int copyCount = copies.values().stream().mapToInt(Integer::intValue).sum();
        CompanionMod.LOGGER.info("selftest: decoding={} at {}: {} copied block(s) of {} kind(s), {} likely carrier(s), {} states looked at",
            CompanionState.decoding(), client.player.blockPosition(), copyCount, copies.size(), carriers, total);
        CompanionMod.LOGGER.info("selftest: copies {}", copies);
        // Whether the real blocks reached the tags that decide the right tool and the mining speed
        for (String id : copies.keySet()) {
            BlockState state = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id)).defaultBlockState();
            CompanionMod.LOGGER.info("selftest: {} pickaxe={} axe={} shovel={} hoe={} hardness={} needsTool={} sound={}", id,
                state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE), state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE),
                state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL), state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE),
                state.getDestroySpeed(client.level, client.player.blockPosition()), state.requiresCorrectToolForDrops(),
                state.getSoundType().getStepSound().location());
        }
    }

    /** Blocks Polymer's carriers are made of: a rough count of what was not swapped. */
    private static boolean isCarrierish(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return id.equals("note_block") || id.equals("tripwire") || id.equals("barrier")
            || id.contains("mushroom_block") || id.endsWith("_leaves") && id.startsWith("oak");
    }
}
