package me.drex.polymerpatcher.compat.enderscape;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.penumbra.enderscape.network.ClientboundDashJumpPayload;
import net.penumbra.enderscape.network.ClientboundDashJumpSoundPayload;
import net.penumbra.enderscape.network.ClientboundNebuliteOreSoundPayload;
import net.penumbra.enderscape.network.ClientboundRubbleShieldCooldownSoundPayload;
import net.penumbra.enderscape.registry.sound.EnderscapeBlockSounds;
import net.penumbra.enderscape.registry.sound.EnderscapeUiSounds;
import net.penumbra.enderscape.util.BlockUtil;

/** Replays the useful effects of Enderscape client payloads using vanilla packets. */
public final class EnderscapePacketHandler {
    public static final net.minecraft.resources.Identifier END_HAVEN_RESPAWN_ACTION =
        PolymerPatcher.id("enderscape/end_haven_respawn");
    public static final net.minecraft.resources.Identifier RESPAWN_ACTION =
        PolymerPatcher.id("enderscape/respawn");
    public static final net.minecraft.resources.Identifier DISCONNECT_ACTION =
        PolymerPatcher.id("enderscape/disconnect");

    private EnderscapePacketHandler() {
    }

    public static void handle(ServerPlayer player, CustomPacketPayload rawPayload) {
        if (rawPayload instanceof ClientboundDashJumpPayload payload) {
            applyDashJump(player, payload);
        } else if (rawPayload instanceof ClientboundDashJumpSoundPayload payload) {
            Entity entity = player.level().getEntity(payload.entityId());
            if (entity != null && entity.isAlive() && !entity.isSpectator()) {
                var sound = player.registryAccess().lookupOrThrow(Registries.SOUND_EVENT)
                    .getOrThrow(ResourceKey.create(Registries.SOUND_EVENT, payload.soundEvent()));
                player.connection.send(new ClientboundSoundEntityPacket(
                    sound, entity.getSoundSource(), entity, 1, 1, entity.getRandom().nextLong()));
            }
        } else if (rawPayload instanceof ClientboundNebuliteOreSoundPayload payload) {
            playNebuliteSound(player, payload);
        } else if (rawPayload instanceof ClientboundRubbleShieldCooldownSoundPayload) {
            player.connection.send(new ClientboundSoundEntityPacket(
                EnderscapeUiSounds.RUBBLE_SHIELD_COOLDOWN_OVER, SoundSource.MASTER, player, 1, 1, 0));
        }
    }

    private static void applyDashJump(ServerPlayer player, ClientboundDashJumpPayload payload) {
        if (!player.isAlive() || player.isSpectator()) return;

        Vec2 input = applyMovementSpeedFactors(getMovementInput(player.getLastClientInput()), player);
        Vec3 travel = new Vec3(input.x, 0, input.y).normalize();
        Vec2 power = payload.power();
        float sin = Mth.sin((float) (player.getYRot() * Math.PI / 180));
        float cos = Mth.cos((float) (player.getYRot() * Math.PI / 180));

        player.setDeltaMovement(new Vec3(
            travel.x * power.x * cos - travel.z * power.x * sin,
            power.y,
            travel.z * power.x * cos + travel.x * power.x * sin));
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    private static void playNebuliteSound(ServerPlayer player, ClientboundNebuliteOreSoundPayload payload) {
        BlockPos nebulite = payload.globalPos().pos();
        ResourceKey<Level> dimension = payload.globalPos().dimension();
        Level level = player.level();
        Entity camera = player.getCamera();
        if (level.dimension() != dimension || !(camera instanceof LivingEntity living)) return;

        SoundEvent sound;
        if (BlockUtil.isBlockObstructed(level, nebulite)) {
            sound = EnderscapeBlockSounds.NEBULITE_ORE_IDLE_OBSTRUCTED;
        } else if (living.blockPosition().closerThan(nebulite, 12.0)) {
            sound = EnderscapeBlockSounds.NEBULITE_ORE_IDLE;
        } else {
            sound = EnderscapeBlockSounds.NEBULITE_ORE_IDLE_FAR;
        }

        float range = Mth.clamp((float) (nebulite.getY() - living.getY()), -8, 0) / 20
            + Mth.nextFloat(level.getRandom(), 0.9F, 1.1F);
        player.connection.send(new ClientboundSoundPacket(
            net.minecraft.core.Holder.direct(sound), SoundSource.BLOCKS,
            nebulite.getX(), nebulite.getY(), nebulite.getZ(), range, range, 0));
    }

    private static Vec2 getMovementInput(Input input) {
        float forward = movementMultiplier(input.forward(), input.backward());
        float sideways = movementMultiplier(input.left(), input.right());
        return new Vec2(sideways, forward).normalized();
    }

    private static float movementMultiplier(boolean positive, boolean negative) {
        return positive == negative ? 0 : positive ? 1 : -1;
    }

    private static Vec2 applyMovementSpeedFactors(Vec2 input, ServerPlayer player) {
        if (input.lengthSquared() == 0) return input;

        Vec2 adjusted = input.scale(0.98F);
        if (player.isUsingItem() && !player.isPassenger()) {
            adjusted = adjusted.scale(0.2F);
        }
        if (player.isCrouching() || player.isVisuallyCrawling()) {
            adjusted = adjusted.scale((float) player.getAttributeValue(Attributes.SNEAKING_SPEED));
        }

        float length = adjusted.length();
        if (length <= 0) return adjusted;
        Vec2 normalized = adjusted.scale(1 / length);
        float x = Math.abs(normalized.x);
        float y = Math.abs(normalized.y);
        float ratio = y > x ? x / y : y / x;
        return normalized.scale(Math.min(length * Mth.sqrt(1 + Mth.square(ratio)), 1));
    }
}
