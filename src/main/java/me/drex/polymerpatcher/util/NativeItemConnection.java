package me.drex.polymerpatcher.util;

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

    /** The data type numbering this connection's client was told to use, or null where it keeps its own. */
    @Nullable ComponentNumbering polymerPatcher$componentNumbering();

    void polymerPatcher$setComponentNumbering(@Nullable ComponentNumbering numbering);
}
