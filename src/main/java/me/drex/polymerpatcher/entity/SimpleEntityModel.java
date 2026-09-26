package me.drex.polymerpatcher.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.factorytools.api.virtualentity.emuvanilla.poly.RideAttachmentElement;
import eu.pb4.factorytools.api.virtualentity.emuvanilla.poly.LeadAttachmentElement;
import eu.pb4.factorytools.mixin.LivingEntityAccessor;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import eu.pb4.polymer.virtualentity.api.data.DisplayEntityData;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import me.drex.polymerpatcher.duck.IModelPart;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import me.drex.polymerpatcher.util.NativeClients;
import me.drex.polymerpatcher.mixin.client.AbstractBoatRendererAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapItemColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

public class SimpleEntityModel<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> extends HitboxModel {
    /** Entity types whose model has already been reported as failing, so the log says it once. */
    private static final java.util.Set<net.minecraft.world.entity.EntityType<?>> REPORTED_FAILURES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public final LeadAttachmentElement leadAttachment = new LeadAttachmentElement();
    public final RideAttachmentElement rideAttachment = new RideAttachmentElement();
    protected final Entity entity;
    protected final EntityRenderer<Entity, RenderState> renderer;

    /** Carries this mob's stride between ticks, for the renderers that leave it at nothing. */
    protected final ServerRenderStates.Walk walk = new ServerRenderStates.Walk();

    private boolean noTick = true;

    /**
     * Whether the model has to be posed again even though nobody is watching yet.
     * <p>
     * Set when a player starts watching, because they are added to the watch list only after this
     * holder has been asked to catch up - so at the moment the question is asked the list is still
     * empty, and going by that alone would hand the first player a mob frozen in whatever pose it last
     * held.
     */
    private boolean posePending = true;

    private final List<ItemDisplayElement> activeElements = new ArrayList<>();
    /** Last pose sent for each display; unchanged parts need no matrix decomposition or packet. */
    private final List<Matrix4f> activeMatrices = new ArrayList<>();
    /** A newly spawned display skips teleport smoothing once, then enables it on its first update. */
    private final BitSet activeTeleportEnabled = new BitSet();
    /** Last range written to every active part, so stable models do not dirty metadata each pose. */
    private float appliedModelViewRange = Float.NaN;
    public int activeElementIndex = 0;

    public SimpleEntityModel(Entity entity, PolyModelInstance<Entity, RenderState, Model> model) {
        this(entity, model.renderer());
    }

    protected SimpleEntityModel(Entity entity, EntityRenderer<Entity, RenderState> renderer) {
        super(entity);
        this.entity = entity;
        this.renderer = renderer;
        this.addElement(leadAttachment);
        this.addElement(rideAttachment);
    }

    /** The lead is held at the middle of the mob and a rider sits on top of it. */
    @Override
    protected void onHitboxResized(float width, float height) {
        this.leadAttachment.setOffset(new Vec3(0, height / 2, 0));
        this.rideAttachment.setOffset(new Vec3(0, height, 0));
        for (ItemDisplayElement element : activeElements) {
            applyCullBounds(element, width, height);
        }
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        // A player being shown the real mob draws it themselves; the stand-in would sit on top of it
        if (NativeClients.has(player.getPlayer(), entityNamespace())
            || BorrowedEchoCompat.hidesVirtualModel(entity)) {
            return false;
        }

        // The next tick poses it even if this is still the only watcher and the list is empty now
        posePending = true;

        if (noTick) {
            onTick();
        }
        return super.startWatching(player);
    }

    /** Ticks since this model was last posed, for the mobs that are not posed every one. */
    private int ticksSincePose = 0;

    /**
     * Whether this mob is worth posing on this tick.
     * <p>
     * Two questions, in the order they are cheapest to answer. Nobody watching means nobody to show it
     * to. Watching from far away means the pose is still worth having but not twenty times a second -
     * a mob forty blocks off refreshed five times a second looks the same and costs a quarter as much,
     * and on a server where most watched mobs are near the edge of view rather than in front of a face,
     * most of them are that mob.
     */
    private boolean worthPosing() {
        var watchers = getWatchingPlayers();
        if (watchers.isEmpty()) {
            ticksSincePose = 0;
            return false;
        }

        var config = ConfigManager.config().entities;
        int interval = Math.max(1, config.distantPoseInterval);
        if (interval == 1) {
            return true;
        }

        // Measured to the mob's edge rather than its middle. Distance to an entity is distance to the
        // point it stands on, which is the same thing for a chicken and nothing like it for a whale:
        // standing beside one put the player thirty blocks from its centre, so the largest and most
        // obvious animals on the server were the ones being posed least often. Its own size is added
        // back so a mob is judged by how near it actually is
        double reach = config.fullDetailDistance + Math.max(entity.getBbWidth(), entity.getBbHeight());
        double full = reach * reach;
        for (var watcher : watchers) {
            var player = watcher.getPlayer();
            if (player != null && player.distanceToSqr(entity) <= full) {
                ticksSincePose = 0;
                return true;
            }
        }

        if (++ticksSincePose >= interval) {
            ticksSincePose = 0;
            return true;
        }
        return false;
    }

    /** The mob this holder draws, for the few places that reach in from outside. */
    public Entity polymer_patcher$entity() {
        return entity;
    }

    private String entityNamespace() {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null ? id.getNamespace() : Identifier.DEFAULT_NAMESPACE;
    }

    @Override
    protected void onTick() {
        noTick = false;
        // Observe movement on every server tick, even when distant poses are downsampled. Measuring
        // only between rendered poses made a walking mob's stride slow down as it moved farther away.
        walk.observe(entity);
        if (this.entity instanceof LivingEntity livingEntity) {
            this.rideAttachment.setMaxHealth(livingEntity.getMaxHealth());
            this.rideAttachment.getSyncedData().set(LivingEntityAccessor.getDATA_HEALTH_ID(), livingEntity.getHealth());
        }
        this.interaction.setCustomName(this.entity.getCustomName());
        this.interaction.setCustomNameVisible(this.entity.isCustomNameVisible()
            && !BorrowedEchoCompat.suppressVirtualName(this.entity));
        this.rideAttachment.setYaw(entity.getYRot());
        syncRideAttachment();
        syncHitbox();

        // Nobody can see it, so it is not posed.
        //
        // What follows is a real client renderer: a pose stack, the model, every feature layer, run in
        // full. It was run every tick for every modded mob on the server, whether or not a single
        // player was in a position to see any of it - a herd of bison in an unloaded corner of the world
        // cost exactly as much as one standing in front of you. On a server with hundreds of modded
        // mobs that is the largest thing this mod does, and almost all of it was thrown away.
        //
        // The elements keep whatever pose they last had, which is what a player who arrives later is
        // shown for the one tick before the next pose reaches them.
        if (!posePending && !worthPosing()) {
            super.onTick();
            return;
        }
        posePending = false;

        // Attempted more than once, because a model can reach for a class the server refused to load
        // and only finds out when it gets there. Alex's Mobs animates through Citadel's ModelAnimator,
        // which needs a client-only Transform class: a bison standing still never asks for it and draws
        // perfectly, while one that starts eating or walking asks, is refused, and loses every element
        // it had. That is why some of a herd looked right and the rest went untextured, and why the
        // same mob came and went as its mood changed. Defining the refused class and going round again
        // fixes it for that mob and every other one that would have asked, for good.
        for (int round = 0; ; round++) {
            PoseStack poseStack = new PoseStack();

            // ensure the element doesn't clip into nearby blocks
            EntityDimensions dimensions = entity.getDimensions(entity.getPose());
            poseStack.translate(0.0F, -dimensions.height() / 2, 0.0F);

            try {
                ServerSubmitNodeCollector.ACTIVE_ENTITY.set(this);
                render(poseStack);
                break;
            } catch (Throwable e) {
                // Whatever it was refused is loaded here and now, and the whole pose is started again
                // from the top rather than resumed halfway through
                if (round < ClientOnlyClasses.maxRounds() && ClientOnlyClasses.defineRefused(e)) {
                    continue;
                }

                // Said out loud once per entity type. A model that throws here throws every tick, and
                // the elements it drew last time simply stay where they were - the mob stops turning,
                // stops animating, or never appears at all, with nothing anywhere saying why. Being
                // quiet about this hid every one of those symptoms behind a debug line nobody turns on
                if (REPORTED_FAILURES.add(entity.getType())) {
                    PolymerPatcher.LOGGER.warn("Failed to render {}; it will not animate or turn. This is reported once per entity type.",
                        entity.getType().builtInRegistryHolder().key().identifier(), e);
                }
                PolymerPatcher.LOGGER.debug("Failed to render", e);
                break;
            } finally {
                ServerSubmitNodeCollector.ACTIVE_ENTITY.remove();
            }
        }

        while (activeElements.size() > activeElementIndex) {
            removeElement(activeElements.removeLast());
            activeMatrices.removeLast();
            activeTeleportEnabled.clear(activeElements.size());
        }

        applyModelViewRange();

        super.onTick();
    }

    /**
     * Places the invisible client-side vehicle where the server actually placed its passenger.
     *
     * <p>Using the entity height is only a tolerable guess for ordinary mobs. Vehicles are free to put
     * their seat anywhere (a submarine's driver is forward and down inside its cockpit), and the server
     * has already calculated that exact point in the passenger's current position. Mirroring that
     * relative offset keeps the vanilla client's camera at the authoritative seat and works for any
     * future vehicle without knowing its mod.</p>
     */
    private void syncRideAttachment() {
        List<net.minecraft.world.entity.Entity> passengers = entity.getPassengers();
        if (passengers.isEmpty()) {
            EntityDimensions dimensions = entity.getDimensions(entity.getPose());
            rideAttachment.setOffset(new Vec3(0, dimensions.height(), 0));
            return;
        }

        net.minecraft.world.entity.Entity passenger = passengers.getFirst();
        Vec3 override = me.drex.polymerpatcher.entity.render.RenderCaptureRules.rideOffset(entity, passenger);
        if (override != null) {
            rideAttachment.setOffset(override);
            return;
        }
        // Ask the vehicle for its authoritative attachment point rather than reading the passenger's
        // last tick position. Modded vehicles commonly place riders forward/down inside a cockpit;
        // the stored passenger position can lag that calculation by a tick and left vanilla clients
        // riding the stand-in at the vehicle origin.
        rideAttachment.setOffset(entity.getPassengerRidingPosition(passenger).subtract(entity.position()));
    }

    /**
     * Poses the entity and hands whatever it draws to the elements standing in for it.
     * <p>
     * {@code activeElementIndex} is only reset once the model has posed without throwing: a model that
     * fails halfway leaves the elements it already had rather than dropping every one of them, so the
     * mob stays where it was instead of blinking out of the world for a tick.
     */
    /**
     * Handed to every renderer in place of the camera there is not one of.
     *
     * <p>A renderer is given the camera's own state so it can turn towards it, and passing nothing at
     * all is not the same as passing a camera that is not looking anywhere: a renderer that reads it
     * without checking throws, and the entity is drawn as nothing.</p>
     */
    protected static final net.minecraft.client.renderer.state.level.CameraRenderState DEFAULT_CAMERA_STATE =
        new net.minecraft.client.renderer.state.level.CameraRenderState();

    protected void render(PoseStack poseStack) {
        RenderState renderState = renderer.createRenderState();
        renderer.extractRenderState(entity, renderState, 0);
        // Some mods pick a texture straight off the entity and reach it through the state; see ICapturedEntity
        if (renderState instanceof me.drex.polymerpatcher.duck.ICapturedEntity captured) {
            captured.polymerPatcher$capture(entity, 0F);
        }
        ServerRenderStates.applyRotations(entity, renderState);
        walk.apply(renderState);
        if (renderer instanceof LivingEntityRenderer livingEntityRenderer) {
            livingEntityRenderer.getModel().setupAnim(renderState);
        } else if (renderer instanceof AbstractBoatRendererAccessor abstractBoatRenderer) {
            abstractBoatRenderer.invokeModel().setupAnim((BoatRenderState) renderState);
        }

        ServerSubmitNodeCollector<Entity, RenderState, Model> nodeCollector = new ServerSubmitNodeCollector<>(this);
        activeElementIndex = 0;

        renderer.submit(renderState, poseStack, nodeCollector, DEFAULT_CAMERA_STATE);
    }

    public void updateModelPart(ModelPart part, Matrix4f matrix4f, int overlayCoords, Identifier texture, boolean translucent, boolean hidden) {
        if (!BorrowedEchoCompat.allowCapturedTexture(entity, texture)) {
            return;
        }
        // Some renderers hold their chosen texture in a render-state object. Compatibility code can
        // still make the semantic per-entity choice here, at the last shared boundary before that
        // texture becomes a generated item model. This keeps the captured animation/model geometry.
        texture = me.drex.polymerpatcher.entity.render.RenderCaptureRules.entityTexture(entity, texture);
        int id = ((IModelPart) (Object) part).polymer_patcher$getId();
        ModelLayerLocation modelLayer = ((IModelPart) (Object) part).polymer_patcher$getModelLayerLocation();
        if (id < 0 || modelLayer == null || hidden) {
            return;
        }

        boolean redOverlay = (overlayCoords >> 16) == OverlayTexture.RED_OVERLAY_V;

        updateItemDisplayElement(createItemStack(id, redOverlay, texture, modelLayer, translucent), matrix4f);
    }

    public void updateBlock(BlockState blockState, Matrix4f matrix4f) {
        var randomSource = RandomSource.create(0);
        List<BlockStateModelManager.ModelGetter> modelGetters = BlockStateModelManager.get(blockState);

        for (BlockStateModelManager.ModelGetter modelGetter : modelGetters) {

            BlockStateModelManager.ModelData model = modelGetter.getModel(randomSource);

            Matrix4f transformation = new  Matrix4f(matrix4f);
            transformation.rotate(model.quaternionfc());
            // A renderer which feeds raw 0..1 block geometry to BlockRenderDispatcher may already
            // include the centring transform that an item display adds for itself. Mod-specific
            // corrections live in compat; globally changing this would move Magnetron blocks and every
            // other correctly captured block.
            me.drex.polymerpatcher.entity.render.RenderCaptureRules.block(
                entity, blockState, transformation);
            // A block embedded in an entity is still drawn in block/world space. Item displays otherwise
            // apply the model's FIXED display transform, which is deliberately half-sized for frames and
            // shrank every cube in a Magnetron after its renderer had already positioned it as a full block.
            // NONE is the same context the ordinary virtual-block path uses, and preserves whatever scale
            // the source renderer put in the matrix for every mod that draws a real BlockState in an entity.
            ItemDisplayElement element = updateItemDisplayElement(model.stack(), transformation);
            element.setItemDisplayContext(ItemDisplayContext.NONE);
        }
    }

    public void updateItem(ItemStack itemStack, ItemDisplayContext context, Matrix4f matrix4f) {
        // Citadel's DesolateDaggerRenderer draws an ItemStack directly. That path never reaches
        // ForeignCitadelModel.drewItem(), where the projectile replacement used to live, so the pack's
        // red translucent dagger was generated correctly but never selected. Apply it at the shared
        // captured-item boundary instead; the source renderer's matrix still supplies its flight angle.
        itemStack = me.drex.polymerpatcher.entity.render.RenderCaptureRules.item(entity, itemStack);
        Matrix4f transformation = new Matrix4f(matrix4f);
        me.drex.polymerpatcher.entity.render.RenderCaptureRules.itemTransform(
            entity, itemStack, context, transformation);
        ItemDisplayElement element = updateItemDisplayElement(itemStack, transformation);
        element.setItemDisplayContext(context);
    }

    /**
     * The picture a renderer bound for something it drew by hand, or the one already in use.
     * <p>
     * Only a picture a model was actually written under is taken. A renderer binds more than models wear -
     * a glow pass, an overlay - and naming one of those would point every part of that model at a file the
     * pack does not have.
     */
    protected net.minecraft.resources.Identifier boundTexture(
        @org.jetbrains.annotations.Nullable net.minecraft.client.renderer.rendertype.RenderType renderType,
        net.minecraft.resources.Identifier fallback) {
        if (renderType == null) {
            return fallback;
        }

        try {
            var setup = ((me.drex.polymerpatcher.mixin.client.render.RenderTypeAccessor) renderType).getState();
            net.minecraft.resources.Identifier bound = me.drex.polymerpatcher.entity.render.RenderSetups.mainTexture(
                ((me.drex.polymerpatcher.mixin.client.render.RenderSetupAccessor) (Object) setup).polymer_patcher$textures());
            if (bound != null && !bound.equals(fallback)
                && me.drex.polymerpatcher.entity.AnimatedEntities.writtenTextures(
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).contains(bound)) {
                return bound;
            }
        } catch (Throwable e) {
            // The picture it was already wearing is the answer that was right before this existed
        }

        return fallback;
    }

    protected ItemDisplayElement updateItemDisplayElement(ItemStack itemStack, Matrix4f matrix4f) {
        ItemDisplayElement element;
        if (activeElementIndex >= activeElements.size()) {
            element = null;
        } else {
            element = this.activeElements.get(activeElementIndex);
        }

        activeElementIndex++;

        if (element == null) {
            // Show element
            element = this.createItemDisplayElement(itemStack);
            this.addElement(element);
            this.activeElements.add(element);
            this.activeMatrices.add(new Matrix4f(matrix4f));
            activeTeleportEnabled.clear(activeElementIndex - 1);
            element.setTeleportDuration(0);
            element.setTransformation(matrix4f);
            element.startInterpolationIfDirty();
        } else {
            // Update element
            int index = activeElementIndex - 1;
            if (!activeTeleportEnabled.get(index)) {
                element.setTeleportDuration(3);
                activeTeleportEnabled.set(index);
            }
            boolean changed = false;
            if (!ItemStack.matches(element.getItem(), itemStack)) {
                element.setItem(itemStack);
                element.getSyncedData().setDirty(DisplayEntityData.Item.ITEM, true);
                changed = true;
            }
            Matrix4f previous = activeMatrices.get(index);
            if (!previous.equals(matrix4f, 0.00001F)) {
                previous.set(matrix4f);
                element.setTransformation(matrix4f);
                changed = true;
            }
            if (changed) {
                element.startInterpolationIfDirty();
            }
        }
        return element;
    }

    private ItemDisplayElement createItemDisplayElement(ItemStack itemStack) {
        var element = ItemDisplayElementUtil.createSimple(itemStack);
        element.setInterpolationDuration(1);
        element.setTeleportDuration(3);
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        element.setViewRange(modelViewRange(activeElements.size() + 1));
        applyCullBounds(element, dimensions.width(), dimensions.height());
        element.setOffset(new Vec3(0, dimensions.height() / 2, 0));
        return element;
    }

    /**
     * Display entities without a size are deliberately never frustum-culled by vanilla. Every model
     * part used to have that default, so a creature behind the camera still submitted every cube each
     * frame. A generous box around the real entity lets the client discard the whole off-screen model
     * without clipping ordinary wings, tails or animation at the edge of the screen.
     */
    private static void applyCullBounds(ItemDisplayElement element, float width, float height) {
        element.setDisplaySize(Math.max(4.0F, width * 3.0F), Math.max(4.0F, height * 2.0F));
    }

    private float modelViewRange(int partCount) {
        var config = me.drex.polymerpatcher.config.ConfigManager.config().entities;
        float normal = Math.max(0.25F, config.displayViewRange);
        if (partCount >= Math.max(1, config.complexModelPartThreshold)) {
            return Math.min(normal, Math.max(0.25F, config.complexModelViewRange));
        }
        return normal;
    }

    /** Applies the shorter range once a pose crosses the complexity threshold, and restores it too. */
    private void applyModelViewRange() {
        float wanted = modelViewRange(activeElements.size());
        if (Float.compare(wanted, appliedModelViewRange) == 0) {
            return;
        }
        appliedModelViewRange = wanted;
        for (ItemDisplayElement element : activeElements) {
            element.setViewRange(wanted);
        }
    }

    private ItemStack createItemStack(int id, boolean redOverlay, Identifier texture, ModelLayerLocation modelLayer, boolean translucent) {
        return createItemStack(texture.withSuffix("/" + ModelLayerNames.of(modelLayer) + "/part_" + id), redOverlay, translucent);
    }

    /**
     * @param translucent kept on the signature, but no longer acted on here: factorytools used to hand
     *                    back a different base item for a translucent model, and 26.2 collapsed that
     *                    into one lookup, with the item asset the pack generates deciding how the model
     *                    is drawn
     */
    protected ItemStack createItemStack(Identifier modelPath, boolean redOverlay, boolean translucent) {
        ItemStack stack = ItemDisplayElementUtil.getModelCopy(modelPath);
        if (redOverlay) {
            stack.set(DataComponents.MAP_COLOR, new MapItemColor(0xff7e7e));
        } else {
            stack.remove(DataComponents.MAP_COLOR);
        }
        return stack;
    }
}
