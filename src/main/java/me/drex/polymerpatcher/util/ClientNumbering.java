package me.drex.polymerpatcher.util;

import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import eu.pb4.polymer.networking.api.ContextByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A gap-free numbering of one registry that one client was told to use, and the way back from it to this
 * server's. Used for items and for item data types.
 * <p>
 * Polymer keeps every mod's entries out of the registry sync a Fabric client is sent on joining, so a client
 * numbers the entries its own mods registered however its own load order fell, and this server numbers its
 * own differently again. Nothing reads one of those numbers from a client in the ordinary course of things -
 * a client is only ever sent stand-ins for what it has no mod for - except an item it sends back whole, which
 * is what creative mode does. A Fancy Portals item picked out of the client's own creative tab arrived under
 * the client's number for that item and carried a {@code "create_portal"} under the client's number for one of
 * its data types, which on this server is the number of another type that holds a map, and the server dropped
 * the player trying to read it.
 * <p>
 * Fixing it means reconstructing the client's gap-free numbering. Every entry this server shares with all
 * clients keeps its number exactly; entries belonging to mods the client has fill the available numbers in
 * registry order. Every number the client sends is then turned back into this server's on the way in.
 *
 * <p>Item numbering may also be sent to a client when it is safe to hand that client real modded items.
 * Data-component numbering is deliberately <em>never</em> sent back. Component types occur inside ordinary
 * server-to-client ItemStacks too, and replacing the client's component registry made Polymer creative-tab
 * entries unreadable ({@code No value with id 46}). Instead, component numbers are translated in both
 * directions at the packet codec: client number to server entry while reading, and server entry to client
 * number while writing. Neither side's actual registry is rewritten.</p>
 * <p>
 * A client handed its mods' real items gets this server's own item numbering instead (see
 * {@link NativeItemSync}); its data types are still translated only while reading its packets.
 */
public final class ClientNumbering {
    /** Why the last attempt to build a numbering gave up, so it is said once rather than per join. */
    private static volatile @Nullable String reportedFailure;

    private final Registry<?> registry;
    /** Index: the number the client uses. Value: this server's number for the same entry. */
    private final int[] serverIds;
    private final Object2IntMap<Identifier> clientIds;

    private ClientNumbering(Registry<?> registry, int[] serverIds, Object2IntMap<Identifier> clientIds) {
        this.registry = registry;
        this.serverIds = serverIds;
        this.clientIds = clientIds;
    }

    /** Which registry this numbers, as the client's registry sync names it. */
    public Identifier registryId() {
        return this.registry.key().identifier();
    }

    /** What goes in the client's registry sync: every entry it is to know, at the number it is to use. */
    public Object2IntMap<Identifier> clientIds() {
        return this.clientIds;
    }

    /** How many of the client's entries were given numbers of their own, for the log. */
    public int renumbered() {
        int moved = 0;
        for (int clientId = 0; clientId < this.serverIds.length; clientId++) {
            if (this.serverIds[clientId] != clientId) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * Works out a gap-free numbering of this registry for a client with these mods (by registry namespace), or
     * null when there is nothing to renumber or it cannot be done without a gap.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static @Nullable ClientNumbering forNamespaces(Registry<?> registry, Set<String> namespaces) {
        Registry raw = registry;
        int size = registry.size();
        Identifier[] keyAt = new Identifier[size];
        int[] serverIdAt = new int[size];
        IntArrayList theirs = new IntArrayList();
        int shared = 0;
        int highestShared = -1;

        for (int serverId = 0; serverId < size; serverId++) {
            Object entry = raw.byId(serverId);
            Identifier key = entry == null ? null : raw.getKey(entry);
            if (key == null) {
                return giveUp(registry, "this server has nothing at number " + serverId);
            }

            if (!RegistrySyncUtils.isServerEntry(raw, entry)) {
                // Shared with every client, and every client has it where this server has it
                keyAt[serverId] = key;
                serverIdAt[serverId] = serverId;
                shared++;
                highestShared = serverId;
            } else if (!RegistryPatcher.isVanillaId(key) && namespaces.contains(key.getNamespace())) {
                // A mod's entry under the game's own name says nothing about which mod it is, so only the
                // ones under a mod's own name are counted as the client's
                theirs.add(serverId);
            }
        }

        if (theirs.isEmpty()) {
            return null;
        }
        int total = shared + theirs.size();
        if (highestShared >= total) {
            return giveUp(registry, "the entries every client shares reach number " + highestShared + ", past the "
                + total + " this client would know, so the numbering would have a gap");
        }

        int next = 0;
        for (int i = 0; i < theirs.size(); i++) {
            while (keyAt[next] != null) {
                next++;
            }
            int serverId = theirs.getInt(i);
            keyAt[next] = raw.getKey(raw.byId(serverId));
            serverIdAt[next] = serverId;
        }

        Object2IntLinkedOpenHashMap<Identifier> clientIds = new Object2IntLinkedOpenHashMap<>();
        int[] serverIds = new int[total];
        for (int clientId = 0; clientId < total; clientId++) {
            if (keyAt[clientId] == null) {
                return giveUp(registry, "number " + clientId + " was left empty");
            }
            clientIds.put(keyAt[clientId], clientId);
            serverIds[clientId] = serverIdAt[clientId];
        }
        return new ClientNumbering(registry, serverIds, clientIds);
    }

    private static @Nullable ClientNumbering giveUp(Registry<?> registry, String why) {
        String report = registry.key().identifier() + ": " + why;
        if (!report.equals(reportedFailure)) {
            reportedFailure = report;
            PolymerPatcher.LOGGER.warn("Could not give a joining client this server's numbering of {}. "
                + "Items it sends back from creative mode may be misread. Said once per reason.", report);
        }
        return null;
    }

    /** How deep this thread is in reading a packet a client sent. Only those numbers are the client's. */
    private static final ThreadLocal<int[]> READING = ThreadLocal.withInitial(() -> new int[1]);

    /** Runs a packet decode, marking every number read during it as one a client wrote. */
    public static void whileReading(Runnable decode) {
        int[] depth = READING.get();
        depth[0]++;
        try {
            decode.run();
        } finally {
            depth[0]--;
        }
    }

    /**
     * The entry a client meant by the number just read from it, given the entry this server's numbering read it
     * as. Returns the same object for every client that was not renumbered in this registry, for every number
     * that means the same at both ends, and for anything this server decodes outside reading a client's packet -
     * copying a stack through a buffer of its own, say, where the numbers are its own.
     */
    public static Object fromClient(ResourceKey<?> registryKey, Object decoded) {
        if (READING.get()[0] == 0) {
            return decoded;
        }
        PacketContext context = PacketContext.get();
        if (context == null) {
            return decoded;
        }
        Connection connection = context.get(PacketContext.CONNECTION);
        if (!(connection instanceof NativeItemConnection known)) {
            return decoded;
        }
        ClientNumbering numbering = known.polymerPatcher$numbering(registryKey.identifier());
        if (numbering == null) {
            return decoded;
        }

        if (decoded instanceof Holder<?> holder) {
            Object value = holder.value();
            Object meant = numbering.translate(value);
            return meant == value ? decoded : numbering.wrap(meant);
        }
        return numbering.translate(decoded);
    }

    /**
     * The numeric id this connection's client uses for a server registry entry, or {@code -1} when the
     * ordinary server id should be written.
     *
     * <p>This is intentionally used only for data-component types.  A matched mod's real item may carry one
     * of its own components, but clients missing other server mods compact that component registry and assign
     * it another number.  Writing the server number produced the recurring creative-tab {@code id 46} kick.
     * Writing the reconstructed client number keeps the value's real component codec while putting the id the
     * receiver actually understands on the wire.</p>
     */
    public static int toClientId(RegistryFriendlyByteBuf buf, ResourceKey<?> registryKey, Object value) {
        // Polymer serializes its own payloads through ContextByteBuf.  That buffer keeps the destination
        // connection explicitly, while Fabric's thread-scoped PacketContext is not guaranteed to remain
        // installed inside the nested payload codec.  The creative-tab kick lived on exactly that path, so
        // prefer the buffer's authoritative connection and retain PacketContext as the vanilla-packet path.
        Connection connection = buf instanceof ContextByteBuf contextBuf
            ? contextBuf.clientConnection()
            : connectionFromPacketContext();
        if (!(connection instanceof NativeItemConnection known)) {
            return -1;
        }
        ClientNumbering numbering = known.polymerPatcher$numbering(registryKey.identifier());
        return numbering == null ? -1 : numbering.clientId(value);
    }

    private static @Nullable Connection connectionFromPacketContext() {
        PacketContext context = PacketContext.get();
        return context == null ? null : context.get(PacketContext.CONNECTION);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int clientId(Object value) {
        Object unwrapped = value instanceof Holder<?> holder ? holder.value() : value;
        Identifier key = ((Registry) this.registry).getKey(unwrapped);
        return key != null && this.clientIds.containsKey(key) ? this.clientIds.getInt(key) : -1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object translate(Object decoded) {
        Registry raw = this.registry;
        int clientId = raw.getId(decoded);
        if (clientId < 0 || clientId >= this.serverIds.length) {
            // Past everything the client was told about: one of its own client-only entries, which this
            // server has never known the number of. Read as it always was
            return decoded;
        }
        int serverId = this.serverIds[clientId];
        if (serverId == clientId) {
            return decoded;
        }
        Object meant = raw.byId(serverId);
        return meant != null ? meant : decoded;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object wrap(Object value) {
        return ((Registry) this.registry).wrapAsHolder(value);
    }
}
