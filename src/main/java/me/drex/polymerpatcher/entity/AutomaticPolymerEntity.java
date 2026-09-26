package me.drex.polymerpatcher.entity;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.other.PlayerBoundConsumer;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.VirtualEntityUtils;
import eu.pb4.polymer.virtualentity.api.attachment.IdentifiedUniqueEntityAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.UniqueIdentifiableAttachment;
import eu.pb4.polymer.virtualentity.api.data.DisplayEntityData;
import it.unimi.dsi.fastutil.ints.IntList;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public record AutomaticPolymerEntity<T extends Entity>(T entity) implements PolymerEntity {
    public static final Identifier MODEL = PolymerPatcher.id("model");

    /** Where a stand-in for an entity with no model is kept, when one is being shown at all. */
    public static final Identifier PLACEHOLDER = PolymerPatcher.id("placeholder");

    public AutomaticPolymerEntity {
        Function<Entity, ? extends SimpleEntityModel<?, ?, ?>> factory = (Function<Entity, ? extends SimpleEntityModel<?, ?, ?>>) AnimatedEntities.ENTITY_FACTORIES.get(entity.getType());
        if (factory != null) {
            SimpleEntityModel<?, ?, ?> holder = factory.apply(entity);
            IdentifiedUniqueEntityAttachment.ofTicking(MODEL, holder, entity);
        } else if (!(entity instanceof net.minecraft.world.entity.LivingEntity) && !entity.isPickable()) {
            // Nothing: a thing that is not a creature and cannot be pointed at is drawn by its mod as
            // nothing at all - Sculk Horde's cursors, which crawl the ground spreading sculk, a cloud, a
            // marker. A placeholder made them red boxes crawling about, and the box beside it caught
            // clicks meant for the blocks behind them, which nobody with the mod can even aim at
        } else {
            // Kept on its own attachment rather than sharing MODEL, because everything that reads MODEL
            // expects to find a real model there and casts to one.
            //
            // Something is always attached here, even with placeholders turned off. A mob with no model
            // reaches a vanilla client as an item display and nothing else, and an item display has no
            // size: the mob was not merely invisible, it could not be pointed at, hit, or named, and a
            // sword swung through it went into the air behind. The box is the part worth having whether
            // or not the red placeholder is wanted, so the two are no longer the same choice
            ElementHolder holder = ConfigManager.config().entities.showPlaceholders
                ? new PlaceholderModel(entity)
                : new HitboxModel(entity);
            IdentifiedUniqueEntityAttachment.ofTicking(PLACEHOLDER, holder, entity);
        }
    }

    /**
     * Every player is shown the stand-in, including one who has the mod themselves.
     * <p>
     * Sending the real type to a player who could draw it was the obvious thing to do and does not
     * work. An entity type travels as a number, and the number only means the same thing at both ends
     * while both registries agree - which they do not here, because
     * {@link me.drex.polymerpatcher.registry.RegistryPatcher} hands every modded type to Polymer as an
     * overlay, and Polymer's registry sync then rewrites what those numbers resolve to on the client.
     * A player with the mod was therefore told "here is a raccoon" and built a marker: an entity with
     * the eight fields every entity has and room for nothing else. The next update, carrying the
     * raccoon's own fields, then ran off the end of it and took the player's connection with it.
     * <p>
     * Which is why this is not a matter of getting the check right. As long as the type is registered
     * as an overlay, the only number that means the same thing at both ends is a vanilla one, and the
     * stand-in is the honest answer for everybody.
     */
    @Override
    public EntityType<?> getPolymerEntityType(PacketContext packetContext) {
        // Answered from the same decision the registry sync used, so the number in this packet still
        // means what the client was told it means
        if (NativeClients.has(PolymerCommonUtils.getPlayer(packetContext), entityNamespace())) {
            return entity.getType();
        }
        EntityType<?> disguise = BorrowedEchoCompat.clientCarrier(entity);
        if (disguise != null) {
            return disguise;
        }
        if (entity instanceof AbstractBoat) {
            // TODO Ensure acacia boat gets custom model
            return EntityTypes.ACACIA_BOAT;
        }
        return EntityTypes.ITEM_DISPLAY;
    }

    private String entityNamespace() {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null ? id.getNamespace() : Identifier.DEFAULT_NAMESPACE;
    }

    @Override
    public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data, ServerPlayer player, boolean initial) {
        PolymerEntity.super.modifyRawTrackedData(data, player, initial);
        // A Borrowed Echo shown as a real cow/player has that carrier's data layout, not a display's.
        // Adding a DisplayEntity field there is not cosmetic: it lands on an unrelated creature field
        // and can disconnect the client when the serializers differ.
        if (initial && !BorrowedEchoCompat.usesVanillaCarrier(entity)) {
            data.add(SynchedEntityData.DataValue.create(DisplayEntityData.TELEPORTATION_DURATION, 3));
        }
    }

    /**
     * Whether everyone this packet is going to is being shown the real mob rather than a stand-in.
     * <p>
     * A player who has the mod is never sent the stand-in, so its pieces do not exist on their side.
     * Rewriting a packet to point at one told them they were riding an entity they had never been
     * given - and a rider whose vehicle cannot be found stops being moved with it, which is a player
     * held by a crocodile watching themselves drift away from their own body.
     */
    private boolean allNative(Consumer<Packet<?>> consumer) {
        // Borrowed Echo's stable disguises are also real client-side entities: vanilla players see a
        // vanilla creature/player carrier while modded players see the original entity. Neither side
        // has virtual ride/leash pieces to redirect packets to.
        if (BorrowedEchoCompat.usesVanillaCarrier(entity)) {
            return true;
        }
        if (!(consumer instanceof PlayerBoundConsumer<?> bound) || bound.receivers().isEmpty()) {
            return false;
        }

        String namespace = entityNamespace();
        for (ServerPlayerConnection connection : bound.receivers()) {
            if (!NativeClients.has(connection.getPlayer(), namespace)) {
                return false;
            }
        }

        return true;
    }

    /**
     * The virtual model standing in for this entity, or null when it has none.
     * <p>
     * An entity only gets one when {@link AnimatedEntities} found a renderer for its type, which is a
     * thing that can simply not happen - no dump, a dump taken without this mod, a renderer that would
     * not build, or the whole set-up failing and the server carrying on without it. Everything below
     * that reaches for a model has to cope with its absence, or the first mob to be leashed or ridden
     * takes the server down with it.
     */
    private SimpleEntityModel<?, ?, ?> model() {
        var attachment = UniqueIdentifiableAttachment.get(entity, MODEL);
        return attachment != null ? (SimpleEntityModel<?, ?, ?>) attachment.holder() : null;
    }

    /**
     * Entity updates are deliberately NOT dropped here.
     * <p>
     * They used to be, on the reasoning that an item display has none of a mob's fields anyway. But
     * Polymer already trims every update down to what the stand-in type can legally carry, and the
     * working server-side mods that do this - Enderscape's patch among them - let it through for
     * exactly that reason. Dropping it threw away the one entry this class adds in
     * {@link #modifyRawTrackedData}, the interpolation duration, so the displays standing in for a mob
     * were never told to interpolate at all.
     */
    @Override
    public void onEntityPacketSent(Consumer<Packet<?>> consumer, Packet<?> packet) {
        // Remove the wire entity before its hidden player-list/team bookkeeping. Doing this in the
        // opposite order can leave a player carrier alive for one packet with no PlayerInfo behind it,
        // which makes the client briefly fall back to the wrong skin.
        if (packet instanceof ClientboundRemoveEntitiesPacket) {
            PolymerEntity.super.onEntityPacketSent(consumer, packet);
            BorrowedEchoCompat.aroundEntityPacket(entity, consumer, packet);
            return;
        }
        BorrowedEchoCompat.aroundEntityPacket(entity, consumer, packet);
        if (packet instanceof ClientboundEntityEventPacket event && !allNative(consumer)
            && EntityEventEmulator.emulate(this.entity, event.getEventId())) {
            return;
        }
        // A player being shown the real mob draws its swings and hurt animations themselves
        if (packet instanceof ClientboundAnimatePacket && !allNative(consumer)) {
            return;
        }

        // An item display has no attributes at all, and Polymer filters a mob's attributes against
        // whatever type it is standing in as - which for an item display means looking up a container
        // that does not exist, and throwing while encoding the packet. That kills the connection, so
        // the packet is not worth sending to anyone still being shown the stand-in
        if (packet instanceof ClientboundUpdateAttributesPacket && !allNative(consumer)) {
            return;
        }
        if (packet instanceof ClientboundSetPassengersPacket packet1) {
            var model = model();
            if (model == null || allNative(consumer)) {
                PolymerEntity.super.onEntityPacketSent(consumer, packet);
                return;
            }

            // The interaction element is only the clickable hitbox. Mounting a player on it put the
            // camera at the entity's centre, which is inside the hull of a submarine (and inside many
            // other large mobs). FactoryTools provides an invisible, zero-scale living entity for this
            // exact job. It follows the real server-side passenger position in SimpleEntityModel, so
            // every vehicle gets its own seat without a per-mod offset table.
            //
            // Clear both old relationships as well. The empty packet matters on dismount, and clearing
            // the interaction element repairs clients which saw an older build mount them there.
            consumer.accept(VirtualEntityUtils.createClientboundSetPassengersPacket(entity.getId(), IntList.of()));
            consumer.accept(VirtualEntityUtils.createClientboundSetPassengersPacket(model.interaction.getEntityId(), IntList.of()));
            consumer.accept(VirtualEntityUtils.createClientboundSetPassengersPacket(model.rideAttachment.getEntityId(), packet1.getPassengers()));
            return;
        }

        if (packet instanceof ClientboundSetEntityLinkPacket packet1) {
            var model = model();
            if (model == null || allNative(consumer)) {
                PolymerEntity.super.onEntityPacketSent(consumer, packet);
                return;
            }
            consumer.accept(VirtualEntityUtils.createClientboundSetEntityLinkPacket(model.leadAttachment.getEntityId(), packet1.getDestId()));
            return;
        }
        PolymerEntity.super.onEntityPacketSent(consumer, packet);
    }
}
