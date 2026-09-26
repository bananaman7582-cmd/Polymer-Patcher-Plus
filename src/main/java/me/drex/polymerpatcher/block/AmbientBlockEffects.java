package me.drex.polymerpatcher.block;

import me.drex.polymerpatcher.mixin.block.TorchBlockAccessor;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Server-side replacements for vanilla client effects normally spawned by a mod's client code. */
final class AmbientBlockEffects {
    private AmbientBlockEffects() {
    }

    static boolean supports(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return !id.getNamespace().equals("minecraft")
            && (state.getBlock() instanceof TorchBlock || state.getBlock() instanceof CampfireBlock);
    }

    static void tick(BlockStateModel model, BlockState state, BlockPos pos, RandomSource random) {
        if (model.getAttachment() == null) {
            return;
        }
        if (!supports(state)) {
            return;
        }

        if (state.getBlock() instanceof CampfireBlock) {
            tickCampfire(model, state, pos, random);
        } else if (state.getBlock() instanceof TorchBlock torch) {
            tickTorch(model, state, pos, torch, random);
        }
    }

    private static void tickTorch(BlockStateModel model, BlockState state, BlockPos pos, TorchBlock torch,
                                  RandomSource random) {
        if (random.nextInt(16) != 0) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.7;
        double z = pos.getZ() + 0.5;
        if (state.getBlock() instanceof WallTorchBlock) {
            Direction awayFromWall = state.getValue(WallTorchBlock.FACING).getOpposite();
            x += 0.27 * awayFromWall.getStepX();
            y += 0.22;
            z += 0.27 * awayFromWall.getStepZ();
        }

        sendParticle(model, ParticleTypes.SMOKE, false, x, y, z, 0, 0, 0);
        ParticleOptions flame = ((TorchBlockAccessor) torch).getFlameParticle();
        sendParticle(model, flame, false, x, y, z, 0, 0, 0);
    }

    private static void tickCampfire(BlockStateModel model, BlockState state, BlockPos pos, RandomSource random) {
        if (!state.getValue(CampfireBlock.LIT)) {
            return;
        }
        if (random.nextFloat() < 0.11F) {
            ParticleOptions smoke = state.getValue(CampfireBlock.SIGNAL_FIRE)
                ? ParticleTypes.CAMPFIRE_SIGNAL_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE;
            for (int i = 0; i < random.nextInt(2) + 2; i++) {
                sendParticle(model, smoke, true,
                    pos.getX() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1),
                    pos.getY() + random.nextDouble() + random.nextDouble(),
                    pos.getZ() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1),
                    0, 0.07, 0);
            }
        }
        if (random.nextInt(64) == 0) {
            if (random.nextInt(10) == 0) {
                model.getAttachment().getWorld().playSound(null, pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS,
                    0.5F + random.nextFloat(), random.nextFloat() * 0.7F + 0.6F);
            }
            if (random.nextInt(5) == 0) {
                sendParticle(model, ParticleTypes.LAVA, false, pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5, random.nextFloat() / 2, 0.00005, random.nextFloat() / 2);
            }
        }
    }

    private static void sendParticle(BlockStateModel model, ParticleOptions particle, boolean alwaysVisible,
                                     double x, double y, double z, double dx, double dy, double dz) {
        model.getAttachment().getWorld().sendParticles(particle, false, alwaysVisible,
            x, y, z, 0, dx, dy, dz, 1);
    }
}
