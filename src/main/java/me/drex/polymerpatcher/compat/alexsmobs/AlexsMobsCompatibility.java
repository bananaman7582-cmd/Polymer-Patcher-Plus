package me.drex.polymerpatcher.compat.alexsmobs;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.entity.armor.ArmorRenderHooks;
import me.drex.polymerpatcher.entity.armor.ArmorModels;
import me.drex.polymerpatcher.entity.armor.ConventionalArmorModels;
import me.drex.polymerpatcher.entity.citadel.CitadelModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModelInstance;
import me.drex.polymerpatcher.entity.citadel.CitadelModels;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Alex's Mobs-only bridges which have no sensible meaning for other mods. */
public final class AlexsMobsCompatibility {
    private static final String MOD_ID = "alexsmobs";
    private static final Identifier ROCKY_CHESTPLATE = Identifier.fromNamespaceAndPath(MOD_ID, "rocky_chestplate");
    private static final Identifier ROCKY_TEXTURE = Identifier.fromNamespaceAndPath(MOD_ID, "armor/rocky_chestplate");
    private static final String ROLLING_MODEL = "com.github.alexthe666.alexsmobs.client.model.ModelRockyChestplateRolling";
    private static final String ROLLING_UTILITY = "com.github.alexthe666.alexsmobs.entity.util.RockyChestplateUtil";

    private static final Map<UUID, Attached> ATTACHED = new HashMap<>();
    private static Method isRolling;
    private static Method startRolling;
    private static Method getRollingTicks;
    private static Method tickRolling;
    private static CitadelModelInstance<?, ?, ?> rollingModel;
    private static boolean active;
    /** Fully local gameplay fallback used only if Alex's own state cannot be started. */
    private static final Map<UUID, Integer> STANDALONE_ROLLS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_ROLL_START = new HashMap<>();
    private static final Set<UUID> LOGGED_NATIVE_START = new HashSet<>();
    private static final Set<UUID> LOGGED_STANDALONE_START = new HashSet<>();
    private static final Map<UUID, ManagedNativeRoll> MANAGED_NATIVE_ROLLS = new HashMap<>();
    private static boolean loggedStateFailure;
    private static boolean armorFallbackRegistered;

    private record Attached(RockyRollModel holder, EntityAttachment attachment, ServerPlayer player) {
    }

    private static final class ManagedNativeRoll {
        private int lastTicks;
        private boolean nativeHookAdvanced;

        private ManagedNativeRoll(int lastTicks) {
            this.lastTicks = lastTicks;
        }
    }

    private AlexsMobsCompatibility() {
    }

    public static void init() {
        active = FabricLoader.getInstance().isModLoaded(MOD_ID);
        if (!active) {
            return;
        }

        AlexsMobsCarver.init();

        try {
            Class<?> utility = Class.forName(ROLLING_UTILITY);
            isRolling = utility.getMethod("isRockyRolling", LivingEntity.class);
            startRolling = utility.getMethod("rollFor", LivingEntity.class, int.class);
            getRollingTicks = utility.getMethod("getRollingTicksLeft", LivingEntity.class);
            tickRolling = utility.getMethod("tickRockyRolling", LivingEntity.class);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Alex's Mobs is installed, but its Rocky Chestplate state could not be read", e);
        }

        ArmorRenderHooks.suppressWhen((player, stack) -> isRockyChestplate(stack) && isAnyRolling(player));
        ServerTickEvents.END_SERVER_TICK.register(AlexsMobsCompatibility::reconcile);
    }

    /** Called after client-only rendering classes have been made available on the dedicated server. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void setupRendering() {
        if (!active) {
            return;
        }

        AlexsMobsRenderRules.init();

        if (!armorFallbackRegistered) {
            armorFallbackRegistered = true;
            ConventionalArmorModels.registerSource(new ConventionalArmorModels.Source(
                MOD_ID, "com.github.alexthe666.alexsmobs.client.model.layered.",
                List.of("Model", "ModelAM")));
            ArmorModels.registerFallback("one-class-per-item", sink ->
                ConventionalArmorModels.discover((piece, shape) -> sink.add(
                    piece.item(), piece.layer(), shape.root(), piece.texture(), shape.model())));
            AnimatedEntities.registerBorrowedModel("alexsmobs:tendon_segment",
                "com.github.alexthe666.alexsmobs.client.render.RenderMurmurHead", "NECK_MODEL",
                Identifier.fromNamespaceAndPath("alexsmobs", "entity/murmur"));
            me.drex.polymerpatcher.block.ShowcaseBlocks.register(
                Identifier.fromNamespaceAndPath("alexsmobs", "capsid"),
                new me.drex.polymerpatcher.block.ShowcaseBlocks.Showcase(-0.1F, 0.5F, false));
        }

        if (isRolling == null || rollingModel != null) {
            return;
        }

        try {
            Class<?> modelClass = ClientOnlyClasses.load(ROLLING_MODEL);
            if (modelClass == null) {
                return;
            }

            Object model = null;
            Throwable lastFailure = null;
            // Loading the model class itself does not load the client-only Citadel classes reached by
            // its constructor. Define each refused dependency and retry the fresh constructor, just as
            // the generic renderer loader does. A failed constructor does not poison this model class.
            for (int round = 0; round < ClientOnlyClasses.maxRounds(); round++) {
                try {
                    model = modelClass.getDeclaredConstructor().newInstance();
                    break;
                } catch (Throwable e) {
                    lastFailure = e;
                    if (!ClientOnlyClasses.defineRefused(e)) {
                        throw e;
                    }
                }
            }
            if (model == null) {
                throw new IllegalStateException("Could not construct the rolling chestplate model", lastFailure);
            }
            if (!CitadelModel.isCitadelModel(model)) {
                PolymerPatcher.LOGGER.warn("Alex's Mobs rolling chestplate model is not a readable Citadel model");
                return;
            }

            CitadelModels.Resolved resolved = CitadelModels.resolve(model);
            rollingModel = new CitadelModelInstance(null, resolved.layer(), resolved.roots(), ROCKY_TEXTURE);
            AnimatedEntities.CITADEL_MODELS.add(rollingModel);
            PolymerPatcher.LOGGER.info("Enabled the Alex's Mobs Rocky Chestplate rolling model for vanilla clients");
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not prepare the Alex's Mobs Rocky Chestplate rolling model", e);
        }
    }

    private static void reconcile(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean wearing = isRockyChestplate(player.getItemBySlot(EquipmentSlot.CHEST));
            UUID playerId = player.getUUID();
            boolean nativeRolling = isRolling(player);
            Integer standaloneTicks = STANDALONE_ROLLS.get(playerId);

            // Prefer Alex's real state so modded clients and every Alex-side effect remain native. Its
            // normal living-tick hook failed to enter that state in server-only use, so start it
            // explicitly after the entity tick. If even that bridge is unavailable, reproduce the
            // movement and collision damage locally instead of silently leaving the armour cosmetic.
            if (!wearing) {
                STANDALONE_ROLLS.remove(playerId);
                MANAGED_NATIVE_ROLLS.remove(playerId);
                standaloneTicks = null;
            } else if (standaloneTicks != null) {
                int nextTicks = tickStandaloneRoll(player, standaloneTicks);
                if (nextTicks > 0) {
                    STANDALONE_ROLLS.put(playerId, nextTicks);
                    standaloneTicks = nextTicks;
                } else {
                    STANDALONE_ROLLS.remove(playerId);
                    standaloneTicks = null;
                }
            } else if (nativeRolling && MANAGED_NATIVE_ROLLS.containsKey(playerId)) {
                nativeRolling = maintainNativeRoll(player, playerId);
                if (!nativeRolling) {
                    stopNativeRoll(player);
                    int nextTicks = tickStandaloneRoll(player, 30);
                    STANDALONE_ROLLS.put(playerId, nextTicks);
                    standaloneTicks = nextTicks;
                    if (LOGGED_STANDALONE_START.add(playerId)) {
                        PolymerPatcher.LOGGER.warn("Alex's Mobs entered but did not advance the Rocky Chestplate roll for {}; using Polymer Patcher's gameplay fallback", player.getGameProfile().name());
                    }
                }
            } else if (!nativeRolling && canStartRoll(player)) {
                MANAGED_NATIVE_ROLLS.remove(playerId);
                LAST_ROLL_START.put(playerId, player.tickCount);
                nativeRolling = startNativeRoll(player);
                if (nativeRolling) {
                    MANAGED_NATIVE_ROLLS.put(playerId, new ManagedNativeRoll(getRollingTicks(player)));
                    if (LOGGED_NATIVE_START.add(playerId)) {
                        PolymerPatcher.LOGGER.info("Rocky Chestplate roll bridge started successfully for {}", player.getGameProfile().name());
                    }
                } else {
                    int nextTicks = tickStandaloneRoll(player, 30);
                    if (nextTicks > 0) {
                        STANDALONE_ROLLS.put(playerId, nextTicks);
                        standaloneTicks = nextTicks;
                    }
                    if (LOGGED_STANDALONE_START.add(playerId)) {
                        PolymerPatcher.LOGGER.warn("Alex's Mobs did not enter its Rocky Chestplate roll state for {}; using Polymer Patcher's gameplay fallback", player.getGameProfile().name());
                    }
                }
            }

            // Do not carry bookkeeping from a completed native roll into the next sprint. Alex's
            // counter can finish while the player is still in a state that cannot begin another one.
            if (!nativeRolling && standaloneTicks == null) {
                MANAGED_NATIVE_ROLLS.remove(playerId);
            }

            Attached attached = ATTACHED.get(playerId);
            boolean wanted = rollingModel != null && (nativeRolling || standaloneTicks != null);

            if (attached != null && (!wanted || attached.player() != player)) {
                attached.holder().destroy();
                ATTACHED.remove(playerId);
                attached = null;
            }

            if (wanted && attached == null) {
                RockyRollModel holder = new RockyRollModel(player, rollingModel);
                EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
                ATTACHED.put(playerId, new Attached(holder, attachment, player));
            }
        }

        ATTACHED.entrySet().removeIf(entry -> {
            if (entry.getValue().player().hasDisconnected()) {
                entry.getValue().holder().destroy();
                STANDALONE_ROLLS.remove(entry.getKey());
                MANAGED_NATIVE_ROLLS.remove(entry.getKey());
                LAST_ROLL_START.remove(entry.getKey());
                LOGGED_NATIVE_START.remove(entry.getKey());
                LOGGED_STANDALONE_START.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private static boolean isRockyChestplate(ItemStack stack) {
        return !stack.isEmpty() && ROCKY_CHESTPLATE.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static boolean isRolling(LivingEntity entity) {
        if (isRolling == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isRolling.invoke(null, entity));
        } catch (Throwable e) {
            if (!loggedStateFailure) {
                loggedStateFailure = true;
                PolymerPatcher.LOGGER.warn("Could not read Alex's Mobs Rocky Chestplate roll state; the local fallback will be used", e);
            }
            return false;
        }
    }

    private static boolean isAnyRolling(LivingEntity entity) {
        return isRolling(entity) || STANDALONE_ROLLS.containsKey(entity.getUUID());
    }

    private static boolean canStartRoll(ServerPlayer player) {
        if (!player.isSprinting() || player.isShiftKeyDown() || player.getAbilities().flying || player.isPassenger()) {
            return false;
        }

        Integer lastStart = LAST_ROLL_START.get(player.getUUID());
        return lastStart == null || player.tickCount - lastStart >= 20 || Math.abs(player.tickCount - lastStart) > 100;
    }

    private static boolean startNativeRoll(LivingEntity entity) {
        if (startRolling == null) {
            return false;
        }
        try {
            startRolling.invoke(null, entity, 30);
            return isRolling(entity);
        } catch (Throwable e) {
            // Alex writes the Citadel state before broadcasting it. A client-channel exception can
            // therefore be thrown after the server-side roll was successfully established.
            if (isRolling(entity)) {
                return true;
            }
            if (!loggedStateFailure) {
                loggedStateFailure = true;
                PolymerPatcher.LOGGER.warn("Could not start Alex's Mobs Rocky Chestplate roll state; the local fallback will be used", e);
            }
            return false;
        }
    }

    /**
     * A forced roll begins after Alex's ordinary living tick. On the next server tick, either Alex's
     * hook has reduced its counter or the same value is still present. Only in the latter case do we
     * advance it ourselves, avoiding double movement when the native hook is healthy.
     */
    private static boolean maintainNativeRoll(ServerPlayer player, UUID playerId) {
        ManagedNativeRoll managed = MANAGED_NATIVE_ROLLS.get(playerId);
        int currentTicks = getRollingTicks(player);
        if (managed == null || currentTicks <= 0) {
            MANAGED_NATIVE_ROLLS.remove(playerId);
            return currentTicks > 0;
        }

        if (currentTicks < managed.lastTicks) {
            managed.nativeHookAdvanced = true;
            managed.lastTicks = currentTicks;
            return true;
        }
        if (managed.nativeHookAdvanced) {
            managed.lastTicks = currentTicks;
            return true;
        }

        if (!tickNativeRoll(player)) {
            MANAGED_NATIVE_ROLLS.remove(playerId);
            return false;
        }

        int advancedTicks = getRollingTicks(player);
        managed.lastTicks = advancedTicks;
        if (advancedTicks <= 0) {
            MANAGED_NATIVE_ROLLS.remove(playerId);
        }
        return advancedTicks > 0;
    }

    private static int getRollingTicks(LivingEntity entity) {
        if (getRollingTicks == null) {
            return -1;
        }
        try {
            return (Integer) getRollingTicks.invoke(null, entity);
        } catch (Throwable e) {
            reportStateFailure("Could not read Alex's Mobs Rocky Chestplate roll counter", e);
            return -1;
        }
    }

    private static boolean tickNativeRoll(LivingEntity entity) {
        if (tickRolling == null) {
            return false;
        }
        try {
            tickRolling.invoke(null, entity);
            return true;
        } catch (Throwable e) {
            reportStateFailure("Could not advance Alex's Mobs Rocky Chestplate roll state", e);
            return false;
        }
    }

    private static void stopNativeRoll(LivingEntity entity) {
        if (startRolling == null) {
            return;
        }
        try {
            startRolling.invoke(null, entity, 0);
        } catch (Throwable ignored) {
            // The state write happens before Alex attempts its optional client broadcast.
        }
        MANAGED_NATIVE_ROLLS.remove(entity.getUUID());
    }

    private static void reportStateFailure(String message, Throwable error) {
        if (!loggedStateFailure) {
            loggedStateFailure = true;
            PolymerPatcher.LOGGER.warn(message + "; the local fallback will be used", error);
        }
    }

    /** Mirrors RockyChestplateUtil's server-side roll while keeping the fallback Alex-specific. */
    private static int tickStandaloneRoll(ServerPlayer player, int ticksLeft) {
        if (player.isInWater()) {
            player.setDeltaMovement(player.getDeltaMovement().add(0.0D, -0.015D, 0.0D));
        }

        for (LivingEntity other : player.level().getEntitiesOfClass(
            LivingEntity.class, player.getBoundingBox().inflate(1.0D))) {
            if (other != player && !player.isAlliedTo(other) && !other.isAlliedTo(player)) {
                other.hurt(other.damageSources().mobAttack(player), 2.0F + player.getRandom().nextFloat());
            }
        }

        if (player.fallDistance > 3.0D) {
            player.fallDistance -= 0.5D;
        }
        player.refreshDimensions();

        Vec3 previous = player.onGround()
            ? player.getDeltaMovement()
            : player.getDeltaMovement().multiply(0.9D, 1.0D, 0.9D);
        float yaw = player.getYRot() * ((float) Math.PI / 180.0F);
        float acceleration = player.isInWater() ? 0.05F : 0.15F;
        Vec3 horizontal = new Vec3(
            previous.x - Mth.sin(yaw) * acceleration,
            0.0D,
            previous.z + Mth.cos(yaw) * acceleration
        );
        double vertical = player.isInWater() || player.isShiftKeyDown()
            ? -0.1D
            : ticksLeft >= 30 ? 0.27D : previous.y;
        player.setDeltaMovement(horizontal.add(0.0D, vertical, 0.0D));

        int nextTicks = ticksLeft;
        if (ticksLeft > 1 || !player.isSprinting()) {
            nextTicks--;
        }
        if (player.getAbilities().flying || player.isShiftKeyDown()) {
            nextTicks = 0;
        }
        return Math.max(0, nextTicks);
    }
}
