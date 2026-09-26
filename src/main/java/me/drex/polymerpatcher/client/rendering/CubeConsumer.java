package me.drex.polymerpatcher.client.rendering;

import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;

public interface CubeConsumer {
    ThreadLocal<CubeConsumer> CONSUMER = ThreadLocal.withInitial(() -> null);


    void consume(ModelPart part, Matrix4f matrix4f, boolean hidden);
}
