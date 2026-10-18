package me.drex.polymerpatcher.compat.witherstorm;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Wither Storm mod's own packets, replayed with vanilla ones for a player who does not have the mod.
 * <p>
 * The tractor beam is the one that matters. The storm works the pull out on the server, but a player's
 * movement is the player's own client's to decide, so the mod sends the pull as a packet of its own and
 * its client applies it. A client without the mod never got it and simply walked away from the beam.
 * The same motion is sent here as velocity - the packet behind every knockback - which is the one way a
 * server can move a vanilla client and have it stick. The symbiont's pull and pulse spells and the
 * command block's shove go the same way.
 * <p>
 * A player with the mod is never handed any of this: their own client already applies the real packet,
 * and a second copy of the motion would double it.
 */
public final class WitherStormPackets {
    public static final String MOD_ID = "witherstormmod";

    private static final Map<String, Method> GETTERS = new ConcurrentHashMap<>();

    private WitherStormPackets() {
    }

    public static void handle(ServerPlayer player, CustomPacketPayload packet) {
        try {
            switch (packet.getClass().getSimpleName()) {
                case "PlayerMotionMessage" -> move(player, (Vec3) get(packet, "getMotion"));
                case "GlobalSoundMessage" -> sound(player, (SoundEvent) get(packet, "getSoundEvent"),
                    (float) get(packet, "getVolume"), (float) get(packet, "getPitch"));
                default -> {
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not pass {} on to {} with vanilla packets", packet.getClass().getSimpleName(), player.getName().getString(), e);
        }
    }

    private static void move(ServerPlayer player, Vec3 motion) {
        if (motion == null) {
            return;
        }
        // As the mod's client does: a rider is carried by what it is riding, and a ridden horse or boat is
        // moved by the rider's own client, so that is the one told
        Entity moved = player.isPassenger() && player.getVehicle() != null ? player.getVehicle() : player;
        player.connection.send(new ClientboundSetEntityMotionPacket(moved.getId(), motion));
    }

    private static void sound(ServerPlayer player, SoundEvent event, float volume, float pitch) {
        if (event == null) {
            return;
        }
        // Heard wherever the player is, which is what a global sound means
        player.connection.send(new ClientboundSoundPacket(Holder.direct(event), SoundSource.HOSTILE,
            player.getX(), player.getEyeY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    private static Object get(Object packet, String name) throws ReflectiveOperationException {
        Method method = GETTERS.get(packet.getClass().getName() + "#" + name);
        if (method == null) {
            method = packet.getClass().getMethod(name);
            GETTERS.put(packet.getClass().getName() + "#" + name, method);
        }
        return method.invoke(packet);
    }
}
