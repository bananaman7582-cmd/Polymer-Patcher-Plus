package me.drex.polymerpatcher.companion.client.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The registry's list of blocks and fluids that were built but not yet registered.
 * <p>
 * A block puts itself on that list the moment construction begins. If building one of the server's
 * blocks fails partway, it stays on the list, and the game refuses to finish starting. The companion
 * takes its own failures back off, so a block it cannot build costs that block and nothing more.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor<T> {

    @Accessor("unregisteredIntrusiveHolders")
    Map<T, Holder.Reference<T>> polymerPatcherClient$unregisteredIntrusiveHolders();
}
