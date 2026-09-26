package me.drex.polymerpatcher.compat.alexscaves;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/** Corrections for block geometry captured from Alex's Caves entity renderers. */
public final class AlexsCavesEntityTransforms {

    private static final Identifier NUCLEAR_BOMB_ENTITY =
        Identifier.fromNamespaceAndPath("alexscaves", "nuclear_bomb");
    private static final Identifier NUCLEAR_BOMB_BLOCK =
        Identifier.fromNamespaceAndPath("alexscaves", "nuclear_bomb");

    private AlexsCavesEntityTransforms() {
    }

    /**
     * Compensates transforms written for the block renderer before using them on an item display.
     *
     * <p>The lit bomb renderer translates raw block geometry by {@code (-.5, 0, -.5)} so its 0..1
     * coordinates are centred on the entity. An item display centres a block model once more by
     * {@code (-.5, -.5, -.5)}. Without undoing that second operation, the bomb is half a block too
     * low and shifted on both horizontal axes. Appending the inverse here preserves every animated
     * rotation and scale already present in the source renderer's matrix.</p>
     */
    public static void adjustCapturedBlock(Entity entity, BlockState state, Matrix4f transformation) {
        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (NUCLEAR_BOMB_ENTITY.equals(entityId) && NUCLEAR_BOMB_BLOCK.equals(blockId)) {
            transformation.translate(0.5F, 0.5F, 0.5F);
        }
    }
}
