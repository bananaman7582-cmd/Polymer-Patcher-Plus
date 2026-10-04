package me.drex.polymerpatcher.companion.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server entry being joined, which the connection knows while the game itself does not yet. */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {

    @Accessor("serverData")
    ServerData polymerPatcherClient$serverData();
}
