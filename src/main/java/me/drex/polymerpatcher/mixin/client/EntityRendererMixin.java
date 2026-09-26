package me.drex.polymerpatcher.mixin.client;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    /**
     * The camera the server has not got.
     * <p>
     * This used to sit in {@code extractRenderState}; 26.2 moved it down into the name-tag work that
     * method calls, so it is caught there instead. The overload is named in full because only the
     * longer of the two reads the camera - the shorter one just passes the work along to it.
     */
    @WrapOperation(
        method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;camera:Lnet/minecraft/client/Camera;",
            opcode = Opcodes.GETFIELD
        )
    )
    public Camera dontAccessEntityRenderDispatcher(EntityRenderDispatcher instance, Operation<Camera> original) {
        return null;
    }

    @Definition(id = "entity", local = @Local(type = Entity.class, argsOnly = true))
    @Definition(id = "Leashable", type = Leashable.class)
    @Expression("entity instanceof Leashable")
    @WrapOperation(method = "extractRenderState", at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    public boolean weDontNeedThis(Object object, Operation<Boolean> original) {
        return false;
    }

    @WrapOperation(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    public boolean dontAccessClient(Minecraft instance, Entity entity, Operation<Boolean> original) {
        return entity.isCurrentlyGlowing();
    }

    @WrapOperation(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
        )
    )
    public void notNeeded(EntityRenderer instance, S entityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, Operation<Void> original) {

    }
}
