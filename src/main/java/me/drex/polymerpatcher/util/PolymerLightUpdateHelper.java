package me.drex.polymerpatcher.util;

import eu.pb4.polymer.core.impl.interfaces.PolymerChunkSectionStorage;
import eu.pb4.polymer.core.impl.interfaces.PolymerChunkStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.function.Supplier;

public final class PolymerLightUpdateHelper {
    public static final ThreadLocal<Level> LEVEL_CONTEXT = ThreadLocal.withInitial(() -> null);
    private static final Direction[] LIGHT_SAMPLE_DIRECTIONS = new Direction[] {
        Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private PolymerLightUpdateHelper() {
    }

    public static <T> T runWithLevel(Level level, Supplier<T> runnable) {
        try {
            LEVEL_CONTEXT.set(level);
            return runnable.get();
        } finally {
            LEVEL_CONTEXT.remove();
        }
    }

    /**
     * Set when Polymer's own bookkeeping turns out not to be where this expects it, which is a version of
     * Polymer this was not built against. It has happened twice, and both times it took the server down with
     * it: this runs while a light packet is being written, on the tick loop, for every chunk sent. Light that
     * is left as the game wrote it is a lit block looking wrong; a server that stops is everybody's evening.
     */
    private static volatile boolean polymerLightUnavailable;

    public static void patchLightData(ClientboundLightUpdatePacketData data, ChunkPos chunkPos, LevelLightEngine lightEngine,
                                      @Nullable BitSet skyChangedLightSectionFilter, @Nullable BitSet blockChangedLightSectionFilter) {
        if (polymerLightUnavailable) {
            return;
        }

        var level = LEVEL_CONTEXT.get();
        if (level == null) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());

        try {
            if (chunk == null || !chunk.getPos().equals(chunkPos) || !((PolymerChunkStorage) chunk).polymer$hasAny()) {
                return;
            }

            patchLightLayer(data.getSkyYMask(), data.getSkyUpdates(), chunk, lightEngine, LightLayer.SKY, skyChangedLightSectionFilter);
            patchLightLayer(data.getBlockYMask(), data.getBlockUpdates(), chunk, lightEngine, LightLayer.BLOCK, blockChangedLightSectionFilter);
        } catch (LinkageError e) {
            polymerLightUnavailable = true;
            me.drex.polymerpatcher.PolymerPatcher.LOGGER.error(
                "Polymer does not keep its record of modded blocks where this expects it, so the light around them is left as the game sent it. "
                    + "This usually means Polymer has been updated past the version this was built against.", e);
        }
    }

    private static void patchLightLayer(BitSet mask, List<byte[]> updates, LevelChunk chunk, LevelLightEngine lightEngine,
                                        LightLayer layer, @Nullable BitSet changedLightSectionFilter) {
        var listener = lightEngine.getLayerListener(layer);
        var sections = chunk.getSections();
        var mutable = new BlockPos.MutableBlockPos();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null) {
                continue;
            }

            var storage = (PolymerChunkSectionStorage) section;
            if (!storage.polymer$hasAny()) {
                continue;
            }

            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            int lightSectionIndex = sectionY - lightEngine.getMinLightSection();

            if (changedLightSectionFilter != null && !changedLightSectionFilter.get(lightSectionIndex)) {
                continue;
            }

            if (!mask.get(lightSectionIndex)) {
                  continue;
            }
            byte[] update = updates.get(mask.get(0, lightSectionIndex).cardinality());

            for (var iterator = storage.polymer$blockIterator(SectionPos.of(chunk.getPos(), sectionY)); iterator.hasNext();) {
                var pos = iterator.next();
                int value = getBestLightValue(listener, mutable, pos);

                setNibble(update, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, value);
            }
        }
    }

    private static int getBestLightValue(LayerLightEventListener listener, BlockPos.MutableBlockPos mutable, BlockPos pos) {
        int value = listener.getLightValue(pos);

        for (var direction : LIGHT_SAMPLE_DIRECTIONS) {
            mutable.setWithOffset(pos, direction);
            value = Math.max(value, listener.getLightValue(mutable));
        }

        return value;
    }

    private static void setNibble(byte[] data, int x, int y, int z, int val) {
        // Matches DataLayer.set(int x, int y, int z, int val)
        int index = y << 8 | z << 4 | x;
        int position = index >> 1;
        int nibble = index & 1;
        int mask = ~(15 << 4 * nibble);
        int valueToSet = (val & 0xF) << 4 * nibble;
        data[position] = (byte)(data[position] & mask | valueToSet);
    }
}
