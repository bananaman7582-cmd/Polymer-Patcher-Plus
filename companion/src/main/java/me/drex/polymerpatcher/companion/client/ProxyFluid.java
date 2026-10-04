package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * This client's copy of one of the server's modded fluids.
 * <p>
 * The client never makes a fluid flow - the server does, and sends the result - so all this has to do
 * is be the right fluid to draw, and give the right depth and flow direction for the player to be
 * pushed about by.
 */
public abstract class ProxyFluid extends FlowingFluid {

    /** The pair a fluid is made of, and the block that holds it. Filled in as each is made. */
    static final class Family {
        final CompanionManifest.FluidEntry entry;
        Source source;
        Flowing flowing;
        LiquidBlock block;

        Family(CompanionManifest.FluidEntry entry) {
            this.entry = entry;
        }
    }

    final Family family;

    private ProxyFluid(Family family) {
        this.family = family;
    }

    /** Whether a player is given water movement in this fluid, as the server gives everybody. */
    public boolean swimsLikeWater() {
        return (family.entry.flags() & CompanionManifest.FLUID_WATER_PHYSICS) != 0;
    }

    @Override
    public Fluid getFlowing() {
        return family.flowing;
    }

    @Override
    public Fluid getSource() {
        return family.source;
    }

    @Override
    public Item getBucket() {
        return Items.AIR;
    }

    @Override
    protected boolean canConvertToSource(ServerLevel level) {
        return (family.entry.flags() & CompanionManifest.FLUID_CONVERTS_TO_SOURCE) != 0;
    }

    @Override
    protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
    }

    @Override
    protected int getSlopeFindDistance(LevelReader level) {
        return family.entry.slopeFindDistance();
    }

    @Override
    protected int getDropOff(LevelReader level) {
        return family.entry.dropOff();
    }

    @Override
    public int getTickDelay(LevelReader level) {
        return family.entry.tickDelay();
    }

    @Override
    protected float getExplosionResistance() {
        return family.entry.explosionResistance();
    }

    @Override
    protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) {
        return direction == Direction.DOWN && !isSame(fluid);
    }

    @Override
    protected BlockState createLegacyBlock(FluidState state) {
        return family.block.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state));
    }

    @Override
    public boolean isSame(Fluid fluid) {
        return fluid == family.source || fluid == family.flowing;
    }

    public static final class Source extends ProxyFluid {
        Source(Family family) {
            super(family);
        }

        @Override
        public int getAmount(FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(FluidState state) {
            return true;
        }
    }

    public static final class Flowing extends ProxyFluid {
        Flowing(Family family) {
            super(family);
        }

        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(FluidState state) {
            return state.getValue(LEVEL);
        }

        @Override
        public boolean isSource(FluidState state) {
            return false;
        }
    }
}
