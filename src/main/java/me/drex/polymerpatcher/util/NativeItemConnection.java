package me.drex.polymerpatcher.util;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Which mods' items a connection's client was made to number exactly as this server does.
 * <p>
 * Kept on the connection because that is what survives from joining into playing: it is decided while
 * the client is being configured and read for every item written to it afterwards.
 */
public interface NativeItemConnection {
    Set<String> polymerPatcher$syncedItemNamespaces();

    void polymerPatcher$setSyncedItemNamespaces(Set<String> namespaces);

    /** Every mod this connection's client said it has at this server's exact version, while it was joining. */
    Set<String> polymerPatcher$sameVersionMods();

    void polymerPatcher$setSameVersionMods(Set<String> mods);

    /** The numbering of this registry the connection's client was told to use, or null where it keeps its own. */
    @Nullable ClientNumbering polymerPatcher$numbering(Identifier registry);

    void polymerPatcher$setNumbering(Identifier registry, @Nullable ClientNumbering numbering);

    /** Forgets every numbering, for a client that will be sent none after all. */
    void polymerPatcher$clearNumberings();
}
