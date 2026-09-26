package me.drex.polymerpatcher.entity;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Entity-event bytes are interpreted according to the client-side entity class. A virtual item display
 * therefore cannot perform an animal or mob event, so reproduce the visible result on the server.
 */
public final class EntityEventEmulator {
    private EntityEventEmulator() {
    }

    public static boolean emulate(Entity entity, byte status) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        if (entity instanceof Animal && status == 18) {
            for (int i = 0; i < 7; i++) {
                double x = entity.getRandom().nextGaussian() * 0.02;
                double y = entity.getRandom().nextGaussian() * 0.02;
                double z = entity.getRandom().nextGaussian() * 0.02;
                particle(level, ParticleTypes.HEART, entity.getRandomX(1), entity.getRandomY() + 0.5,
                    entity.getRandomZ(1), x, y, z);
            }
            return true;
        }
        if (entity instanceof Mob && status == 20) {
            poof(level, entity);
            return true;
        }

        if (entity instanceof LivingEntity living) {
            switch (status) {
                case 46 -> {
                    for (int i = 0; i < 128; i++) {
                        double progress = (double) i / 127;
                        double dx = (living.getRandom().nextFloat() - 0.5F) * 0.2F;
                        double dy = (living.getRandom().nextFloat() - 0.5F) * 0.2F;
                        double dz = (living.getRandom().nextFloat() - 0.5F) * 0.2F;
                        particle(level, ParticleTypes.PORTAL,
                            Mth.lerp(progress, living.xo, living.getX())
                                + (living.getRandom().nextDouble() - 0.5) * living.getBbWidth() * 2,
                            Mth.lerp(progress, living.yo, living.getY())
                                + living.getRandom().nextDouble() * living.getBbHeight(),
                            Mth.lerp(progress, living.zo, living.getZ())
                                + (living.getRandom().nextDouble() - 0.5) * living.getBbWidth() * 2,
                            dx, dy, dz);
                    }
                    return true;
                }
                case 47 -> { breakEquipment(living, EquipmentSlot.MAINHAND); return true; }
                case 48 -> { breakEquipment(living, EquipmentSlot.OFFHAND); return true; }
                case 49 -> { breakEquipment(living, EquipmentSlot.HEAD); return true; }
                case 50 -> { breakEquipment(living, EquipmentSlot.CHEST); return true; }
                case 51 -> { breakEquipment(living, EquipmentSlot.LEGS); return true; }
                case 52 -> { breakEquipment(living, EquipmentSlot.FEET); return true; }
                case 54 -> { honey(level, entity, 10); return true; }
                case 60 -> { poof(level, entity); return true; }
                case 65 -> { breakEquipment(living, EquipmentSlot.BODY); return true; }
                case 67 -> {
                    Vec3 movement = entity.getDeltaMovement();
                    for (int i = 0; i < 8; i++) {
                        particle(level, ParticleTypes.BUBBLE,
                            entity.getX() + entity.getRandom().triangle(0, 1),
                            entity.getY() + entity.getRandom().triangle(0, 1),
                            entity.getZ() + entity.getRandom().triangle(0, 1),
                            movement.x, movement.y, movement.z);
                    }
                    return true;
                }
                case 68 -> { breakEquipment(living, EquipmentSlot.SADDLE); return true; }
                default -> { }
            }
        }
        if (status == 53) {
            honey(level, entity, 5);
            return true;
        }
        return false;
    }

    private static void poof(ServerLevel level, Entity entity) {
        for (int i = 0; i < 20; i++) {
            double x = entity.getRandom().nextGaussian() * 0.02;
            double y = entity.getRandom().nextGaussian() * 0.02;
            double z = entity.getRandom().nextGaussian() * 0.02;
            particle(level, ParticleTypes.POOF, entity.getRandomX(1) - x * 10,
                entity.getRandomY() - y * 10, entity.getRandomZ(1) - z * 10, x, y, z);
        }
    }

    private static void honey(ServerLevel level, Entity entity, int count) {
        ParticleOptions particle = new BlockParticleOption(ParticleTypes.BLOCK,
            Blocks.HONEY_BLOCK.defaultBlockState());
        for (int i = 0; i < count; i++) {
            particle(level, particle, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0);
        }
    }

    private static void breakEquipment(LivingEntity entity, EquipmentSlot slot) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) {
            return;
        }
        Holder<SoundEvent> breakSound = stack.get(DataComponents.BREAK_SOUND);
        if (breakSound != null && !entity.isSilent()) {
            entity.level().playSound(entity, entity.getX(), entity.getY(), entity.getZ(), breakSound.value(),
                entity.getSoundSource(), 0.8F, 0.8F + entity.getRandom().nextFloat() * 0.4F);
        }
        ItemParticleOption particle = new ItemParticleOption(ParticleTypes.ITEM,
            ItemStackTemplate.fromNonEmptyStack(stack));
        for (int i = 0; i < 5; i++) {
            Vec3 velocity = new Vec3((entity.getRandom().nextFloat() - 0.5) * 0.1,
                entity.getRandom().nextDouble() * 0.1 + 0.1, 0)
                .xRot(-entity.getXRot() * Mth.DEG_TO_RAD)
                .yRot(-entity.getYRot() * Mth.DEG_TO_RAD);
            double down = -entity.getRandom().nextFloat() * 0.6 - 0.3;
            Vec3 position = new Vec3((entity.getRandom().nextFloat() - 0.5) * 0.3, down, 0.6)
                .xRot(-entity.getXRot() * Mth.DEG_TO_RAD)
                .yRot(-entity.getYRot() * Mth.DEG_TO_RAD)
                .add(entity.getX(), entity.getEyeY(), entity.getZ());
            particle((ServerLevel) entity.level(), particle, position.x, position.y, position.z,
                velocity.x, velocity.y + 0.05, velocity.z);
        }
    }

    private static void particle(ServerLevel level, ParticleOptions particle, double x, double y, double z,
                                 double dx, double dy, double dz) {
        level.sendParticles(particle, x, y, z, 0, dx, dy, dz, 1);
    }
}
