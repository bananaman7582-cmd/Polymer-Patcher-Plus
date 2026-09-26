package me.drex.polymerpatcher.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.drex.polymerpatcher.client.rendering.CubeConsumer;
import me.drex.polymerpatcher.duck.IModelPart;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements IModelPart {

    @Unique
    private int polymer_patcher$Id = -1;
    @Unique
    private ModelLayerLocation polymer_patcher$modelLayerLocation = null;

    @Shadow
    public boolean skipDraw;

    @Shadow
    @Final
    private List<ModelPart.Cube> cubes;

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/geom/ModelPart;translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            shift = At.Shift.AFTER
        )
    )
    public void serverSideRender(PoseStack poseStack, VertexConsumer vertexConsumer, int i, int j, int k, CallbackInfo ci) {
        CubeConsumer cubeConsumer = CubeConsumer.CONSUMER.get();
        if (cubeConsumer == null || this.cubes.isEmpty()) return;
        cubeConsumer.consume((ModelPart) (Object) this, poseStack.last().pose(), this.skipDraw);
    }

    @Override
    public void polymer_patcher$setId(int id) {
        this.polymer_patcher$Id = id;
    }

    @Override
    public int polymer_patcher$getId() {
        return polymer_patcher$Id;
    }

    @Override
    public void polymer_patcher$setModelLayerLocation(ModelLayerLocation location) {
        this.polymer_patcher$modelLayerLocation = location;
    }

    @Override
    public ModelLayerLocation polymer_patcher$getModelLayerLocation() {
        return polymer_patcher$modelLayerLocation;
    }
}
