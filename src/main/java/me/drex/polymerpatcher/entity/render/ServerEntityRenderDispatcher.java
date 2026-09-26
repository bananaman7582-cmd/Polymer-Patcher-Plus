package me.drex.polymerpatcher.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;

public class ServerEntityRenderDispatcher extends EntityRenderDispatcher {
    public ServerEntityRenderDispatcher() {
        super(null, null, null, new ServerItemModelResolver(), null, null, null, null, null, null, null);
    }

    @Override
    public <E extends Entity> int getPackedLightCoords(E entity, float f) {
        return LightCoordsUtil.pack(15, 15);
    }

    @Override
    public <T extends Entity> EntityRenderer<? super T, ?> getRenderer(T entity) {
        return super.getRenderer(entity);
    }

    @Override
    public AvatarRenderer<AbstractClientPlayer> getPlayerRenderer(AbstractClientPlayer abstractClientPlayer) {
        return super.getPlayerRenderer(abstractClientPlayer);
    }

    @Override
    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S entityRenderState) {
        return super.getRenderer(entityRenderState);
    }

    @Override
    public void prepare(Camera camera, Entity entity) {
        super.prepare(camera, entity);
    }

    @Override
    public <E extends Entity> boolean shouldRender(E entity, Frustum frustum, double d, double e, double f) {
        return super.shouldRender(entity, frustum, d, e, f);
    }

    @Override
    public <E extends Entity> EntityRenderState extractEntity(E entity, float f) {
        return super.extractEntity(entity, f);
    }

    @Override
    public <S extends EntityRenderState> void submit(S entityRenderState, CameraRenderState cameraRenderState, double d, double e, double f, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
        super.submit(entityRenderState, cameraRenderState, d, e, f, poseStack, submitNodeCollector);
    }

    @Override
    public void resetCamera() {
        super.resetCamera();
    }

    /**
     * How far the mob is from the camera - nothing, because there is no camera here.
     * <p>
     * The game answers this by measuring against where the player is looking from, and this dispatcher
     * has no such place: it was built without one, since nothing is being looked at. Handing the
     * question to the game anyway read that missing camera and threw, which is not a small thing - a
     * renderer asks this to decide whether a name tag is close enough to draw, so Alex's Mobs'
     * underminer failed on it before drawing anything at all and was never seen.
     * <p>
     * Zero says "right next to the camera", which keeps every distance test on the generous side. A mob
     * drawn when it might not have been is better than one that is not drawn at all.
     */
    @Override
    public double distanceToSqr(Entity entity) {
        return 0.0D;
    }

    @Override
    public ItemInHandRenderer getItemInHandRenderer() {
        return super.getItemInHandRenderer();
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
//        super.onResourceManagerReload(resourceManager);
    }
}
