package me.drex.polymerpatcher.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.attachment.UniqueIdentifiableAttachment;
import me.drex.polymerpatcher.entity.AutomaticPolymerEntity;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    public LivingEntityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @ModifyExpressionValue(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isClientSide()Z",
            ordinal = 2
        )
    )
    public boolean serverSideWalkAnimation(boolean original) {
        return original || PolymerEntity.get(this) instanceof AutomaticPolymerEntity;
    }

    /** Effects that only draw themselves on a client are drawn here for the players without their mod. */
    @Inject(method = "tickEffects", at = @At("HEAD"))
    private void polymerPatcher$drawClientEffects(CallbackInfo ci) {
        me.drex.polymerpatcher.util.ClientParticleReplay.tickEffects((LivingEntity) (Object) this);
    }

    @ModifyArg(
        method = "take",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundTakeItemEntityPacket;<init>(III)V"
        ),
        index = 1
    )
    public int fixTakeItemEntityId(int original) {
        UniqueIdentifiableAttachment attachment = UniqueIdentifiableAttachment.get(this, AutomaticPolymerEntity.MODEL);
        if (attachment == null) return original;
        var model = (SimpleEntityModel) attachment.holder();

        return model.leadAttachment.getEntityId();
    }

    /**
     * Says nothing at all when the thing that picked the item up is one the client has been handed as
     * an item display.
     * <p>
     * The client takes this packet as "entity N now holds that item" and treats N as something alive -
     * it does not check, it casts, and a display is not a living entity. A patched mob normally has a
     * model whose lead attachment stands in for it, and the id above is swapped for that; a mob with no
     * model has nothing to swap in, and the real id would be a disconnect for everyone watching.
     * <p>
     * What is lost is the little animation of the item flying into the mob. What is kept is the
     * connection.
     */
    @WrapOperation(
        method = "take",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;sendToTrackingPlayers(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    public void dontPointAtADisplay(ServerChunkCache instance, Entity entity, Packet<?> packet, Operation<Void> original) {
        if (PolymerEntity.get(this) instanceof AutomaticPolymerEntity
            && UniqueIdentifiableAttachment.get(this, AutomaticPolymerEntity.MODEL) == null) {
            return;
        }

        original.call(instance, entity, packet);
    }
}
