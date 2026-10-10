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
    private static int shotAt = -1;

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
            // Nobody is there to press the button, and a test player standing in the death screen sees nothing
            if (client.player.isDeadOrDying() && ticks % 40 == 0) {
                CompanionMod.LOGGER.info("selftest: died, respawning");
                client.player.respawn();
            }
            ticks++;
            if (ticks == shotAt) {
                Screenshot.grab(client, false);
                if (client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            }
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
                        // A few frames later, with the HUD hidden, so the shot is of the world and nothing on top
                        if (!client.gui.hud.isHidden()) {
                            client.gui.hud.toggle();
                        }
                        shotAt = ticks + 5;
                    } else if (command.equals("hotbar")) {
                        for (int slot = 0; slot < 9; slot++) {
                            var stack = client.player.getInventory().getItem(slot);
                            if (!stack.isEmpty()) {
                                CompanionMod.LOGGER.info("selftest: hotbar {} {} model={}", slot, stack.getHoverName().getString(), stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL));
                            }
                        }
                    } else if (command.equals("entities")) {
                        java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
                        for (var e : client.level.entitiesForRendering()) {
                            if (e.distanceTo(client.player) > 20) {
                                continue;
                            }
                            String what = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                            if (e instanceof net.minecraft.world.entity.Display.ItemDisplay display) {
                                var model = display.getItemStack().get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
                                what += " " + (model == null ? display.getItemStack().getItem() : model.toString().replaceAll("part_[0-9]+", "part_N"));
                                if (model != null && model.getPath().contains("flame_construct") && model.getPath().endsWith("part_2")) {
                                    Object scale = "?", translation = "?";
                                    try {
                                        var sf = net.minecraft.world.entity.Display.class.getDeclaredField("DATA_SCALE_ID"); sf.setAccessible(true);
                                        var tf = net.minecraft.world.entity.Display.class.getDeclaredField("DATA_TRANSLATION_ID"); tf.setAccessible(true);
                                        scale = display.getEntityData().get((net.minecraft.network.syncher.EntityDataAccessor<?>) sf.get(null));
                                        translation = display.getEntityData().get((net.minecraft.network.syncher.EntityDataAccessor<?>) tf.get(null));
                                    } catch (Exception ex) { scale = ex.toString(); }
                                    CompanionMod.LOGGER.info("selftest: flame part_2 scale={} translation={} viewRange={}", scale, translation, display.getViewRange());
                                }
                            }
                            if (e instanceof net.minecraft.world.entity.LivingEntity living && what.contains("warden")) {
                                StringBuilder data = new StringBuilder();
                                var items = living.getEntityData().getNonDefaultValues();
                                if (items != null) {
                                    for (var item : items) {
                                        data.append(item.id()).append('=').append(item.value()).append(' ');
                                    }
                                }
                                CompanionMod.LOGGER.info("selftest: {} invisible={} health={} dead={} scale={} bb={} renderer={} data[{}]", what, living.isInvisible(),
                                    living.getHealth(), living.isDeadOrDying(), living.getScale(), living.getBoundingBox(),
                                    client.getEntityRenderDispatcher().getRenderer(living).getClass().getName(), data);
                            }
                            what += " @" + e.blockPosition().toShortString();
                            seen.merge(what.replaceAll(" @.*", "") + " near " + (e.blockPosition().getX() / 3 * 3) + "," + (e.blockPosition().getZ() / 3 * 3), 1, Integer::sum);
                        }
                        seen.forEach((k, v) -> CompanionMod.LOGGER.info("selftest: entity {} x{}", k, v));
                    } else if (command.equals("hudshot")) {
                        shotAt = ticks + 5;
                    } else if (command.startsWith("look ")) {
                        String[] angles = command.substring(5).trim().split(" ");
                        client.player.setYRot(Float.parseFloat(angles[0]));
                        client.player.setXRot(Float.parseFloat(angles[1]));
                    } else if (command.equals("use")) {
                        // A right click, aimed by the client's own idea of what is in front of it
                        var hit = client.hitResult;
                        if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            CompanionMod.LOGGER.info("selftest: use, the client aims at {} {}, which it sees as {}, holding {}", block.getBlockPos(), block.getDirection(),
                                client.level.getBlockState(block.getBlockPos()), client.player.getMainHandItem());
                            client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, block);
                        } else {
                            CompanionMod.LOGGER.info("selftest: use, the client aims at nothing");
                            client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                    } else if (command.startsWith("probe")) {
                        // How the player is moving as their own client sees it, which is all that decides it
                        var player = client.player;
                        CompanionMod.LOGGER.info("selftest: {} at {} motion={} speed={} jump={} gravity={} walkingSpeed={} fovModifier={}",
                            command, String.format("%.3f %.3f %.3f", player.getX(), player.getY(), player.getZ()), player.getDeltaMovement(),
                            player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
                            player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH),
                            player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                            player.getAbilities().getWalkingSpeed(), player.getFieldOfViewModifier(true, 1.0F));
                        CompanionMod.LOGGER.info("selftest: {} holding {}; aiming at {}", command, player.getMainHandItem(),
                            client.hitResult instanceof net.minecraft.world.phys.BlockHitResult aimed ? client.level.getBlockState(aimed.getBlockPos()) : "nothing");
                        CompanionMod.LOGGER.info("selftest: {} client sees {} with fluid {} (inWater={} onClimbable={})", command,
                            client.level.getBlockState(player.blockPosition()), client.level.getFluidState(player.blockPosition()), player.isInWater(), player.onClimbable());
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
