package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface ClientboundPlayerInfoUpdatePacketAccessor {
    @Mutable
    @Accessor("entries")
    void polymer_patcher$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
