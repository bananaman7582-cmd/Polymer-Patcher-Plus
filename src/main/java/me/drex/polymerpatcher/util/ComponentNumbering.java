package me.drex.polymerpatcher.util;

import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import eu.pb4.polymer.rsm.impl.RegistrySyncExtension;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * The numbering of item data types one client was told to use, and the way back from it to this server's.
 * <p>
 * An item's data travels as a list of numbered types. Polymer keeps every mod's types out of the registry sync
 * a Fabric client is sent on joining, so a client numbers the types its own mods registered however its own
 * load order fell, and this server numbers its own differently again. Nothing reads one of those numbers from
 * a client in the ordinary course of things - data a client has no mod for never reaches it - except an item
 * it sends back whole, which is what creative mode does. A Fancy Portals command stick picked out of the
 * client's own creative tab carried its command under the client's number for the command type, which on this
 * server is the number for a portal wand's settings, and the server dropped the player trying to read a
 * command as a map.
 * <p>
 * Fixing it means both ends using the same numbers, and the same numbers cannot simply be this server's: its
 * own sit in one run after the vanilla ones, with the types of every mod the client lacks spread through it.
 * A numbering with gaps in it is fatal to a 26.2 client (see {@link NativeItemSync}). So a client is given a
 * numbering of its own with no gaps - the vanilla types where they always are, then only the types of the mods
 * it has at this server's version, packed one after another - and every number it sends is turned back into
 * this server's on the way in.
 * <p>
 * It rides in the item sync {@link NativeItemSync} already sends, and only then: that sync is the one a client
 * has been shown to accept, and a sync sent for data types on their own is what crashed clients in 0.14.309.
 */
public final class ComponentNumbering {
    public static final Identifier REGISTRY = BuiltInRegistries.DATA_COMPONENT_TYPE.key().identifier();

    /** Index: the number the client uses. Value: this server's number for the same type. */
    private final int[] serverIds;
    private final Object2IntMap<Identifier> clientIds;

    private ComponentNumbering(int[] serverIds, Object2IntMap<Identifier> clientIds) {
        this.serverIds = serverIds;
        this.clientIds = clientIds;
    }

    /** What goes in the client's registry sync: every type it is to know, at the number it is to use. */
    public Object2IntMap<Identifier> clientIds() {
        return this.clientIds;
    }

    /**
     * Works out a gap-free numbering for a client with these mods (by registry namespace), or null when there
     * is nothing to renumber or this server's registry is not laid out the way this relies on.
     */
    public static @Nullable ComponentNumbering forNamespaces(Set<String> namespaces) {
        Registry<DataComponentType<?>> registry = BuiltInRegistries.DATA_COMPONENT_TYPE;
        // Fabric's own sync carries this registry when a mod's type is not kept back by Polymer, and would
        // then renumber the client again behind this one's back
        if (registry instanceof RegistrySyncExtension<?> extension
            && extension.polymer_registry_sync$getStatus() == RegistrySyncExtension.Status.WITH_MODDED) {
            return null;
        }

        Object2IntLinkedOpenHashMap<Identifier> clientIds = new Object2IntLinkedOpenHashMap<>();
        IntArrayList serverIds = new IntArrayList();
        boolean pastShared = false;
        boolean renumbered = false;
        int size = registry.size();
        for (int serverId = 0; serverId < size; serverId++) {
            DataComponentType<?> type = registry.byId(serverId);
            Identifier key = type == null ? null : registry.getKey(type);
            if (key == null) {
                // A gap in this server's own numbering; nothing below can be trusted
                return null;
            }

            if (!RegistrySyncUtils.isServerEntry(registry, type)) {
                // Polymer moves every type it keeps back after the ones it shares, and the shared ones are
                // vanilla. Anything else means the layout is not what this relies on, so leave it alone
                if (pastShared || !RegistryPatcher.isVanillaId(key)) {
                    return null;
                }
            } else {
                pastShared = true;
                // A mod's type under the game's own name says nothing about which mod it is
                if (RegistryPatcher.isVanillaId(key) || !namespaces.contains(key.getNamespace())) {
                    continue;
                }
                renumbered = true;
            }

            clientIds.put(key, serverIds.size());
            serverIds.add(serverId);
        }

        return renumbered ? new ComponentNumbering(serverIds.toIntArray(), clientIds) : null;
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
     * The type a client meant by the number just read from it, given the type this server's numbering read it
     * as. Returns the same object for every client that was not renumbered, for every number that means the
     * same at both ends, and for anything this server decodes outside reading a client's packet - copying a
     * stack through a buffer of its own while writing to that client, say, where the numbers are its own.
     */
    public static Object fromClient(Object decoded) {
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
        ComponentNumbering numbering = known.polymerPatcher$componentNumbering();
        if (numbering == null) {
            return decoded;
        }

        if (decoded instanceof Holder<?> holder && holder.value() instanceof DataComponentType<?> type) {
            DataComponentType<?> meant = numbering.translate(type);
            return meant == type ? decoded : BuiltInRegistries.DATA_COMPONENT_TYPE.wrapAsHolder(meant);
        }
        if (decoded instanceof DataComponentType<?> type) {
            return numbering.translate(type);
        }
        return decoded;
    }

    private DataComponentType<?> translate(DataComponentType<?> decoded) {
        int clientId = BuiltInRegistries.DATA_COMPONENT_TYPE.getId(decoded);
        if (clientId < 0 || clientId >= this.serverIds.length) {
            // Past everything the client was told about: one of its own client-only types, which this
            // server has never known the number of. Read as it always was
            return decoded;
        }
        int serverId = this.serverIds[clientId];
        if (serverId == clientId) {
            return decoded;
        }
        DataComponentType<?> meant = BuiltInRegistries.DATA_COMPONENT_TYPE.byId(serverId);
        return meant != null ? meant : decoded;
    }
}
