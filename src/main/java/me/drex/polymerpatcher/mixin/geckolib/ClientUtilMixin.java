package me.drex.polymerpatcher.mixin.geckolib;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibTriggerBridge;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells GeckoLib what time it is, and where the world is, while a mob of ours is being posed.
 * <p>
 * Every GeckoLib animation is worked out from a clock, and GeckoLib reads that clock - and the level it
 * needs alongside it - off the running client. The stand-in client this mod poses against has neither,
 * so a controller either advanced against a window timer that never moved or gave up before it started.
 * The mobs were built, textured and posed for one instant and never moved again.
 * <p>
 * The server has both to hand, so they are handed over - but only while a mob is being posed here.
 * Everywhere else GeckoLib is left to answer for itself.
 */
@Mixin(targets = "com.geckolib.util.ClientUtil", remap = false)
public class ClientUtilMixin {

    @Unique
    private static boolean polymer_patcher$seenTickFloat;
    @Unique
    private static boolean polymer_patcher$seenTick;
    @Unique
    private static boolean polymer_patcher$seenLevel;
    @Unique
    private static boolean polymer_patcher$seenPlayer;
    @Unique
    private static boolean polymer_patcher$seenCamera;

    /**
     * The clock, for callers that pass a partial tick - Crop Critters' render path is one.
     */
    @Inject(method = "getCurrentTick(Ljava/lang/Float;)D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void polymer_patcher$serverTickFloat(Float partialTick, CallbackInfoReturnable<Double> cir) {
        if (!polymer_patcher$seenTickFloat) {
            polymer_patcher$seenTickFloat = true;
            PolymerPatcher.LOGGER.info("GeckoLib's clock (with partial tick) is being intercepted");
        }
        polymer_patcher$overrideTick(partialTick != null ? partialTick : 0.0F, cir);
    }

    /**
     * The clock, for callers that pass no partial tick - {@code AnimationController} calls this one, so
     * this is the site the animations actually turn on.
     */
    @Inject(method = "getCurrentTick()D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void polymer_patcher$serverTick(CallbackInfoReturnable<Double> cir) {
        if (!polymer_patcher$seenTick) {
            polymer_patcher$seenTick = true;
            PolymerPatcher.LOGGER.info("GeckoLib's clock (no partial tick) is being intercepted");
        }
        polymer_patcher$overrideTick(0.0F, cir);
    }

    /**
     * The world GeckoLib reaches for while animating - proof the animation code runs at all, separate
     * from whether it can read its clock.
     */
    @Inject(method = "getLevel()Lnet/minecraft/world/level/Level;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void polymer_patcher$serverLevel(CallbackInfoReturnable<Level> cir) {
        Entity entity = polymer_patcher$currentEntity();
        if (entity == null) {
            return;
        }
        if (!polymer_patcher$seenLevel) {
            polymer_patcher$seenLevel = true;
            PolymerPatcher.LOGGER.info("GeckoLib is asking for the level while animating; handing it the server's");
        }
        cir.setReturnValue(entity.level());
    }

    /**
     * The player GeckoLib reaches for while animating. {@code extractControllerStates} gives up and
     * hands {@code applyAnimationControllers} an empty list whenever this is null, so the whole
     * controller animation is skipped with it - a mob built and posed but never moved. Whoever is
     * standing nearest to the mob is close enough to stand in for the client player here.
     */
    @Inject(method = "getClientPlayer()Lnet/minecraft/world/entity/player/Player;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void polymer_patcher$serverPlayer(CallbackInfoReturnable<Player> cir) {
        Entity entity = polymer_patcher$currentEntity();
        if (entity == null || entity.level() == null) {
            return;
        }
        Player player = entity.level().getNearestPlayer(entity, -1.0);
        if (player == null) {
            return;
        }
        if (!polymer_patcher$seenPlayer) {
            polymer_patcher$seenPlayer = true;
            PolymerPatcher.LOGGER.info("GeckoLib is asking for a player while animating; handing it the server's nearest");
        }
        cir.setReturnValue(player);
    }

    /**
     * The camera GeckoLib reaches for while animating. It is only read into the Molang {@code Actor},
     * but leaving it null on the dedicated server means any animation that looks at the camera finds
     * nothing there.
     */
    @Inject(method = "getCameraPos()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void polymer_patcher$serverCamera(CallbackInfoReturnable<Vec3> cir) {
        Entity entity = polymer_patcher$currentEntity();
        if (entity == null) {
            return;
        }
        if (!polymer_patcher$seenCamera) {
            polymer_patcher$seenCamera = true;
            PolymerPatcher.LOGGER.info("GeckoLib is asking for the camera while animating; handing it the mob's eyes");
        }
        cir.setReturnValue(entity.getEyePosition());
    }

    /**
     * Answers the clock from the entity being posed, when one is.
     */
    @Unique
    private static void polymer_patcher$overrideTick(float partialTick, CallbackInfoReturnable<Double> cir) {
        Entity entity = polymer_patcher$currentEntity();
        if (entity == null || entity.level() == null) {
            return;
        }
        cir.setReturnValue((double) entity.level().getGameTime() + partialTick);
    }

    @Unique
    private static Entity polymer_patcher$currentEntity() {
        Entity triggering = GeckoLibTriggerBridge.activeEntity();
        if (triggering != null) return triggering;
        SimpleEntityModel<?, ?, ?> model = ServerSubmitNodeCollector.ACTIVE_ENTITY.get();
        return model != null ? model.polymer_patcher$entity() : null;
    }
}
