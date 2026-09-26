package me.drex.polymerpatcher.entity.geckolib;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.network.packet.MultiloaderPacket;
import com.geckolib.network.packet.entity.EntityAnimTriggerPacket;
import com.geckolib.network.packet.entity.StopTriggeredEntityAnimPacket;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GeckoLib normally applies one-shot triggers only after a packet reaches a modded client. A vanilla
 * client has no GeckoLib manager, while our server-side renderer uses the server's manager to pose its
 * display parts. Mirror the same trigger there; leave the packet alone for native clients.
 */
public final class GeckoLibTriggerBridge {
    private static final ThreadLocal<Entity> TRIGGER_ENTITY = new ThreadLocal<>();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final Set<Class<?>> FAILED = ConcurrentHashMap.newKeySet();

    private GeckoLibTriggerBridge() {
    }

    @Nullable
    public static Entity activeEntity() {
        return TRIGGER_ENTITY.get();
    }

    public static void mirror(MultiloaderPacket packet, Entity entity) {
        if (entity.level().isClientSide() || !(entity instanceof GeoEntity geo)) {
            return;
        }

        try {
            if (packet instanceof EntityAnimTriggerPacket trigger) {
                if (trigger.isReplacedEntity() || trigger.entityId() != entity.getId()) return;
                withEntity(entity, () -> {
                    AnimatableManager<?> manager = geo.getAnimatableInstanceCache().getManagerForId(entity.getId());
                    if (trigger.controllerName().isPresent()) {
                        manager.tryTriggerAnimation(trigger.controllerName().get(), trigger.animName());
                    } else {
                        manager.tryTriggerAnimation(trigger.animName());
                    }
                });
            } else if (packet instanceof StopTriggeredEntityAnimPacket stop) {
                if (stop.isReplacedEntity() || stop.entityId() != entity.getId()) return;
                withEntity(entity, () -> {
                    AnimatableManager<?> manager = geo.getAnimatableInstanceCache().getManagerForId(entity.getId());
                    if (stop.controllerName().isPresent()) {
                        manager.stopTriggeredAnimation(stop.controllerName().get(), stop.animName().orElse(null));
                    } else {
                        manager.stopTriggeredAnimation(stop.animName().orElse(null));
                    }
                });
            } else {
                return;
            }

            if (REPORTED.compareAndSet(false, true)) {
                PolymerPatcher.LOGGER.info("GeckoLib one-shot entity animations are being mirrored for vanilla clients");
            }
        } catch (Throwable e) {
            // A failed stand-in animation must not prevent GeckoLib from sending the original packet.
            if (FAILED.add(entity.getClass())) {
                PolymerPatcher.LOGGER.warn("Could not mirror GeckoLib animation trigger for {}", entity.getClass().getName(), e);
            }
        }
    }

    private static void withEntity(Entity entity, Runnable action) {
        Entity previous = TRIGGER_ENTITY.get();
        TRIGGER_ENTITY.set(entity);
        try {
            action.run();
        } finally {
            if (previous == null) {
                TRIGGER_ENTITY.remove();
            } else {
                TRIGGER_ENTITY.set(previous);
            }
        }
    }
}
