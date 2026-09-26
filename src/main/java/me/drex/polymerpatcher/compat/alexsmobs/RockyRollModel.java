package me.drex.polymerpatcher.compat.alexsmobs;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.data.DisplayEntityData;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.entity.citadel.CitadelModelInstance;
import me.drex.polymerpatcher.entity.citadel.CitadelPart;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Replaces a rolling player with Alex's shell model for clients that do not have Alex's Mobs. */
final class RockyRollModel extends ElementHolder {
    private final Player player;
    private final CitadelModelInstance<?, ?, ?> model;
    private final List<ItemDisplayElement> activeElements = new ArrayList<>();
    private int activeElementIndex;

    RockyRollModel(Player player, CitadelModelInstance<?, ?, ?> model) {
        this.player = player;
        this.model = model;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl connection) {
        // A vanilla server cannot know whether the wearer is in first- or third-person. Never send
        // the shell display to the wearer, because it would also fill their first-person camera.
        if (connection.getPlayer() == player || NativeClients.has(connection.getPlayer(), "alexsmobs")) {
            return false;
        }

        boolean started = super.startWatching(connection);
        if (started && !activeElements.isEmpty()) {
            sendVisibility(connection, true);
        }
        return started;
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl connection) {
        boolean stopped = super.stopWatching(connection);
        sendVisibility(connection, false);
        return stopped;
    }

    @Override
    protected void onTick() {
        activeElementIndex = 0;

        EntityDimensions dimensions = player.getDimensions(player.getPose());
        PoseStack poseStack = new PoseStack();
        poseStack.translate(0.0F, -dimensions.height() / 2.0F, 0.0F);
        poseStack.translate(0.0F, dimensions.height() / 2.0F, 0.0F);
        poseStack.mulPose(Axis.YN.rotationDegrees(180.0F + player.yBodyRot));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(player.walkAnimation.position() * 100.0F));

        for (CitadelPart root : model.roots()) {
            root.visit(poseStack, (part, matrix) -> updateElement(
                ItemDisplayElementUtil.getModelCopy(model.modelPath(part.id)), matrix));
        }

        while (activeElements.size() > activeElementIndex) {
            removeElement(activeElements.removeLast());
        }

        // Sprint/crouch changes update the entity flags independently. Reasserting the visual hide
        // for the roll's short lifetime prevents those ordinary updates from revealing the player
        // through the shell halfway through the animation.
        for (ServerGamePacketListenerImpl watcher : getWatchingPlayers()) {
            sendVisibility(watcher, true);
        }

        super.onTick();
    }

    private void updateElement(ItemStack stack, Matrix4f transform) {
        ItemDisplayElement element = activeElementIndex < activeElements.size()
            ? activeElements.get(activeElementIndex)
            : null;
        activeElementIndex++;

        if (element == null) {
            element = ItemDisplayElementUtil.createSimple(stack);
            element.setInterpolationDuration(1);
            element.setTeleportDuration(0);
            element.setViewRange(2);
            element.setOffset(new Vec3(0, player.getDimensions(player.getPose()).height() / 2.0F, 0));
            addElement(element);
            activeElements.add(element);
        } else {
            element.setTeleportDuration(3);
            element.setItem(stack);
            element.getSyncedData().setDirty(DisplayEntityData.Item.ITEM, true);
        }

        element.setTransformation(new Matrix4f(transform));
        element.startInterpolationIfDirty();
    }

    private void sendVisibility(ServerGamePacketListenerImpl connection, boolean hidden) {
        if (connection == null || connection.getPlayer() == player
            || NativeClients.has(connection.getPlayer(), "alexsmobs")) {
            return;
        }

        byte flags = entityFlags(player);
        if (hidden) {
            flags |= 0x20;
        }

        connection.send(new ClientboundSetEntityDataPacket(player.getId(), List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, flags))));
    }

    private static byte entityFlags(Player player) {
        int flags = 0;
        if (player.isOnFire()) flags |= 0x01;
        if (player.isShiftKeyDown()) flags |= 0x02;
        if (player.isSprinting()) flags |= 0x08;
        if (player.isSwimming()) flags |= 0x10;
        if (player.isInvisible()) flags |= 0x20;
        if (player.isCurrentlyGlowing()) flags |= 0x40;
        if (player.isFallFlying()) flags |= 0x80;
        return (byte) flags;
    }
}
