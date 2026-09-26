package me.drex.polymerpatcher.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import me.drex.polymerpatcher.entity.AutomaticPolymerEntity;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps an unseen client collision shape from fighting the server over the player's position. */
@Mixin(Entity.class)
public abstract class EntityPushMixin {
    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void polymerPatcher$hideUnknownSolidBody(Entity other,
                                                      CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ServerPlayer player && isVirtualFor(other, player)) {
            // Movement collision is predicted on both sides. The server knows a submarine or other
            // modded entity is a large solid body; an unmodded client only knows about its non-solid
            // display pieces. Letting the server include that unseen body in the player's collision
            // sweep repeatedly pushes the player out and the client repeatedly walks them back in.
            // Pickability/interactions use a different path and remain available through the model's
            // interaction element.
            cir.setReturnValue(false);
        } else if (other instanceof ServerPlayer player && isVirtualFor(self, player)) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
    private void polymerPatcher$syncPush(Entity pushed, double x, double y, double z,
                                         Operation<Void> original, Entity other) {
        Entity self = (Entity) (Object) this;
        if (pushed instanceof ServerPlayer player
            && (isVirtualFor(self, player) || isVirtualFor(other, player))) {
            // A vanilla client cannot know the solid body of a modded entity represented by display
            // pieces. Applying the push and then correcting the client every collision tick made the
            // player shake violently. Suppressing only the player's half of that collision is stable;
            // native clients still use the real entity and retain its normal collision.
            return;
        }
        original.call(pushed, x, y, z);
    }

    private static boolean isVirtualFor(Entity candidate, ServerPlayer player) {
        if (!(PolymerEntity.get(candidate) instanceof AutomaticPolymerEntity<?>)) {
            return false;
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());
        return id == null || !NativeClients.has(player, id.getNamespace());
    }
}
