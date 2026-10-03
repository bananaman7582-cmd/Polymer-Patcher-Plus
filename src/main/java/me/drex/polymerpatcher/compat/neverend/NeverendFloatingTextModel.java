package me.drex.polymerpatcher.compat.neverend;

import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Vanilla text-display counterpart to Neverend's octant and sonar block-entity renderers. */
final class NeverendFloatingTextModel extends BlockModel {
    private final ServerLevel level;
    private final BlockPos pos;
    private final Identifier block;
    private final TextDisplayElement label = new TextDisplayElement(Component.empty());
    private Component last = Component.empty();

    NeverendFloatingTextModel(ServerLevel level, BlockPos pos, Identifier block) {
        this.level = level;
        this.pos = pos.immutable();
        this.block = block;
        label.setBillboardMode(Display.BillboardConstraints.CENTER);
        label.setViewRange(2);
        label.setShadow(true);
        label.setOffset(new Vec3(0, 1.2, 0));
        addElement(label);
        updateText();
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl connection) {
        return !NativeClients.carries(connection.getPlayer(), NeverendCompatibility.MOD_ID)
            && super.startWatching(connection);
    }

    @Override
    protected void onTick() {
        updateText();
        super.onTick();
    }

    private void updateText() {
        ChunkPos chunk = ChunkPos.containing(pos);
        Component next;
        if (block.equals(NeverendCompatibility.id("sonar"))) {
            ChunkPos safe = nearestSafeChunk(chunk, level.getSeed());
            String coordinates = safe == null ? "?" : safe.toString();
            boolean powered = level.getBlockState(pos).hasProperty(BlockStateProperties.POWERED)
                && level.getBlockState(pos).getValue(BlockStateProperties.POWERED);
            next = Component.literal(coordinates).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withObfuscated(!powered));
        } else {
            next = Component.literal(chunk.toString()).withStyle(ChatFormatting.AQUA);
        }
        if (!next.equals(last)) {
            last = next;
            label.setText(next);
        }
    }

    private static ChunkPos nearestSafeChunk(ChunkPos origin, long seed) {
        ChunkPos safe = null;
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                int candidateX = origin.x() + x;
                int candidateZ = origin.z() + z;
                if (!isSafeChunk(candidateX, candidateZ, seed)) {
                    continue;
                }
                ChunkPos candidate = new ChunkPos(candidateX, candidateZ);
                if (safe == null || candidate.getChessboardDistance(origin) < safe.getChessboardDistance(origin)) {
                    safe = candidate;
                }
            }
        }
        return safe;
    }

    static boolean isSafeChunk(int chunkX, int chunkZ, long seed) {
        // Intentionally retain Neverend's narrowing cast before the remainder. It matters for old
        // worlds with a large seed and must agree with the mod's maze generator exactly.
        return (int) (chunkX + seed * 3L) % 13 == 0
            && (int) (chunkZ + seed * 7L) % 27 == 0;
    }
}
