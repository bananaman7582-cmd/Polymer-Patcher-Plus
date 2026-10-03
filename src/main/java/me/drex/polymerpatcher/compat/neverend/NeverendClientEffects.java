package me.drex.polymerpatcher.compat.neverend;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.ClientParticleReplay;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Replays Neverend visuals and ambience which otherwise exist only in its client source set. */
public final class NeverendClientEffects {
    private static final Identifier POOLROOMS = NeverendCompatibility.id("poolrooms");
    private static final Identifier EXIT = NeverendCompatibility.id("liminal_exit");
    private static final Identifier OCTANT = NeverendCompatibility.id("octant");
    private static final Identifier LEVEL_1 = NeverendCompatibility.id("ambient.level1.loop");
    private static final Identifier LEVEL_2 = NeverendCompatibility.id("ambient.level2.loop");
    private static final Identifier LEVEL_3 = NeverendCompatibility.id("ambient.level3.loop");
    private static final Identifier ADDITION = NeverendCompatibility.id("ambient.level3.loop.additions");
    private static final Identifier ADDITION_RARE = NeverendCompatibility.id("ambient.level3.loop.additions.rare");
    private static final Identifier ADDITION_ULTRA = NeverendCompatibility.id("ambient.level3.loop.additions.ultra_rare");
    private static final Set<Entity> EXITS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID, AmbientState> AMBIENCE = new HashMap<>();

    private NeverendClientEffects() {
    }

    static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (EXIT.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) EXITS.add(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> EXITS.remove(entity));
        ServerTickEvents.END_SERVER_TICK.register(NeverendClientEffects::tick);
    }

    public static void forget(ServerPlayer player) {
        AMBIENCE.remove(player.getUUID());
    }

    private static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % 10 == 0) {
            drawExits();
            showHud(server);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickAmbience(player, tick);
        }
        AMBIENCE.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
    }

    private static void drawExits() {
        for (Entity exit : new ArrayList<>(EXITS)) {
            if (exit.isRemoved() || !(exit.level() instanceof ServerLevel level)) {
                EXITS.remove(exit);
                continue;
            }
            for (ServerPlayer viewer : ClientParticleReplay.shownStandInFor(exit, NeverendCompatibility.MOD_ID)) {
                level.sendParticles(viewer, ParticleTypes.GLOW, true, false,
                    exit.getX(), exit.getY() + exit.getBbHeight() / 2.0, exit.getZ(),
                    1, 0.04, 0.04, 0.04, 0.0);
            }
        }
    }

    private static void showHud(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (NativeClients.carries(player, NeverendCompatibility.MOD_ID)) {
                continue;
            }

            if (is(player.getMainHandItem(), OCTANT) || is(player.getOffhandItem(), OCTANT)) {
                player.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.literal(player.chunkPosition().toString()).withColor(0x00FFAA)));
                continue;
            }

            // The native client shows a full-screen sinking overlay in the safe shafts. A server
            // cannot inject a custom HUD renderer into vanilla, so retain the gameplay information
            // as a compact action-bar meter instead of leaving the player unaware of the hazard.
            if (POOLROOMS.equals(player.level().dimension().identifier()) && player.getY() <= 120.0
                && NeverendFloatingTextModel.isSafeChunk(player.chunkPosition().x(),
                    player.chunkPosition().z(), player.level().getSeed())) {
                int danger = (int) Math.round(Math.clamp((120.0 - player.getY()) / 50.0, 0.0, 1.0) * 100.0);
                player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("SINKING  " + danger + "%")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)));
            }
        }
    }

    private static void tickAmbience(ServerPlayer player, int now) {
        AmbientState previous = AMBIENCE.get(player.getUUID());
        if (NativeClients.carries(player, NeverendCompatibility.MOD_ID)) {
            AMBIENCE.remove(player.getUUID());
            return;
        }

        int band = band(player);
        if (band == 0) {
            if (previous != null) stop(player, soundId(previous.band));
            AMBIENCE.remove(player.getUUID());
            return;
        }

        int next = previous == null ? now : previous.nextRestart;
        if (previous == null || previous.band != band) {
            if (previous != null) stop(player, soundId(previous.band));
            next = now;
        }
        if (now >= next) {
            Identifier id = soundId(band);
            SoundEvent sound = sound(id);
            if (sound != null) {
                ClientParticleReplay.playTo(java.util.List.of(player), player.getX(), player.getY(), player.getZ(),
                    sound, SoundSource.AMBIENT, 1.0F, 1.0F);
            }
            next = now + duration(band) - 2;
        }
        AMBIENCE.put(player.getUUID(), new AmbientState(band, next));

        if (band == 3 && player.isUnderWater()) {
            float roll = player.getRandom().nextFloat();
            Identifier addition = roll < 0.0001F ? ADDITION_ULTRA
                : roll < 0.001F ? ADDITION_RARE : roll < 0.005F ? ADDITION : null;
            if (addition != null) {
                SoundEvent sound = sound(addition);
                if (sound != null) {
                    ClientParticleReplay.playTo(java.util.List.of(player), player.getX(), player.getY(), player.getZ(),
                        sound, SoundSource.AMBIENT, 1.0F, 1.0F);
                }
            }
        }
    }

    private static int band(ServerPlayer player) {
        if (!POOLROOMS.equals(player.level().dimension().identifier())) return 0;
        if (player.getY() > 301.0) return 1;
        if (player.getY() > 266.0) return 2;
        return player.isUnderWater() ? 3 : 0;
    }

    private static int duration(int band) {
        return switch (band) {
            case 1 -> 2_120;
            case 2 -> 2_740;
            case 3 -> 1_760;
            default -> 20;
        };
    }

    private static Identifier soundId(int band) {
        return switch (band) {
            case 1 -> LEVEL_1;
            case 2 -> LEVEL_2;
            case 3 -> LEVEL_3;
            default -> LEVEL_1;
        };
    }

    private static SoundEvent sound(Identifier id) {
        Holder.Reference<SoundEvent> found = BuiltInRegistries.SOUND_EVENT.get(id).orElse(null);
        return found == null ? null : found.value();
    }

    private static void stop(ServerPlayer player, Identifier id) {
        player.connection.send(new ClientboundStopSoundPacket(id, SoundSource.AMBIENT));
    }

    private static boolean is(ItemStack stack, Identifier id) {
        return !stack.isEmpty() && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private record AmbientState(int band, int nextRestart) {
    }
}
