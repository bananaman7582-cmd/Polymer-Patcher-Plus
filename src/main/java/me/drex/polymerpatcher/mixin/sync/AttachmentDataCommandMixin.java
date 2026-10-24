package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.fabricmc.fabric.impl.attachment.DataAccessorHandler;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.Map;

/** Keep Fabric's /data reconciliation, but allow the first lazy attachment allocation during load. */
@Mixin(value = DataAccessorHandler.class, remap = false)
public abstract class AttachmentDataCommandMixin {
    @WrapOperation(method = "applyDataChanges", at = @At(value = "INVOKE",
        target = "Ljava/lang/ScopedValue$Carrier;run(Ljava/lang/Runnable;)V"))
    private static void polymerPatcher$allowInitialAllocation(@Coerce Object scope, Runnable load,
            Operation<Void> original, AttachmentTarget target, ValueInput input, Runnable applyData,
            @Local LocalRef<Map<AttachmentType<?>, ?>> previous) {
        original.call(scope, load);
        // getAttachedOrCreate during a mod's readAdditionalSaveData can legitimately allocate the
        // previously absent map. Fabric mistakes null -> map for replacement/corruption and crashes
        // the entire world tick. Use that first map for its normal persistence/sync reconciliation.
        // Never accept replacement of an existing map: Fabric's assertion must still catch that.
        if (previous.get() == null) {
            previous.set(((AttachmentTargetImpl) target).fabric_getAttachments());
        }
    }
}
