package me.drex.polymerpatcher.client.rendering;

import com.mojang.blaze3d.vertex.VertexConsumer;

public class NoOpVertexConsumer implements VertexConsumer {
    public static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();

    @Override
    public VertexConsumer addVertex(float f, float g, float h) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i, int j, int k, int l) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float f, float g) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int i, int j) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int i, int j) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float f, float g, float h) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float f) {
        return this;
    }
}
