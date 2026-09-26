package me.drex.polymerpatcher.mixin.polymer.light;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.drex.polymerpatcher.util.PolymerLightUpdateHelper;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.BitSet;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public class ClientboundLevelChunkWithLightPacketMixin {
    @WrapOperation(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)Lnet/minecraft/network/protocol/game/ClientboundLightUpdatePacketData;"
        )
    )
    private ClientboundLightUpdatePacketData addPolymerLightContext(
        ChunkPos chunkPos, LevelLightEngine lightEngine, @Nullable BitSet skyChangedLightSectionFilter,
        @Nullable BitSet blockChangedLightSectionFilter, Operation<ClientboundLightUpdatePacketData> operation,
        LevelChunk chunk) {
        return PolymerLightUpdateHelper.runWithLevel(chunk.getLevel(), () -> operation.call(chunkPos, lightEngine, skyChangedLightSectionFilter, blockChangedLightSectionFilter));
    }
}
