package me.drex.polymerpatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import me.drex.polymerpatcher.effect.ModdedEffectNotices;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Says what a player is currently under the effect of, in words.
 * <p>
 * A vanilla client draws the effect icons down the side of the inventory from a list it already has, so
 * an effect it has never heard of has no icon to draw and no name to show - it simply is not there. The
 * effect is real and the server is applying it; there is just no way to find out that it is, which is a
 * poor thing to discover by wondering why you keep dying.
 * <p>
 * The notice when one is gained already exists, but a notice is gone in a moment and says nothing about
 * what else is running or how long any of it has left. This answers on demand instead.
 * <p>
 * Everything is listed, not only the modded ones. A player asking what they are under the effect of
 * wants the answer, not a quiz on which half of it their client could already draw.
 */
public final class EffectsCommand {
    private EffectsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("effects").executes(ctx -> show(ctx.getSource().getPlayerOrException())));
        // The name the effect icons go by in the inventory, for anyone who reaches for that first
        dispatcher.register(Commands.literal("effect-list").executes(ctx -> show(ctx.getSource().getPlayerOrException())));
    }

    private static int show(ServerPlayer player) {
        List<MobEffectInstance> active = new ArrayList<>(player.getActiveEffects());
        if (active.isEmpty()) {
            player.sendSystemMessage(Component.literal("You have no effects.").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        // Longest-lasting last, so the ones about to run out are the ones nearest the hotbar
        active.sort(Comparator.comparingInt(MobEffectInstance::getDuration));

        player.sendSystemMessage(Component.literal("You have " + active.size() + (active.size() == 1 ? " effect:" : " effects:"))
            .withStyle(ChatFormatting.GRAY));

        for (MobEffectInstance instance : active) {
            player.sendSystemMessage(describe(instance));
        }
        return active.size();
    }

    private static Component describe(MobEffectInstance instance) {
        StringBuilder line = new StringBuilder("  ");
        line.append(ModdedEffectNotices.displayName(instance.getEffect()));

        if (instance.getAmplifier() > 0) {
            // Plain numbers rather than roman numerals: the point of this is to be read
            line.append(" ").append(instance.getAmplifier() + 1);
        }

        line.append(" - ").append(instance.isInfiniteDuration() ? "does not run out" : remaining(instance.getDuration()));

        boolean harmful = instance.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL;
        return Component.literal(line.toString()).withStyle(harmful ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    private static String remaining(int ticks) {
        int seconds = Math.max(ticks, 0) / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s left";
        }
        return seconds + "s left";
    }
}
