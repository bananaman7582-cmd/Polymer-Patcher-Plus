package me.drex.polymerpatcher.dump.mixin;

import me.drex.polymerpatcher.dump.PolymerPatcherDumper;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeColors.class)
public abstract class BiomeColorsMixin {
    @Inject(method = "getAverageColor", at = @At("HEAD"))
    private static void captureBiomeColor(BlockAndTintGetter blockAndTintGetter, BlockPos blockPos, ColorResolver colorResolver, CallbackInfoReturnable<Integer> cir) {
        PolymerPatcherDumper.COLOR_RESOLVER.set(colorResolver);
    }
}
