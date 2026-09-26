package me.drex.polymerpatcher.mixin.enderscape;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import eu.pb4.factorytools.mixin.LivingEntityAccessor;
import io.netty.channel.ChannelFutureListener;
import me.drex.polymerpatcher.compat.enderscape.EnderscapePacketHandler;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.manager.EndHavenManager;
import net.penumbra.enderscape.manager.VoidManager;
import net.penumbra.enderscape.registry.entity.EnderscapeMobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player {
    @Shadow public ServerGamePacketListenerImpl connection;
    @Shadow public abstract ServerLevel level();

    @Unique private float polymerPatcher$previousVoidDamage;
    @Unique private boolean polymerPatcher$stunned;

    protected ServerPlayerMixin(Level level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymerPatcher$syncClientVisibleAttributes(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (this.isDeadOrDying() || NativeClients.has(player, "enderscape")) {
            return;
        }

        float voidDamage = VoidManager.getVoidedHealth(this);
        List<AttributeInstance> updates = new ArrayList<>();
        if (voidDamage != this.polymerPatcher$previousVoidDamage) {
            this.polymerPatcher$previousVoidDamage = voidDamage;
            AttributeInstance maxHealth = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> { });
            maxHealth.setBaseValue(this.getMaxHealth() - voidDamage);
            updates.add(maxHealth);
        }

        boolean stunned = EnderscapeMobEffects.isStunned(this);
        if (stunned != this.polymerPatcher$stunned) {
            for (var attribute : List.of(Attributes.MOVEMENT_SPEED, Attributes.SNEAKING_SPEED, Attributes.JUMP_STRENGTH)) {
                AttributeInstance instance = new AttributeInstance(attribute, ignored -> { });
                instance.setBaseValue(stunned ? 0 : this.getAttributeValue(attribute));
                updates.add(instance);
            }
            this.polymerPatcher$stunned = stunned;
        }

        if (stunned) {
            this.closeContainer();
        }
        if (!updates.isEmpty()) {
            this.connection.send(new ClientboundUpdateAttributesPacket(this.getId(), updates));
        }
    }

    @Unique
    private void polymerPatcher$restoreAttributes() {
        List<AttributeInstance> updates = new ArrayList<>();
        if (this.polymerPatcher$previousVoidDamage != 0) {
            AttributeInstance maxHealth = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> { });
            maxHealth.setBaseValue(this.getMaxHealth());
            updates.add(maxHealth);
            this.polymerPatcher$previousVoidDamage = 0;
        }
        if (this.polymerPatcher$stunned) {
            for (var attribute : List.of(Attributes.MOVEMENT_SPEED, Attributes.SNEAKING_SPEED, Attributes.JUMP_STRENGTH)) {
                AttributeInstance instance = new AttributeInstance(attribute, ignored -> { });
                instance.setBaseValue(this.getAttributeValue(attribute));
                updates.add(instance);
            }
            this.polymerPatcher$stunned = false;
        }
        if (!updates.isEmpty()) {
            this.connection.send(new ClientboundUpdateAttributesPacket(this.getId(), updates));
        }
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void polymerPatcher$showEndHavenChoice(ServerGamePacketListenerImpl connection, Packet<?> packet,
                                                   Operation<Void> original) {
        original.call(connection, packet);
        this.polymerPatcher$afterDeathPacket(packet);
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V"))
    private void polymerPatcher$showEndHavenChoice(ServerGamePacketListenerImpl connection, Packet<?> packet,
                                                   ChannelFutureListener listener, Operation<Void> original) {
        original.call(connection, packet, listener);
        this.polymerPatcher$afterDeathPacket(packet);
    }

    @Unique
    private void polymerPatcher$afterDeathPacket(Packet<?> packet) {
        this.polymerPatcher$restoreAttributes();
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (NativeClients.has(player, "enderscape")) {
            return;
        }
        if (packet instanceof ClientboundPlayerCombatKillPacket kill
            && kill.playerId() == this.getId() && EndHavenManager.promptHavenRespawnChoice(this)) {
            this.polymerPatcher$sendEndHavenDialog(kill.message());
        }
    }

    @Unique
    private void polymerPatcher$sendEndHavenDialog(Component deathMessage) {
        boolean hardcore = this.level().getServer().isHardcore();
        this.connection.send(new ClientboundSetEntityDataPacket(this.getId(), List.of(
            SynchedEntityData.DataValue.create(LivingEntityAccessor.getDATA_HEALTH_ID(), 0.01F))));

        Component body = Component.empty().append(deathMessage).append("\n\n")
            .append(Component.translatable("deathScreen.score.value",
                Component.literal(Integer.toString(this.getScore())).withStyle(ChatFormatting.YELLOW)));
        this.connection.send(new ClientboundShowDialogPacket(Holder.direct(new MultiActionDialog(
            new CommonDialogData(
                Component.translatable(hardcore ? "deathScreen.title.hardcore" : "deathScreen.title").withStyle(ChatFormatting.BOLD),
                Optional.empty(), false, false, DialogAction.NONE,
                List.of(new PlainMessage(body, 240)), List.of()),
            List.of(
                polymerPatcher$button(hardcore ? "screen.enderscape.death.spectate_from_end_haven"
                    : "screen.enderscape.death.respawn_from_end_haven", EnderscapePacketHandler.END_HAVEN_RESPAWN_ACTION),
                polymerPatcher$button(hardcore ? "deathScreen.spectate" : "deathScreen.respawn",
                    EnderscapePacketHandler.RESPAWN_ACTION),
                polymerPatcher$button("deathScreen.titleScreen", EnderscapePacketHandler.DISCONNECT_ACTION)),
            Optional.empty(), 1))));
    }

    @Unique
    private static ActionButton polymerPatcher$button(String translationKey,
                                                       net.minecraft.resources.Identifier action) {
        return new ActionButton(new CommonButtonData(Component.translatable(translationKey), 200),
            Optional.of(new StaticAction(new ClickEvent.Custom(action, Optional.empty()))));
    }
}
