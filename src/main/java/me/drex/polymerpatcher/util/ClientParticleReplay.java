package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Runs a mod's own client-side particle code on the server, for the players whose clients never will.
 * <p>
 * A great deal of what a mod looks like is drawn by code that only runs on a client: a block's
 * {@code animateTick}, the branch of an entity's tick behind {@code level().isClientSide()}, an effect
 * that puffs spores around whoever has it. A player without the mod has none of that code, and a player
 * shown a stand-in for a mob has no real mob on their side for it to run on - so the particles that
 * made the thing recognisable simply never happen for them. Sculk Horde is the clearest case: the
 * horde's spread is watched almost entirely through the souls its cursors trail as they crawl.
 * <p>
 * Inside {@link #run} the level answers {@code isClientSide()} with true, so the mod's own code takes
 * its client branch, and every particle it adds - which on a server would go nowhere - is sent to the
 * players named instead (see {@code ServerParticleMixin}). Nothing is reimplemented where the mod's own
 * method can be called; where the effect sits in the middle of a method that also does real work, the
 * caller writes the few lines out itself and runs them here.
 * <p>
 * Everything sent this way goes through {@link ClientParticleBudget}, like the other effects that are
 * replayed for vanilla clients.
 */
public final class ClientParticleReplay {

    private ClientParticleReplay() {
    }

    /**
     * The level currently pretending to be a client, read on every {@code isClientSide()} call in the
     * game - so it is a plain static that is almost always null, and only compared against when set.
     */
    @Nullable
    public static volatile Level faking;
    @Nullable
    private static Thread fakingThread;
    @Nullable
    private static List<ServerPlayer> viewers;

    /** Whether this level should answer that it is a client, which is only ever inside {@link #run}. */
    public static boolean fakesClient(Level level) {
        return faking == level && fakingThread == Thread.currentThread();
    }

    /** The players particles added right now are meant for, or null outside a replay. */
    @Nullable
    public static List<ServerPlayer> viewers(Level level) {
        return fakesClient(level) ? viewers : null;
    }

    /**
     * Runs a piece of a mod's client-side drawing code with the level acting as a client, sending what
     * it draws to these players only. Anything it throws costs this one call and is otherwise ignored:
     * code written for a client is allowed to reach for things a server does not have.
     *
     * @return false if the code threw, so a caller can stop asking the same code to run
     */
    public static boolean run(ServerLevel level, List<ServerPlayer> to, Runnable clientCode) {
        if (to.isEmpty() || faking != null) {
            return true;
        }
        faking = level;
        fakingThread = Thread.currentThread();
        viewers = to;
        try {
            clientCode.run();
            return true;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Client-side effect code failed while being replayed on the server", e);
            return false;
        } finally {
            viewers = null;
            fakingThread = null;
            faking = null;
        }
    }

    /**
     * As {@link #run}, but whatever the code throws is handed on - for a caller whose own error handling
     * depends on seeing it. Inside another replay the code simply runs as part of that one.
     */
    public static void runRethrowing(ServerLevel level, List<ServerPlayer> to, Runnable clientCode) {
        if (to.isEmpty() || faking != null) {
            clientCode.run();
            return;
        }
        faking = level;
        fakingThread = Thread.currentThread();
        viewers = to;
        try {
            clientCode.run();
        } finally {
            viewers = null;
            fakingThread = null;
            faking = null;
        }
    }

    /**
     * Plays a sound a mod meant only for the client, to these players - where it would have been heard on
     * a client, which is from where it is and no further than its volume carries.
     */
    public static void playTo(List<ServerPlayer> to, double x, double y, double z,
                              net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source,
                              float volume, float pitch) {
        double range = sound.getRange(volume);
        var packet = new net.minecraft.network.protocol.game.ClientboundSoundPacket(
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, x, y, z, volume, pitch,
            java.util.concurrent.ThreadLocalRandom.current().nextLong());
        for (ServerPlayer player : to) {
            if (player.distanceToSqr(x, y, z) <= range * range) {
                player.connection.send(packet);
            }
        }
    }

    /**
     * The mod a class belongs to, found from where it was loaded; empty where it is nobody's. Used to
     * tell which mod made a call that, on a server, goes nowhere.
     */
    public static final ClassValue<String> MOD_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                var source = type.getProtectionDomain().getCodeSource();
                if (source == null) {
                    return "";
                }
                java.nio.file.Path where = java.nio.file.Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                    for (java.nio.file.Path path : mod.getOrigin().getPaths()) {
                        if (path.toAbsolutePath().normalize().equals(where)) {
                            return mod.getMetadata().getId();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return "";
        }
    };

    /**
     * The players watching this entity who were sent a stand-in for it rather than the real thing, and
     * therefore run none of its client code. A player is included when the entity is themselves.
     */
    public static List<ServerPlayer> shownStandInFor(Entity entity, String namespace) {
        List<ServerPlayer> found = new ArrayList<>();
        if (!(entity.level() instanceof ServerLevel)) {
            return found;
        }
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            if (!NativeClients.has(player, namespace)) {
                found.add(player);
            }
        }
        return found;
    }

    /**
     * The players who can see this entity and lack the mod, so have no code of their own to draw what
     * the mod draws around it. A player is included when the entity is themselves.
     */
    public static List<ServerPlayer> lackingModAround(Entity entity, String namespace) {
        List<ServerPlayer> found = new ArrayList<>();
        if (!(entity.level() instanceof ServerLevel)) {
            return found;
        }
        if (entity instanceof ServerPlayer self && !NativeClients.carries(self, namespace)) {
            found.add(self);
        }
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            if (player != entity && !NativeClients.carries(player, namespace)) {
                found.add(player);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------------------------
    // Effects whose look is drawn by the client
    // ---------------------------------------------------------------------------------------------

    /**
     * Effects that draw themselves on the client each time they tick, and how to draw them. Filled in
     * by compatibility code for the mods that have such effects; empty otherwise, which is what keeps
     * the hook in every living entity's tick free.
     */
    private static final List<EffectDrawing> EFFECT_DRAWINGS = new ArrayList<>();
    private static final Set<Class<?>> FAILED_EFFECTS = ConcurrentHashMap.newKeySet();

    private record EffectDrawing(Class<?> type, BiConsumer<LivingEntity, MobEffectInstance> draw) {
    }

    public static void registerEffectDrawing(Class<?> effectType, BiConsumer<LivingEntity, MobEffectInstance> draw) {
        EFFECT_DRAWINGS.add(new EffectDrawing(effectType, draw));
    }

    /** Called from a living entity's effect tick on the server. */
    public static void tickEffects(LivingEntity entity) {
        if (EFFECT_DRAWINGS.isEmpty() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        var effects = entity.getActiveEffects();
        if (effects.isEmpty()) {
            return;
        }

        for (MobEffectInstance instance : List.copyOf(effects)) {
            Holder<MobEffect> holder = instance.getEffect();
            MobEffect effect = holder.value();
            if (FAILED_EFFECTS.contains(effect.getClass())) {
                continue;
            }
            for (EffectDrawing drawing : EFFECT_DRAWINGS) {
                if (!drawing.type().isInstance(effect)
                    || !effect.shouldApplyEffectTickThisTick(instance.getDuration(), instance.getAmplifier())) {
                    continue;
                }
                Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
                List<ServerPlayer> to = lackingModAround(entity, id == null ? "" : id.getNamespace());
                if (!run(level, to, () -> drawing.draw().accept(entity, instance))) {
                    FAILED_EFFECTS.add(effect.getClass());
                    PolymerPatcher.LOGGER.warn("Could not draw {} for players without its mod; it will not be tried again", id);
                }
            }
        }
    }
}
