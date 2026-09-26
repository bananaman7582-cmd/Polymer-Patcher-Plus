package me.drex.polymerpatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Says which of this server's mods a player's client actually has.
 * <p>
 * Everything this mod does to a packet turns on that one answer, and it is worked out from what a
 * client says about itself while it joins rather than from anything anybody typed. When a player
 * reports that something looks wrong, the first useful question is which half of the code they are
 * being shown - and until now the only way to find out was to read the log for the line written when
 * they connected. This asks the same thing that every other part of the mod asks, in the same way.
 *
 * @see NativeClients
 */
public final class ModsCheckCommand {

    private ModsCheckCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("mods-check")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> report(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> report(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
    }

    private static int report(CommandSourceStack source, ServerPlayer player) {
        Set<String> patched = new TreeSet<>(PolymerPatcher.PATCHED_MODS);
        Set<String> has = new TreeSet<>();
        Set<String> hasNot = new TreeSet<>();
        for (String mod : patched) {
            (NativeClients.carries(player, mod) ? has : hasNot).add(mod);
        }

        String name = player.getGameProfile().name();
        MutableComponent out = Component.literal(name + " has " + has.size() + " of the " + patched.size()
            + " mods this server patches").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);

        out.append(list("\n  has: ", has, ChatFormatting.GREEN));
        out.append(list("\n  has not: ", hasNot, ChatFormatting.GRAY));

        // The two things that are decided from the same answer but are not the same question
        out.append(Component.literal("\n  shown the real mobs of: ")
            .append(shown(player, has)).withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("\n  numbers its entity fields as this server does: "
                + (NativeClients.hasAll(player) ? "yes" : "no"))
            .withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("\n  answer settled: " + yesNo(NativeClients.settled(player))
                + ", registries reconciled: " + yesNo(NativeClients.registriesReconciled(player)))
            .withStyle(ChatFormatting.DARK_GRAY));

        source.sendSuccess(() -> out, false);
        return has.size();
    }

    /**
     * Which of the mods they have they are actually being sent for real, which is the setting's business
     * rather than the client's: turn native clients off and everybody sees the stand-ins.
     */
    private static Component shown(ServerPlayer player, Set<String> has) {
        List<String> real = has.stream().filter(mod -> NativeClients.has(player, mod)).toList();
        return Component.literal(real.isEmpty() ? "none - everything is a stand-in" : String.join(", ", real));
    }

    private static Component list(String label, Set<String> mods, ChatFormatting colour) {
        return Component.literal(label + (mods.isEmpty() ? "nothing" : String.join(", ", mods))).withStyle(colour);
    }

    private static String yesNo(boolean yes) {
        return yes ? "yes" : "no";
    }
}
