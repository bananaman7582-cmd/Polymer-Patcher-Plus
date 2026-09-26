package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(SynchronizeRegistriesTask.class)
public interface SynchronizeRegistriesTaskAccessor {
    /** The packs this server asked the client whether it has - one per mod with data, carrying that mod's version. */
    @Accessor("requestedPacks")
    List<KnownPack> polymerPatcher$requestedPacks();
}
