package me.drex.polymerpatcher.mixin.polymer;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Serializes access to Polymer Common's lazily populated template-entity caches.
 *
 * <p>Polymer 0.17.5 stores both caches in ordinary, non-thread-safe maps. Resource-pack model capture
 * and packet preparation can ask for templates concurrently; a resize race corrupts the fastutil
 * tracked-data map and leaves every later lookup spinning forever. The server watchdog then kills an
 * otherwise fully started server. Both public cache entrances share this reentrant monitor, including
 * the nested getEntity call made while tracked data is being created.</p>
 */
@Mixin(targets = "eu.pb4.polymer.common.impl.entity.InternalEntityHelpers", remap = false)
public abstract class InternalEntityHelpersMixin {
    @Unique
    private static final Object polymerPatcher$entityCacheLock = new Object();

    @WrapMethod(method = "getExampleTrackedDataOfEntityType")
    private static SynchedEntityData.DataItem<?>[] polymerPatcher$serializeTrackedData(
        EntityType<?> type, Operation<SynchedEntityData.DataItem<?>[]> original
    ) {
        synchronized (polymerPatcher$entityCacheLock) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "getEntity")
    private static Entity polymerPatcher$serializeExampleEntities(
        EntityType<?> type, Operation<Entity> original
    ) {
        synchronized (polymerPatcher$entityCacheLock) {
            return original.call(type);
        }
    }
}
