package me.drex.polymerpatcher.mixin.sync;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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
        Identifier id = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(argumentType.type());
        assert id != null;
        if (!PolymerPatcher.PATCHED_MODS.contains(id.getNamespace())) return argumentType;

        return ArgumentTypeInfos.unpack(StringArgumentType.greedyString());
    }
}
