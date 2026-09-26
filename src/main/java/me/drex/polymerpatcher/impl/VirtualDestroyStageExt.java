package me.drex.polymerpatcher.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Internal bridge added to FactoryTools' per-player destroy-stage holder. */
public interface VirtualDestroyStageExt {
    void polymer_patcher$setBlockShape(ServerPlayer player, BlockPos pos, BlockState state, int stage);
}
