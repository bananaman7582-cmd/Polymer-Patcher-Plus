package me.drex.polymerpatcher.compat.neverend;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.data.DisplayEntityData;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.duck.IModelPart;
import me.drex.polymerpatcher.entity.ModelLayerNames;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import me.drex.polymerpatcher.mixin.client.ModelPartAccessor;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Poses Neverend's suit at the wearer instead of reducing it to an unanimated armour texture. */
final class NeverendDivingSuitModel extends ElementHolder {
    private final ServerPlayer player;
    private final PlayerModel model;
    private final ModelPart root;
    private final ModelLayerLocation layer;
    private final Identifier texture;
    private final AvatarRenderState state = new AvatarRenderState();
    private final List<ItemDisplayElement> activeElements = new ArrayList<>();
    private int activeElementIndex;

    NeverendDivingSuitModel(ServerPlayer player, PlayerModel model, ModelPart root,
                            ModelLayerLocation layer, Identifier texture) {
        this.player = player;
        this.model = model;
        this.root = root;
        this.layer = layer;
        this.texture = texture;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl connection) {
        // A display suit can cross the first-person camera and the real mod client draws its own layer.
        if (connection.getPlayer() == player || NativeClients.carries(connection.getPlayer(), NeverendCompatibility.MOD_ID)) {
            return false;
        }
        return super.startWatching(connection);
    }

    @Override
    protected void onTick() {
        activeElementIndex = 0;
        try {
            extractState();
            model.setupAnim(state);

            PoseStack poseStack = new PoseStack();
            EntityDimensions dimensions = player.getDimensions(player.getPose());
            poseStack.translate(0.0F, -dimensions.height() / 2.0F, 0.0F);
            ServerRenderStates.setupRotations(state, poseStack, state.bodyRot);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            walk(root, poseStack);
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.debug("Could not pose Neverend's diving suit", throwable);
        }

        while (activeElements.size() > activeElementIndex) {
            removeElement(activeElements.removeLast());
        }
        super.onTick();
    }

    private void extractState() {
        ServerRenderStates.applyRotations(player, state);
        state.ageInTicks = player.tickCount;
        state.walkAnimationPos = player.walkAnimation.position();
        state.walkAnimationSpeed = player.walkAnimation.speed();
        state.attackTime = player.getAttackAnim(0.0F);
        state.pose = player.getPose();
        state.scale = 1.0F;
        state.ageScale = 1.0F;
        state.isBaby = false;
        state.speedValue = 1.0F;
        state.mainArm = player.getMainArm();
        state.isCrouching = player.isCrouching();
        state.isFallFlying = player.isFallFlying();
        state.isVisuallySwimming = player.isVisuallySwimming();
        state.isPassenger = player.isPassenger();
        state.isUsingItem = player.isUsingItem();
        state.useItemHand = player.getUsedItemHand();
        state.ticksUsingItem = player.getTicksUsingItem();
        state.swimAmount = player.getSwimAmount(0.0F);
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.isSpectator = false;
    }

    private void walk(ModelPart part, PoseStack poseStack) {
        if (!part.visible) return;
        poseStack.pushPose();
        part.translateAndRotate(poseStack);

        if (!part.skipDraw && !part.isEmpty()) {
            int id = ((IModelPart) (Object) part).polymer_patcher$getId();
            if (id >= 0) {
                Identifier modelPath = texture.withSuffix("/" + ModelLayerNames.of(layer) + "/part_" + id);
                update(ItemDisplayElementUtil.getModelCopy(modelPath), new Matrix4f(poseStack.last().pose()));
            }
        }

        for (ModelPart child : ((ModelPartAccessor) (Object) part).getChildren().values()) {
            walk(child, poseStack);
        }
        poseStack.popPose();
    }

    private void update(ItemStack stack, Matrix4f transform) {
        ItemDisplayElement element = activeElementIndex < activeElements.size()
            ? activeElements.get(activeElementIndex) : null;
        activeElementIndex++;

        if (element == null) {
            element = ItemDisplayElementUtil.createSimple(stack);
            element.setInterpolationDuration(1);
            element.setTeleportDuration(0);
            element.setViewRange(2.0F);
            element.setDisplaySize(2.0F, 3.0F);
            element.setOffset(new Vec3(0.0, player.getDimensions(player.getPose()).height() / 2.0, 0.0));
            addElement(element);
            activeElements.add(element);
        } else {
            element.setTeleportDuration(3);
            if (!ItemStack.matches(element.getItem(), stack)) {
                element.setItem(stack);
                element.getSyncedData().setDirty(DisplayEntityData.Item.ITEM, true);
            }
        }
        element.setTransformation(transform);
        element.startInterpolationIfDirty();
    }
}
