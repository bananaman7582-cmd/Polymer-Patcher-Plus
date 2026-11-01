package me.drex.polymerpatcher.block;

import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Material facts proven by a slab's double-state model, never by its item/block name. */
public final class SlabMaterials {
    private static final Map<Block, BlockState> MATERIALS = new IdentityHashMap<>();
    private SlabMaterials() {}

    public static void discover(Block block, List<Pair<BlockStatePredicate, List<StateModelVariant>>> variants) {
        if (!(block instanceof SlabBlock)) return;
        BlockState doubled = block.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        BlockState material = null;
        for (var entry : variants) {
            if (!entry.getFirst().test(doubled)) continue;
            if (entry.getSecond().size() != 1) return;
            var model = entry.getSecond().getFirst();
            if (model.x() != 0 || model.y() != 0) return;
            Identifier id = materialId(model.model());
            if (id == null || !RegistryPatcher.isVanillaBlock(id)) return;
            BlockState candidate = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
            if (material != null && material != candidate) return;
            material = candidate;
        }
        if (material != null) MATERIALS.put(block, material);
    }

    /** Only direct vanilla block models count; arbitrary models with similar textures do not. */
    public static Identifier materialId(Identifier model) {
        if (!model.getNamespace().equals("minecraft") || !model.getPath().startsWith("block/")) return null;
        return Identifier.fromNamespaceAndPath(model.getNamespace(), model.getPath().substring(6));
    }

    public static BlockState doubleCarrier(BlockState slab) {
        BlockState material = MATERIALS.get(slab.getBlock());
        if (material == null || slab.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
            || !CarrierStateSafety.isWaterSafeCarrier(slab, material)
            || slab.getLightEmission() != material.getLightEmission()) return null;
        var world = eu.pb4.polymer.common.api.PolymerCommonUtils.getFakeWorld();
        return ShapeKey.of(slab.getCollisionShape(world, net.minecraft.core.BlockPos.ZERO)).equals(
            ShapeKey.of(material.getCollisionShape(world, net.minecraft.core.BlockPos.ZERO))) ? material : null;
    }

    /** Repair only absent tool classification. Explicit mod mining tags always win. */
    public static BlockState miningState(BlockState state) {
        BlockState material = MATERIALS.get(state.getBlock());
        return material != null && !hasToolTag(state) && hasToolTag(material) ? material : state;
    }

    private static boolean hasToolTag(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.is(BlockTags.MINEABLE_WITH_AXE)
            || state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.MINEABLE_WITH_HOE);
    }
}
