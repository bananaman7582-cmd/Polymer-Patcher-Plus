package me.drex.polymerpatcher.mixin.particle;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.ClientParticleReplay;
import me.drex.polymerpatcher.util.ClientParticleBudget;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes particles requested through {@link Level} visible to clients without their owning mod.
 *
 * <p>{@code Level.addParticle} is intentionally empty on a dedicated server. Mod code commonly calls
 * it from shared entity/item logic, relying on the same code running on a modded client to draw the
 * particle locally. A vanilla client never runs that code, so Polymer can translate the particle type
 * perfectly and still never receive a packet to translate.</p>
 *
 * <p>Only non-vanilla types are bridged. Vanilla calls often deliberately use the empty server method
 * as their side check; forwarding those would duplicate ordinary client ambience and create a great
 * deal of needless traffic. Players with the particle's own mod are also skipped because their client
 * already runs the original effect.</p>
 */
@Mixin(Level.class)
public abstract class ServerParticleMixin {

    @Unique private static final ParticleOptions POLYMER_PATCHER_AZURE_MAGNETIC =
        new DustColorTransitionOptions(0x31D9FF, 0x4454FF, 0.72F);
    @Unique private static final ParticleOptions POLYMER_PATCHER_SCARLET_MAGNETIC =
        new DustColorTransitionOptions(0xFF294F, 0xFF752D, 0.72F);

    /** A malformed or overenthusiastic mod must not turn one shared tick into unbounded packets. */
    @Unique
    private static final int POLYMER_PATCHER_MAX_FORWARDED_PER_TICK = 128;
    @Unique
    private long polymer_patcher$particleBudgetTick = Long.MIN_VALUE;
    @Unique
    private int polymer_patcher$particlesForwarded;

    @Inject(
        method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
        at = @At("HEAD")
    )
    private void polymer_patcher$sendModParticle(ParticleOptions particle,
                                                  double x, double y, double z,
                                                  double velocityX, double velocityY, double velocityZ,
                                                  CallbackInfo ci) {
        polymer_patcher$send(particle, false, false, x, y, z, velocityX, velocityY, velocityZ);
    }

    @Inject(
        method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
        at = @At("HEAD")
    )
    private void polymer_patcher$sendModParticle(ParticleOptions particle, boolean force, boolean decreased,
                                                  double x, double y, double z,
                                                  double velocityX, double velocityY, double velocityZ,
                                                  CallbackInfo ci) {
        polymer_patcher$send(particle, force, decreased, x, y, z, velocityX, velocityY, velocityZ);
    }

    @Inject(
        method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
        at = @At("HEAD")
    )
    private void polymer_patcher$sendAlwaysVisibleModParticle(ParticleOptions particle,
                                                               double x, double y, double z,
                                                               double velocityX, double velocityY, double velocityZ,
                                                               CallbackInfo ci) {
        polymer_patcher$send(particle, true, false, x, y, z, velocityX, velocityY, velocityZ);
    }

    @Inject(
        method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V",
        at = @At("HEAD")
    )
    private void polymer_patcher$sendAlwaysVisibleModParticle(ParticleOptions particle, boolean decreased,
                                                               double x, double y, double z,
                                                               double velocityX, double velocityY, double velocityZ,
                                                               CallbackInfo ci) {
        polymer_patcher$send(particle, true, decreased, x, y, z, velocityX, velocityY, velocityZ);
    }

    /**
     * Inside {@link ClientParticleReplay#run} the level says it is a client, so a mod's own drawing code
     * takes the branch it would take on one. Everywhere else, and on every other thread, it is untouched.
     */
    @ModifyReturnValue(method = "isClientSide", at = @At("RETURN"))
    private boolean polymer_patcher$clientForReplay(boolean original) {
        return original || (ClientParticleReplay.faking != null && ClientParticleReplay.fakesClient((Level) (Object) this));
    }

    /**
     * A sound played "locally" is one only a client plays; on a server the call goes nowhere. Mods make it
     * from client code - which is replayed here - and also from shared code that runs on both sides, where
     * the server's half is simply lost: Sculk Horde's spikes and spell circles are silent to everybody
     * without the mod for that reason. Either way it is played to the players whose clients will not.
     */
    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"))
    private void polymer_patcher$playLocalSound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                                                 net.minecraft.sounds.SoundSource source, float volume, float pitch,
                                                 boolean distanceDelay, CallbackInfo ci) {
        polymer_patcher$sound(x, y, z, sound, source, volume, pitch);
    }

    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"))
    private void polymer_patcher$playLocalSound(net.minecraft.world.entity.Entity entity, net.minecraft.sounds.SoundEvent sound,
                                                 net.minecraft.sounds.SoundSource source, float volume, float pitch,
                                                 CallbackInfo ci) {
        polymer_patcher$sound(entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch);
    }

    @Unique
    private void polymer_patcher$sound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                                       net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        if (!((Object) this instanceof ServerLevel level) || sound == null) {
            return;
        }
        try {
            java.util.List<ServerPlayer> replayed = ClientParticleReplay.viewers(level);
            if (replayed != null) {
                ClientParticleReplay.playTo(replayed, x, y, z, sound, source, volume, pitch);
                return;
            }
            // Made on the server by a mod's shared code. The game's own never relies on this
            Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                    .filter(type -> !Level.class.isAssignableFrom(type))
                    .findFirst().orElse(null));
            if (caller == null || caller.getName().startsWith("net.minecraft.")) {
                return;
            }
            String mod = ClientParticleReplay.MOD_OF.get(caller);
            if (mod.isEmpty() || mod.equals(PolymerPatcher.MOD_ID)) {
                return;
            }
            java.util.List<ServerPlayer> to = new java.util.ArrayList<>();
            for (ServerPlayer player : level.players()) {
                if (!NativeClients.has(player, mod)) {
                    to.add(player);
                }
            }
            ClientParticleReplay.playTo(to, x, y, z, sound, source, volume, pitch);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not play a client-only sound for players without its mod", e);
        }
    }

    @Unique
    private void polymer_patcher$send(ParticleOptions particle, boolean force, boolean decreased,
                                      double x, double y, double z,
                                      double velocityX, double velocityY, double velocityZ) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }

        Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
        if (id == null) {
            return;
        }

        // Client drawing code being replayed: everything it adds is meant for exactly these players,
        // the game's own particles included, since nobody else is going to draw them
        java.util.List<ServerPlayer> replayed = ClientParticleReplay.viewers(level);
        if (replayed != null) {
            try {
                for (ServerPlayer viewer : replayed) {
                    if (!ClientParticleBudget.allow(viewer, level, x, y, z)
                        || polymer_patcher$magneticCrack(level, viewer, id, force, decreased,
                        x, y, z, velocityX, velocityY, velocityZ)) {
                        continue;
                    }
                    level.sendParticles(viewer, particle, force, decreased, x, y, z,
                        0, velocityX, velocityY, velocityZ, 1.0D);
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not forward replayed particle {}", id, e);
            }
            return;
        }

        if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return;
        }

        long tick = level.getGameTime();
        if (polymer_patcher$particleBudgetTick != tick) {
            polymer_patcher$particleBudgetTick = tick;
            polymer_patcher$particlesForwarded = 0;
        }
        if (polymer_patcher$particlesForwarded++ >= POLYMER_PATCHER_MAX_FORWARDED_PER_TICK) {
            return;
        }

        try {
            for (ServerPlayer viewer : level.players()) {
                if (!NativeClients.carries(viewer, id.getNamespace())
                    && ClientParticleBudget.allow(viewer, level, x, y, z)) {
                    if (polymer_patcher$magneticCrack(level, viewer, id, force, decreased,
                        x, y, z, velocityX, velocityY, velocityZ)) {
                        continue;
                    }
                    // A count of zero is the packet form for one particle with an exact velocity;
                    // count one would reinterpret these values as random position spread.
                    level.sendParticles(viewer, particle, force, decreased, x, y, z,
                        0, velocityX, velocityY, velocityZ, 1.0D);
                }
            }
        } catch (Throwable e) {
            // A broken third-party ParticleOptions implementation must not be allowed to interrupt
            // the entity or item tick that merely tried to show it.
            PolymerPatcher.LOGGER.debug("Could not forward modded particle {}", id, e);
        }
    }

    /**
     * Alex's magnetic particle encodes its destination in the three values vanilla calls velocity.
     * Sending that as ordinary dust produced one speck flying at world-coordinate speed. Reconstruct
     * the coloured, crooked bolt between the two points with a short bounded chain of vanilla dust.
     */
    @Unique
    private static boolean polymer_patcher$magneticCrack(ServerLevel level, ServerPlayer viewer,
                                                          Identifier id, boolean force, boolean decreased,
                                                          double x, double y, double z,
                                                          double targetX, double targetY, double targetZ) {
        if (!id.getNamespace().equals("alexscaves")) {
            return false;
        }
        String path = id.getPath();
        boolean azure = path.startsWith("azure_");
        boolean scarlet = path.startsWith("scarlet_");
        if ((!azure && !scarlet) || !(path.endsWith("magnetic_flow")
            || path.endsWith("magnetic_orbit") || path.endsWith("shield_lightning"))) {
            return false;
        }

        Vec3 start = new Vec3(x, y, z);
        Vec3 end = new Vec3(targetX, targetY, targetZ);
        Vec3 delta = end.subtract(start);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance < 0.02 || distance > 24.0) {
            return false;
        }
        Vec3 direction = delta.scale(1.0 / distance);
        Vec3 side = direction.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-4) {
            side = direction.cross(new Vec3(1, 0, 0));
        }
        side = side.normalize();
        Vec3 up = direction.cross(side).normalize();
        int segments = Math.clamp((int) Math.ceil(distance * 4.0), 4, 11);
        ParticleOptions colour = azure ? POLYMER_PATCHER_AZURE_MAGNETIC : POLYMER_PATCHER_SCARLET_MAGNETIC;
        double seed = x * 11.73 + y * 37.19 + z * 5.41 + level.getGameTime() * 0.37;
        for (int part = 0; part <= segments; part++) {
            // The caller already reserved the first packet. Every further point must reserve its own,
            // otherwise one reconstructed bolt could silently exceed the shared per-client budget.
            if (part > 0 && !ClientParticleBudget.allow(viewer, level, x, y, z)) {
                break;
            }
            double progress = part / (double) segments;
            double taper = Math.sin(progress * Math.PI);
            double jagA = Math.sin(seed + part * 2.31) * 0.10 * taper;
            double jagB = Math.cos(seed * 0.71 + part * 3.17) * 0.07 * taper;
            Vec3 point = start.add(delta.scale(progress)).add(side.scale(jagA)).add(up.scale(jagB));
            level.sendParticles(viewer, colour, force, decreased, point.x, point.y, point.z,
                0, 0, 0, 0, 1.0D);
        }
        return true;
    }
}
