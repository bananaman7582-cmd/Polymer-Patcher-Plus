package me.drex.polymerpatcher.client.rendering;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;

public interface CubeConsumer {
    ThreadLocal<CubeConsumer> CONSUMER = ThreadLocal.withInitial(() -> null);


    void consume(ModelPart part, Matrix4f matrix4f, boolean hidden);

    /**
     * The same, told which buffer the part is being drawn into and with what overlay. Only a part drawn
     * by hand needs either: one drawn through the collector is already known to be the model it was given.
     */
    default void consume(ModelPart part, Matrix4f matrix4f, boolean hidden, VertexConsumer buffer, int overlayCoords) {
        consume(part, matrix4f, hidden);
    }
}
