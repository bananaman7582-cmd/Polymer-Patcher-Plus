package me.drex.polymerpatcher.mixin.sync;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.VanillaRegistries;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.lang.reflect.Field;

@Mixin(targets = "net/minecraft/network/protocol/game/ClientboundCommandsPacket$ArgumentNodeStub")
public abstract class ClientboundCommandsPacketMixin {
    @ModifyArg(
        method = "write",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket$ArgumentNodeStub;serializeCap(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/commands/synchronization/ArgumentTypeInfo$Template;)V"
        ),
        index = 1
    )
    public ArgumentTypeInfo.Template<?> vanillaify(ArgumentTypeInfo.Template<?> argumentType) {
        // An argument can name a registry the client has never heard of while wearing a perfectly
        // vanilla argument type, which the namespace check below cannot see: Spell Engine reads its
        // spells through minecraft:resource, and a client without Spell Engine throws looking the
        // registry up - ending the connection over a command it was never going to run. Read what
        // the argument is actually for first, and write it as text when a plain client cannot read it.
        ResourceKey<?> registry = polymerPatcher$registryOf(argumentType);
        if (registry != null && !VanillaRegistries.everyClientHas(registry)) {
            return ArgumentTypeInfos.unpack(StringArgumentType.greedyString());
        }

        Identifier id = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(argumentType.type());
        assert id != null;
        if (!PolymerPatcher.PATCHED_MODS.contains(id.getNamespace())) return argumentType;

        return ArgumentTypeInfos.unpack(StringArgumentType.greedyString());
    }

    /**
     * The registry an argument reads from, or null for an argument that reads from none.
     * <p>
     * Every argument backed by a registry keeps its key in a field of exactly that type, across the
     * whole family of them - found by type rather than by name, since the name is not the same on a
     * server as it is where this mod was built.
     */
    private static ResourceKey<?> polymerPatcher$registryOf(ArgumentTypeInfo.Template<?> template) {
        for (Field field : template.getClass().getDeclaredFields()) {
            if (field.getType() != ResourceKey.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(template) instanceof ResourceKey<?> key) {
                    return key;
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not read which registry a {} argument reads from",
                    template.getClass().getName(), e);
            }
        }
        return null;
    }
}
