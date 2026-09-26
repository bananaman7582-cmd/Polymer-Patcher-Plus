package me.drex.polymerpatcher.dump.command;

import com.mojang.brigadier.CommandDispatcher;
import me.drex.polymerpatcher.dump.AutoDump;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/**
 * Takes the dump on request.
 * <p>
 * {@link AutoDump} takes one by itself whenever the mods have changed, so this is not normally needed.
 * It stays for the times that reasoning is wrong - a mod updated in place, a dump edited or lost - when
 * the answer is to take another regardless of what is already on file.
 */
public class DumpCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("pp-dump")
                .executes(commandContext -> {
                    ServerLevel level = commandContext.getSource().getLevel();
                    return AutoDump.run(level, true) ? 1 : 0;
                })
        );
    }
}
