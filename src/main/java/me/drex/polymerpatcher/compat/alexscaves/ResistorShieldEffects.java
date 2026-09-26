package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gives the resistor shield back the noise and the glow it makes, for people without the mod.
 * <p>
 * What the shield actually <em>does</em> works already, and is worth saying plainly: the pull and push
 * are a hurt and a knockback applied on the server, and switching polarity is read from whether the
 * player is crouching on the server. None of that needs a client to agree with it.
 * <p>
 * Everything you can see or hear of it does, though. The ring of sparks around the wielder is drawn
 * with {@code Level.addParticle}, which does nothing at all when called on a server, and the hum while
 * it is raised is a looping sound the mod starts on the client. Both sit behind a client-side check,
 * so a stranger gets a shield that silently and invisibly throws mobs around.
 * <p>
 * So the ring and the hum are produced here instead - the sparks as the nearest vanilla particles, red
 * for scarlet and blue for azure, and the mod's own loop played outright. The radius is the five blocks
 * the shield itself reaches, so what you see is what it affects.
 */
public final class ResistorShieldEffects {

    private ResistorShieldEffects() {
    }

    private static final Identifier SHIELD = Identifier.fromNamespaceAndPath("alexscaves", "resistor_shield");
    private static final Identifier AZURE_LOOP = Identifier.fromNamespaceAndPath("alexscaves", "resistor_shield_azure_loop");
    private static final Identifier SCARLET_LOOP = Identifier.fromNamespaceAndPath("alexscaves", "resistor_shield_scarlet_loop");
    private static final Identifier SPIN = Identifier.fromNamespaceAndPath("alexscaves", "resistor_shield_spin");
    private static final Identifier SLAM = Identifier.fromNamespaceAndPath("alexscaves", "resistor_shield_slam");

    /** How often the hum is started again, in ticks. */
    private static final int HUM_EVERY = 40;

    /** Which way each player's shield was pointing last tick, so a switch can be noticed. */
    private static final Map<UUID, Boolean> LAST_POLARITY = new HashMap<>();

    /** Previous use time, so the client-only slam can be reproduced exactly once at tick ten. */
    private static final Map<UUID, Integer> LAST_USE_TIME = new HashMap<>();

    private static Method isScarlet;
    private static boolean looked;

    public static void init() {
        // A few instant-fire modded bows perform their shot but return PASS. Vanilla consequently
        // tries the off hand as well, and an off-hand shield starts blocking in the very same click.
        // A bow has priority over an off-hand shield just as a vanilla bow does; stop only that narrow
        // semantic combination, and only for a client which needs this compatibility layer.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || hand != InteractionHand.OFF_HAND
                || !isResistorShield(player.getOffhandItem())
                || !isRanged(player.getMainHandItem())
                || !NativeClients.settled(serverPlayer)
                || NativeClients.carries(serverPlayer, "alexscaves")) {
                return InteractionResult.PASS;
            }
            return InteractionResult.FAIL;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    tick(player, server.getTickCount());
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not draw a resistor shield's ring", e);
                }
            }
        });
    }

    private static void tick(ServerPlayer player, int tickCount) {
        ItemStack held = player.getUseItem();
        boolean raised = player.isUsingItem() && isResistorShield(held);

        if (!raised) {
            LAST_POLARITY.remove(player.getUUID());
            LAST_USE_TIME.remove(player.getUUID());
            return;
        }

        int useTime = player.getTicksUsingItem();
        Integer previousUseTime = LAST_USE_TIME.put(player.getUUID(), useTime);
        if (useTime >= 10 && (previousUseTime == null || previousUseTime < 10)
            && (!NativeClients.settled(player) || !NativeClients.carries(player, "alexscaves"))) {
            // Alex's Caves plays this only on its client. A vanilla client never runs that branch.
            play(player, SLAM, 1.0F);
        }

        boolean scarlet = isScarlet(held);

        // A switch is worth hearing, and the mod's own sound for it never reaches the person who did
        // it: it is played through the player, which on a server means everyone except them
        Boolean before = LAST_POLARITY.put(player.getUUID(), scarlet);
        if (before != null && before != scarlet) {
            play(player, SPIN, 1.0F);
        }

        if (tickCount % HUM_EVERY == 0) {
            play(player, scarlet ? SCARLET_LOOP : AZURE_LOOP, 0.8F);
        }

        // This is the cadence and geometry of ResistorShieldItem.onUseTick: after the slam, five to
        // nine full lightning cracks radiate through the whole five-block field every five ticks.
        if (useTime >= 10 && useTime % 5 == 0) {
            MagneticCrackEffects.drawShield((ServerLevel) player.level(),
                player.position().add(0, 0.2D, 0), scarlet, player.getRandom());
        }
    }

    private static boolean isResistorShield(ItemStack stack) {
        return !stack.isEmpty() && SHIELD.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static boolean isRanged(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemUseAnimation animation = stack.getUseAnimation();
        return animation == ItemUseAnimation.BOW || animation == ItemUseAnimation.CROSSBOW;
    }

    /** Authoritative interaction guard used before either hand is allowed to consume the click. */
    public static boolean suppressOffhandUse(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        return hand == InteractionHand.OFF_HAND
            && isResistorShield(stack)
            && isRanged(player.getMainHandItem())
            && NativeClients.settled(player)
            && !NativeClients.carries(player, "alexscaves");
    }

    /** Which way the shield is set, asked of the mod, or scarlet when it will not say. */
    private static boolean isScarlet(ItemStack stack) {
        if (!looked) {
            looked = true;
            try {
                isScarlet = stack.getItem().getClass().getMethod("isScarlet", ItemStack.class);
                isScarlet.setAccessible(true);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("A resistor shield would not say which way it is set", e);
            }
        }
        if (isScarlet != null) {
            try {
                return Boolean.TRUE.equals(isScarlet.invoke(null, stack));
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static void play(ServerPlayer player, Identifier sound, float volume) {
        Holder.Reference<SoundEvent> event = find(sound);
        if (event == null) {
            return;
        }
        // To everyone nearby including the wielder, which is the part the mod's own call leaves out
        ((ServerLevel) player.level()).playSound(null, player.getX(), player.getY(), player.getZ(),
            event.value(), SoundSource.PLAYERS, volume, 1.0F);
    }

    @Nullable
    private static Holder.Reference<SoundEvent> find(Identifier sound) {
        return BuiltInRegistries.SOUND_EVENT.get(sound).orElse(null);
    }
}
