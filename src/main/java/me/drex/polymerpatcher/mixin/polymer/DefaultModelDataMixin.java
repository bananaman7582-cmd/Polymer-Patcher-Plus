package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.blocks.impl.DefaultModelData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

@Mixin(DefaultModelData.class)
public abstract class DefaultModelDataMixin {
    @ModifyArg(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Leu/pb4/polymer/blocks/impl/DefaultModelData;generateDefault(Leu/pb4/polymer/blocks/api/BlockModelType;Ljava/util/function/Predicate;[Lnet/minecraft/world/level/block/Block;)V",
            ordinal = 0
        ),
        index = 2
    )
    private static Block[] noSpruceLeaves(Block[] blocks) {
        return Arrays.stream(blocks).filter(block -> block != Blocks.SPRUCE_LEAVES).toArray(Block[]::new);
    }
}
