package me.drex.polymerpatcher.effect;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.util.ModdedNames;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Says out loud which modded effect a player just got, and when it wears off.
 * <p>
 * A modded effect reaches a stranger as some vanilla one - it has to, because the icon in the corner
 * is drawn from a list the client already has. What is lost is which effect it actually was: the
 * player sees a random vanilla icon appear and has no way of telling whether they have been irradiated,
 * frozen, or something else entirely, nor when it ends.
 * <p>
 * So the name is sent as words instead. Not in chat, which is where a player is reading other things,
 * but above the hotbar where the game puts its own short-lived notices - and the name is looked up in
 * the mod's own English file, so it arrives as "Irradiated" rather than as a translation key nobody
 * without the mod can read.
 */
public final class ModdedEffectNotices {

    private ModdedEffectNotices() {
    }

    /** What each player had last time we looked, so only the changes are mentioned. */
    private static final Map<UUID, Set<Holder<MobEffect>>> LAST_SEEN = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ConfigManager.config().effects.announceModdedEffects) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    check(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not look at {}'s effects", player.getGameProfile().name(), e);
                }
            }
        });
    }

    private static void check(ServerPlayer player) {
        Set<Holder<MobEffect>> now = new HashSet<>();
        for (MobEffectInstance instance : player.getActiveEffects()) {
            Holder<MobEffect> effect = instance.getEffect();
            if (isModded(effect)) {
                now.add(effect);
            }
        }

        Set<Holder<MobEffect>> before = LAST_SEEN.get(player.getUUID());
        if (before == null) {
            // First look at this player: whatever they already have is not news
            LAST_SEEN.put(player.getUUID(), now);
            return;
        }

        for (Holder<MobEffect> effect : now) {
            if (!before.contains(effect)) {
                // The name on its own read as a label rather than as news - it was not obvious the
                // effect had just been gained, or that it was an effect at all
                say(player, Component.literal("You now have the modded effect " + nameOf(effect))
                    .withStyle(ChatFormatting.AQUA));
            }
        }
        for (Holder<MobEffect> effect : before) {
            if (!now.contains(effect)) {
                say(player, Component.literal(nameOf(effect) + " has worn off").withStyle(ChatFormatting.GRAY));
            }
        }

        LAST_SEEN.put(player.getUUID(), now);
    }

    /** Forgotten on the way out, so a returning player is not told about effects they still had. */
    public static void forget(ServerPlayer player) {
        LAST_SEEN.remove(player.getUUID());
    }

    private static void say(ServerPlayer player, Component message) {
        // Above the hotbar rather than in chat: this is a short-lived notice about the player's own
        // state, and chat is where they are reading other things
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(message));
    }

    private static boolean isModded(Holder<MobEffect> effect) {
        Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
        return id != null && !ModdedNames.isTheGamesOwn(id.getNamespace());
    }

    /** The effect's name, as a player should read it. */
    public static String displayName(Holder<MobEffect> effect) {
        return nameOf(effect);
    }

    private static String nameOf(Holder<MobEffect> effect) {
        Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
        if (id == null) {
            return "Unknown effect";
        }
        return ModdedNames.of(id.getNamespace(), "effect." + id.getNamespace() + "." + id.getPath());
    }
}
