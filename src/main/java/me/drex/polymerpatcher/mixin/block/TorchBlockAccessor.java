package me.drex.polymerpatcher.mixin.block;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.block.TorchBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TorchBlock.class)
public interface TorchBlockAccessor {
    @Accessor
    SimpleParticleType getFlameParticle();
}
