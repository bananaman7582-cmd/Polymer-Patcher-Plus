package me.drex.polymerpatcher.block;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.util.ClientParticleReplay;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws modded blocks' ambient particles - the ones their {@code animateTick} makes on a client.
 * <p>
 * No client ever runs a modded block's own ambience: everybody, modded or not, is sent a carrier in its
 * place, and a carrier runs the carrier's. So the flora of an infested Sculk Horde landscape shed no crust
 * and a mod's glowing ore never sparked, for anyone.
 * <p>
 * This does what a client does, from the server, around each player: every tick it picks the same random
 * spots a client would ({@code ClientLevel.animateTick}, 667 within 16 blocks and 667 within 32) and runs
 * the block's own {@code animateTick} there, with the level acting as a client for the call so the mod
 * takes its client branch. Only blocks whose ambience is the mod's own are asked - a modded torch that
 * inherits vanilla's flame is left to {@link AmbientBlockEffects}. The spots are only looked at in
 * sections whose palette holds such a block at all, which is almost none of them, so a player standing
 * nowhere near one costs nothing but the dice.
 */
public final class ModdedBlockAmbience {

    private ModdedBlockAmbience() {
    }

    private static final int TRIES = 667;
    private static final int RESCAN_TICKS = 20;

    /** Whether each block class draws ambience of its mod's own; decided once per class. */
    private static final ClassValue<Boolean> OWN_AMBIENCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                try {
                    current.getDeclaredMethod("animateTick", BlockState.class, Level.class, BlockPos.class, RandomSource.class);
                    return !current.getName().startsWith("net.minecraft.");
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable e) {
                    return false;
                }
            }
            return false;
        }
    };

    private static final Set<Block> BROKEN = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Nearby> NEARBY = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private static final class Nearby {
        long scannedAt = Long.MIN_VALUE;
        Level level;
        final LongOpenHashSet sections = new LongOpenHashSet();
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ConfigManager.config().blocks.replayModdedBlockAmbience) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    tick(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not draw block ambience around {}", player.getGameProfile().name(), e);
                }
            }
            NEARBY.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        });
    }

    private static boolean draws(BlockState state) {
        Block block = state.getBlock();
        if (BROKEN.contains(block) || !OWN_AMBIENCE.get(block.getClass()) || AmbientBlockEffects.supports(state)) {
            return false;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && PolymerPatcher.PATCHED_MODS.contains(id.getNamespace())
            && !ConfigManager.config().blocks.blockAmbienceExcludedMods.contains(id.getNamespace());
    }

    private static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Nearby nearby = NEARBY.computeIfAbsent(player.getUUID(), id -> new Nearby());
        long now = level.getGameTime();
        if (nearby.level != level || now - nearby.scannedAt >= RESCAN_TICKS) {
            scan(level, player.blockPosition(), nearby);
            nearby.level = level;
            nearby.scannedAt = now;
        }
        if (nearby.sections.isEmpty()) {
            return;
        }

        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<ServerPlayer> to = List.of(player);
        for (int i = 0; i < TRIES; i++) {
            tryAt(level, origin, 16, pos, nearby, to);
            tryAt(level, origin, 32, pos, nearby, to);
        }
    }

    private static void tryAt(ServerLevel level, BlockPos origin, int radius, BlockPos.MutableBlockPos pos,
                              Nearby nearby, List<ServerPlayer> to) {
        pos.set(origin.getX() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius),
            origin.getY() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius),
            origin.getZ() + RANDOM.nextInt(radius) - RANDOM.nextInt(radius));
        if (!nearby.sections.contains(SectionPos.asLong(pos))) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!draws(state)) {
            return;
        }
        BlockPos at = pos.immutable();
        if (!ClientParticleReplay.run(level, to, () -> state.getBlock().animateTick(state, level, at, RANDOM))) {
            BROKEN.add(state.getBlock());
            PolymerPatcher.LOGGER.info("{}'s ambient effects need a client to draw and will not be shown",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
    }

    /** The sections around a player whose palette holds a block with ambience of its own. */
    private static void scan(ServerLevel level, BlockPos origin, Nearby nearby) {
        nearby.sections.clear();
        int cx = origin.getX() >> 4;
        int cy = origin.getY() >> 4;
        int cz = origin.getZ() >> 4;
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                for (int y = cy - 2; y <= cy + 2; y++) {
                    if (y < level.getMinSectionY() || y > level.getMaxSectionY()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(y));
                    if (!section.hasOnlyAir() && section.maybeHas(ModdedBlockAmbience::draws)) {
                        nearby.sections.add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
    }
}
