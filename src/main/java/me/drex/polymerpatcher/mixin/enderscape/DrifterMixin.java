package me.drex.polymerpatcher.mixin.enderscape;

import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.entity.drifter.Drifter;
import net.penumbra.enderscape.registry.block.EnderscapeBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Drifter.class)
public abstract class DrifterMixin extends Animal {
    protected DrifterMixin(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    @ModifyArg(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private ParticleOptions polymerPatcher$useRenderedBlockParticle(ParticleOptions original) {
        return BlockStateModelManager.getParticle(EnderscapeBlocks.DRIFT_JELLY_BLOCK.defaultBlockState());
    }
}
