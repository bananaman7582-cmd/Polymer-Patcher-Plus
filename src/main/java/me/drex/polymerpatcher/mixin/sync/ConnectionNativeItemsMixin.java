package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.util.ClientNumbering;
import me.drex.polymerpatcher.util.NativeItemConnection;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gives each connection somewhere to remember what its client said about itself while joining.
 * <p>
 * Kept on the connection rather than by player, because one account can be joining on a second client
 * while its first is still being disconnected, and anything filed by player is shared between the two.
 * <p>
 * Volatile because it is written on the server thread while the client is configured and read on the
 * network thread for every packet written afterwards.
 */
@Mixin(Connection.class)
public abstract class ConnectionNativeItemsMixin implements NativeItemConnection {
    @Unique
    private volatile Set<String> polymerPatcher$syncedItemNamespaces = Set.of();

    @Unique
    private volatile Set<String> polymerPatcher$sameVersionMods = Set.of();

    // Both getters treat an unset field as empty. The initial values above are not reliably copied into the
    // connection's constructor, so a connection that never had one set - every vanilla client's - read null,
    // and the first item tag written to it ended the connection
    @Override
    public Set<String> polymerPatcher$syncedItemNamespaces() {
        Set<String> namespaces = this.polymerPatcher$syncedItemNamespaces;
        return namespaces == null ? Set.of() : namespaces;
    }

    @Override
    public void polymerPatcher$setSyncedItemNamespaces(Set<String> namespaces) {
        this.polymerPatcher$syncedItemNamespaces = namespaces;
    }

    @Override
    public Set<String> polymerPatcher$sameVersionMods() {
        Set<String> mods = this.polymerPatcher$sameVersionMods;
        return mods == null ? Set.of() : mods;
    }

    @Override
    public void polymerPatcher$setSameVersionMods(Set<String> mods) {
        this.polymerPatcher$sameVersionMods = mods;
    }

    /** Replaced whole rather than changed, because it is read on the network thread for every packet. */
    @Unique
    private volatile @Nullable Map<Identifier, ClientNumbering> polymerPatcher$numberings;

    @Override
    public @Nullable ClientNumbering polymerPatcher$numbering(Identifier registry) {
        Map<Identifier, ClientNumbering> numberings = this.polymerPatcher$numberings;
        return numberings == null ? null : numberings.get(registry);
    }

    @Override
    public synchronized void polymerPatcher$setNumbering(Identifier registry, @Nullable ClientNumbering numbering) {
        Map<Identifier, ClientNumbering> numberings = this.polymerPatcher$numberings;
        Map<Identifier, ClientNumbering> next = numberings == null ? new HashMap<>() : new HashMap<>(numberings);
        if (numbering == null) {
            next.remove(registry);
        } else {
            next.put(registry, numbering);
        }
        this.polymerPatcher$numberings = Map.copyOf(next);
    }

    @Override
    public void polymerPatcher$clearNumberings() {
        this.polymerPatcher$numberings = null;
    }
}
