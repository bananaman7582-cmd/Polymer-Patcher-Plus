package me.drex.polymerpatcher.block;

import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/** Semantic invariants that every client-visible vanilla carrier must preserve. */
public final class CarrierStateSafety {
    private CarrierStateSafety() {
    }

    /** True unless the client carrier would introduce water that the real block does not contain. */
    public static boolean isWaterSafeCarrier(BlockState original, @Nullable BlockState carrier) {
        if (carrier == null) {
            return true;
        }
        boolean sourceHoldsWater = original.getValueOrElse(BlockStateProperties.WATERLOGGED, false)
            || holdsWater(original);
        boolean carrierHoldsWater = carrier.getValueOrElse(BlockStateProperties.WATERLOGGED, false)
            || holdsWater(carrier);
        return sourceHoldsWater || !carrierHoldsWater;
    }

    /**
     * Water by the fluid itself rather than the water tag: this runs while blocks are being registered,
     * before any tag is loaded, and asking a tag then throws for every block
     */
    private static boolean holdsWater(BlockState state) {
        var fluid = state.getFluidState().getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }
}
