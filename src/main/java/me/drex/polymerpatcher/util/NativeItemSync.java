package me.drex.polymerpatcher.util;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.mixin.sync.ServerCommonPacketListenerImplAccessor;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.fabric.api.resource.v1.pack.ModPackResources;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Makes a modded client number a mod's items exactly as this server does, so it can be handed them as
 * themselves rather than as stand-ins - and turns away a client whose copy of a mod is a different version.
 * <p>
 * A stand-in is a vanilla item wearing a modded model, and a model is all it keeps. For somebody without
 * the mod that is exactly right. For somebody with it, it is why a galena gauntlet has no charge-up and a
 * resistor shield no slam, why cave tablets lose their colour, and why the spelunkery table does not
 * recognise the tablet put in it: the mod's own code on their client is handed something that is not
 * the mod's item, and does nothing.
 * <p>
 * An item travels as a number out of this server's item registry, and Polymer deliberately keeps modded
 * items out of the registry sync Fabric performs on joining - that is how clients with a different mod list
 * can connect at all - so a client's numbering is free to differ. Sending the real item without fixing that
 * disconnected the first player it reached.
 * <p>
 * <b>Knowing which mods a client has.</b> Its channel list cannot say: while joining, a Fabric client lists
 * only the channels it uses during joining, and a content mod's channels are for the game itself. What it
 * does answer is the known-packs question vanilla asks every client - which of this server's data packs do
 * you already have? Fabric makes every mod a pack whose identity carries the mod's exact version, and a
 * client answers yes only to a pack it has at that same version. That one answer says both whether the
 * client has a mod and whether it is the same version.
 * <p>
 * <b>Making the numbering agree.</b> Right after that answer, and before anything else the client is sent,
 * it is handed Fabric's own item sync again - this time with the items of the mods it has at this server's
 * versions put back in. Its client renumbers those items to match, so they can travel as themselves
 * everywhere: in the inventory, in the hand, in tags, in creative. Mods it does not have keep their
 * stand-ins, which is why a client missing a mod still joins exactly as before.
 * <p>
 * <b>Different versions.</b> A client that did not answer yes for a content mod might simply not have it,
 * which is fine. It names the mods it has by the channels it opens, and one that opens a channel for a mod
 * it did not have at this server's version has that mod at another version. It is disconnected with a
 * screen saying exactly which version to install - while it is still joining, wherever it can be, because
 * that screen does not survive being sent once the game has started. See {@link #refuseWhileConfiguring}.
 */
public final class NativeItemSync {
    private NativeItemSync() {
    }

    private static final Identifier ITEM_REGISTRY = BuiltInRegistries.ITEM.key().identifier();

    /** Clients sent this server's item numbering who have not reached the game yet, and for which mods. */
    private static final Map<UUID, PendingCheck> PENDING = new ConcurrentHashMap<>();

    /**
     * For each joining client, the content mods it did not have at this server's version - by namespace,
     * with the name and version this server runs. Whether the client has them at all is learned later.
     */
    private static final Map<UUID, Map<String, String>> NOT_MATCHING = new ConcurrentHashMap<>();

    /** Namespaces with content Polymer hides from clients: the mods whose items exist only on this server. */
    private static volatile @Nullable Set<String> hiddenNamespaces;

    /** {@code namespaces} is empty when only the item data types were renumbered and the items were not. */
    private record PendingCheck(String name, Set<String> namespaces) {
    }

    public static void init() {
        // A client that cannot reconcile what it was sent closes the connection itself during configuration
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = ownerId(handler);
            if (id == null) {
                return;
            }
            // Only a real disconnect. Finishing configuration and moving on to the game is not one
            Connection connection = connectionOf(handler);
            if (connection != null && connection.isConnected()) {
                return;
            }
            NOT_MATCHING.remove(id);
            PendingCheck check = PENDING.remove(id);
            if (check != null && !check.namespaces().isEmpty()) {
                PolymerPatcher.LOGGER.warn("{} disconnected while their client was renumbering its items to match this server's {}. "
                        + "Their client log says why; this server runs {}.",
                    check.name(), check.namespaces(), versions(check.namespaces()));
            } else if (check != null) {
                PolymerPatcher.LOGGER.warn("{} disconnected while their client was renumbering its items and item data types to match this server. "
                        + "Their client log says why; set entities.nativeComponents to false if this keeps happening.", check.name());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PendingCheck check = PENDING.remove(handler.getPlayer().getUUID());
            if (check != null && !check.namespaces().isEmpty()) {
                PolymerPatcher.LOGGER.info("{} took this server's item numbering and is handed the real items of {}", check.name(), check.namespaces());
            } else if (check != null) {
                PolymerPatcher.LOGGER.info("{} took this server's numbering; items they send back from creative mode are read as what they are", check.name());
            }
            sayWhatIsOutOfDate(handler);
            refuseIfDifferent(handler, server, ServerPlayNetworking.getSendable(handler));
        });
        // A client names its game channels once it is in the game, which can be either side of the join event
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> refuseIfDifferent(handler, server, channels));
    }

    /**
     * Reads a client's answer to which of this server's packs it has, and returns the item sync to send it
     * - or null when it should get none.
     * <p>
     * Called on the server thread before the answer is acted on, which matters: the item tags go out while
     * it is acted on, and they have to already include the items this client is about to be given.
     */
    public static @Nullable Map<Identifier, Object2IntMap<Identifier>> knownPacksReceived(ServerConfigurationPacketListenerImpl handler, MinecraftServer server,
                                                                                         List<KnownPack> requested, List<KnownPack> replied) {
        UUID id = ownerId(handler);
        try {
            Map<KnownPack, ModMetadata> modPacks = new HashMap<>();
            server.getResourceManager().listPacks().forEach(pack -> {
                if (pack instanceof ModPackResources mod) {
                    pack.location().knownPackInfo().ifPresent(known -> modPacks.put(known, mod.getFabricModMetadata()));
                }
            });

            Set<KnownPack> theirs = new HashSet<>(replied);
            Set<String> same = new TreeSet<>();
            Map<String, String> different = new TreeMap<>();
            for (KnownPack pack : requested) {
                ModMetadata mod = modPacks.get(pack);
                if (mod == null) {
                    continue;
                }
                if (theirs.contains(pack)) {
                    same.add(mod.getId());
                } else {
                    different.put(mod.getId(), mod.getName() + " " + mod.getVersion().getFriendlyString());
                }
            }

            // A client that has none of this server's mods at the same version is either vanilla or too old to
            // say which mods it has, and nothing it left unsaid can be read as a mismatch
            if (same.isEmpty()) {
                return null;
            }

            // Registry namespaces are not required to equal a Fabric mod id. Treat declared `provides`
            // aliases as belonging to the exact same matched mod (Fancy Portals registers components in
            // `iportal` while its actual mod id is `immersive_portals`).
            Set<String> allSameVersion = new TreeSet<>(same);
            for (String modId : same) {
                FabricLoader.getInstance().getModContainer(modId).ifPresent(container ->
                    allSameVersion.addAll(container.getMetadata().getProvides()));
            }

            // Written down before anything below narrows it. Which mods a client has at this server's
            // version is also what decides how its entity fields and component types are numbered, and
            // this is the first moment it is known. Include declared aliases such as Fancy Portals'
            // `iportal`, because registry namespaces need not equal the owning Fabric mod id.
            if (connectionOf(handler) instanceof NativeItemConnection known) {
                known.polymerPatcher$setSameVersionMods(Set.copyOf(allSameVersion));
            }

            Set<String> hidden = hiddenNamespaces();
            int matching = same.size();
            // Items are found by the namespace they are registered under, which need not be the mod's id:
            // Fancy Portals is immersive_portals and registers its items as iportal. Matched by mod id
            // alone, its items were never put back for a client that has it, and stayed stand-ins
            Set<String> sameNamespaces = new TreeSet<>(allSameVersion);
            sameNamespaces.retainAll(hidden);
            different.keySet().retainAll(hidden);
            PolymerPatcher.LOGGER.info("{} has {} of this server's mods at the same version; of the ones with server-only content: {}",
                ownerName(handler), matching, sameNamespaces.isEmpty() ? "none" : sameNamespaces);

            // Written down whether or not a mismatch is grounds for turning anybody away: it is also what
            // the player is told about on joining, and that is worth saying even when they are let in
            if (id != null && !different.isEmpty()) {
                NOT_MATCHING.put(id, Collections.unmodifiableMap(different));
            }

            if (!ServerConfigurationNetworking.canSend(handler, RegistrySyncPayload.ID)) {
                return null;
            }

            Map<Identifier, Object2IntMap<Identifier>> sync = new LinkedHashMap<>();
            Object2IntMap<Identifier> items = itemNumbering(handler, sameNamespaces);
            if (items != null) {
                sync.put(ITEM_REGISTRY, items);
            }

            // The mods it has at this server's version, numbered so an item the client sends back - a creative
            // pick from its own tabs above all - is read as what it is. A client handed real items already has
            // this server's item numbering, so only the data types are renumbered for it; a client given
            // stand-ins still has those mods' creative tabs, so it gets both. See ClientNumbering
            if (ConfigManager.config().entities.nativeComponents && connectionOf(handler) instanceof NativeItemConnection state) {
                if (items == null) {
                    renumber(state, sync, BuiltInRegistries.ITEM, allSameVersion);
                }
                renumber(state, sync, BuiltInRegistries.DATA_COMPONENT_TYPE, allSameVersion);
            }
            return sync.isEmpty() ? null : sync;
        } catch (Throwable e) {
            // Anything unexpected falls back to exactly what would have happened without this
            clearNamespaces(handler);
            PolymerPatcher.LOGGER.warn("Could not work out which mods a joining client has; they will get stand-ins", e);
            return null;
        }
    }

    private static void renumber(NativeItemConnection state, Map<Identifier, Object2IntMap<Identifier>> sync,
                                 Registry<?> registry, Set<String> namespaces) {
        ClientNumbering numbering = ClientNumbering.forNamespaces(registry, namespaces);
        if (numbering != null) {
            sync.put(numbering.registryId(), numbering.clientIds());
            state.polymerPatcher$setNumbering(numbering.registryId(), numbering);
        }
    }

    /**
     * This server's item numbering for a client with these mods, or null when it should keep stand-ins.
     */
    private static @Nullable Object2IntMap<Identifier> itemNumbering(ServerConfigurationPacketListenerImpl handler, Set<String> sameNamespaces) {
        if (sameNamespaces.isEmpty() || !ConfigManager.config().entities.nativeItems) {
            return null;
        }

        // Every item goes in, in the server's own order, exactly as Fabric would write it - vanilla ones
        // included. An item left out of the map is one the client assumes is its own and renumbers, and a
        // vanilla item renumbered is every vanilla item wrong. The only ones left out are Polymer's, and of
        // those only the ones belonging to mods this client does not have at this server's version
        Set<String> included = new TreeSet<>();
        Object2IntLinkedOpenHashMap<Identifier> items = new Object2IntLinkedOpenHashMap<>();
        int highestSent = -1;
        int firstHole = Integer.MAX_VALUE;
        String holeBelongsTo = null;

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            int number = BuiltInRegistries.ITEM.getId(item);
            if (RegistrySyncUtils.isServerEntry(BuiltInRegistries.ITEM, item)) {
                // A mod registering under the game's own name is still left as a stand-in: the name says
                // nothing about which mod it is, so there is no pack to match it against
                if (RegistryPatcher.isVanillaId(key) || !sameNamespaces.contains(key.getNamespace())) {
                    if (number < firstHole) {
                        firstHole = number;
                        holeBelongsTo = key.getNamespace();
                    }
                    continue;
                }
                included.add(key.getNamespace());
            }
            items.put(key, number);
            highestSent = Math.max(highestSent, number);
        }

        if (included.isEmpty() || !(connectionOf(handler) instanceof NativeItemConnection state)) {
            return null;
        }

        // An id this server uses for something left out of the map is an id with nothing in it on the
        // client, and since 26.2 that is fatal. The client walks its item registry by number to give
        // each item its components, a number with nothing at it is a null it cannot give anything to,
        // and it throws while finishing joining - before it is ever in the game, with nothing in its
        // own log tying the crash to its mods. kingy_allay, who had four of this server's content mods
        // and not the rest, could not get in at all.
        //
        // So the numbering only goes out when it arrives whole. A client missing any of the mods whose
        // items would fill it gets the stand-ins instead, exactly as it did before any of this existed,
        // and is told on joining which mods it is missing.
        if (firstHole < highestSent) {
            PolymerPatcher.LOGGER.info("{} is missing {}, whose items sit in the middle of this server's numbering; "
                    + "they are sent the stand-ins rather than a numbering with holes in it, which a client cannot read",
                ownerName(handler), holeBelongsTo);
            clearNamespaces(handler);
            return null;
        }
        state.polymerPatcher$setSyncedItemNamespaces(Set.copyOf(included));
        return items;
    }

    /**
     * Puts the item sync at the front of this client's remaining joining steps, so it is renumbered before it
     * is sent anything that holds an item - its spawn, its inventory, the resource pack prompt included.
     * <p>
     * Fabric's own sync task is used as it is. Fabric already answers the client's "done" for that task, and
     * because this one is queued in the normal way, that answer finishes it the normal way too.
     */
    public static void queue(ServerConfigurationPacketListenerImpl handler, Queue<ConfigurationTask> tasks, Map<Identifier, Object2IntMap<Identifier>> sync) {
        try {
            ConfigurationTask task = new RegistrySyncManager.SyncConfigurationTask(handler, sync);
            List<ConfigurationTask> later = new ArrayList<>(tasks);
            tasks.clear();
            tasks.add(task);
            tasks.addAll(later);
        } catch (Throwable e) {
            clearNamespaces(handler);
            PolymerPatcher.LOGGER.warn("Could not queue the item sync for a joining client; they will get stand-ins", e);
            return;
        }

        NativeItemConnection state = connectionOf(handler) instanceof NativeItemConnection known ? known : null;
        Set<String> included = state != null ? state.polymerPatcher$syncedItemNamespaces() : Set.of();
        String name = ownerName(handler);
        UUID id = ownerId(handler);
        if (id != null) {
            PENDING.put(id, new PendingCheck(name, included));
        }
        String renumbered = renumberedSummary(state);
        if (!included.isEmpty()) {
            PolymerPatcher.LOGGER.info("Syncing {}'s item numbering for {} so they can be handed the real items{}", name, included,
                renumbered.isEmpty() ? "" : ", with " + renumbered);
        } else {
            PolymerPatcher.LOGGER.info("Syncing {}'s numbering so items they send back from creative mode are read correctly: {}",
                name, renumbered);
        }
    }

    private static String renumberedSummary(@Nullable NativeItemConnection state) {
        if (state == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        ClientNumbering items = state.polymerPatcher$numbering(ITEM_REGISTRY);
        if (items != null) {
            parts.add(items.renumbered() + " of their mods' items renumbered to match");
        }
        ClientNumbering types = state.polymerPatcher$numbering(BuiltInRegistries.DATA_COMPONENT_TYPE.key().identifier());
        if (types != null) {
            parts.add(types.renumbered() + " of their mods' item data types renumbered to match");
        }
        return String.join(", ", parts);
    }

    /**
     * Whether this item may be written to the client behind this context as itself.
     * <p>
     * True only where that client's own registry was made to agree with this one for the item's mod, which
     * is what makes it safe in every direction and in every place an item can appear.
     */
    public static boolean sendsRaw(@Nullable PacketContext context, Item item) {
        if (context == null) {
            return false;
        }

        Connection connection = context.orElse(PacketContext.CONNECTION, null);
        if (connection == null) {
            ServerPlayer player = PolymerCommonUtils.getPlayer(context);
            if (player != null && player.connection != null) {
                connection = connectionOf(player.connection);
            }
        }
        if (!(connection instanceof NativeItemConnection state)) {
            return false;
        }

        Set<String> namespaces = state.polymerPatcher$syncedItemNamespaces();
        if (namespaces.isEmpty()) {
            return false;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key != null && namespaces.contains(key.getNamespace());
    }

    public static void forget(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        NOT_MATCHING.remove(player.getUUID());
    }

    /**
     * Tells a player, as they arrive, which of this server's mods they are not running the same version of.
     * <p>
     * Only somebody who already has some of them at the right version is told. That is what separates a
     * player whose mods have fallen behind from one who has none of them and wants none: the first is
     * playing modded and would otherwise spend the evening wondering why one mod's content looks wrong,
     * while the second is playing perfectly well with stand-ins and does not need telling about mods they
     * never installed.
     * <p>
     * Said rather than enforced. A version that does not match is only grounds for turning somebody away
     * once their client proves it has that mod - see {@link #refuseIfDifferent} - and this runs for the far
     * more common case where they simply do not have it and everything works anyway.
     */
    private static void sayWhatIsOutOfDate(ServerGamePacketListenerImpl handler) {
        try {
            ServerPlayer player = handler.getPlayer();
            Map<String, String> notMatching = NOT_MATCHING.get(player.getUUID());
            if (notMatching == null || notMatching.isEmpty()) {
                return;
            }

            Connection connection = connectionOf(handler);
            Set<String> theirs = connection instanceof NativeItemConnection state
                ? state.polymerPatcher$sameVersionMods()
                : Set.of();
            if (Collections.disjoint(theirs, hiddenNamespaces())) {
                return;
            }

            Component message = Component.literal("Some of this server's mods are not the versions you have:\n")
                .append(Component.literal(notMatching.values().stream().map(name -> "  " + name).collect(Collectors.joining("\n"))))
                .append(Component.literal("\nUpdate them to see that content properly. Everything else works as it is."));
            player.sendSystemMessage(message);
            PolymerPatcher.LOGGER.info("Told {} that their {} {} not this server's version",
                player.getGameProfile().name(), notMatching.keySet(), notMatching.size() == 1 ? "is" : "are");
        } catch (Throwable e) {
            // Saying nothing is better than a failed join
            PolymerPatcher.LOGGER.debug("Could not tell a player which of their mods are out of date", e);
        }
    }

    /**
     * Disconnects a joining client that has one of this server's content mods at another version, before it
     * has left configuration. True when it was disconnected, and nothing more should be done for it.
     * <p>
     * This is where the refusal has to happen for its screen to be seen at all. Turned away once in the
     * game, the server logged the reason and the player saw nothing but "Disconnected": by then they are
     * being sent their spawn, and - having opened that mod's channels - the real content of the very mod
     * whose version is wrong, and the screen with the reason on it never made it through. An ordinary kick
     * later on shows its reason perfectly well; it is the moment that was wrong, not the message.
     * <p>
     * A Fabric client lists every channel it can receive in the game while it is still being configured -
     * the common register exchange, which Fabric runs before the known-packs question is even asked - so
     * by the time that answer is read here, both halves are known: which mods it has not got at this
     * server's version, and which of those it has at all. A client that does not take part in that exchange
     * lists nothing here, and is caught by {@link #refuseIfDifferent} once in the game instead.
     */
    public static boolean refuseWhileConfiguring(ServerConfigurationPacketListenerImpl handler) {
        try {
            UUID id = ownerId(handler);
            Map<String, String> notMatching = id == null ? null : NOT_MATCHING.get(id);
            if (notMatching == null || !ConfigManager.config().entities.refuseMismatchedMods) {
                return false;
            }

            // The channels it will receive in the game, waiting for the game to start. A copy, because Fabric
            // still owns the set, and its configuration channels besides - a mod's channel either way says the
            // mod is there
            Set<Identifier> channels = new HashSet<>(ServerConfigurationNetworking.getSendable(handler));
            if (connectionOf(handler) instanceof ChannelInfoHolder holder) {
                channels.addAll(List.copyOf(holder.fabric_getPendingChannelsNames(ConnectionProtocol.PLAY)));
            }

            Map<String, String> theirs = mismatched(notMatching, channels);
            if (theirs.isEmpty() || NOT_MATCHING.remove(id) == null) {
                return false;
            }

            PolymerPatcher.LOGGER.warn("Disconnecting {} while they join: their client has {} at a different version. This server runs {}.",
                ownerName(handler), theirs.keySet(), String.join(", ", theirs.values()));
            handler.disconnect(refusal(theirs));
            return true;
        } catch (Throwable e) {
            // Not knowing here only means finding out once they are in the game, as before
            PolymerPatcher.LOGGER.debug("Could not check a joining client's mod versions while it was configured", e);
            return false;
        }
    }

    /**
     * Disconnects a player who has turned out, once in the game, to have one of this server's content mods
     * at another version.
     * <p>
     * Only a mod they did not have at this server's version and have now opened a channel for counts. A mod
     * they simply do not have opens no channel, and never gets here. Most clients are refused while joining,
     * by {@link #refuseWhileConfiguring}; this is for the ones that did not list their channels then.
     */
    private static void refuseIfDifferent(ServerGamePacketListenerImpl handler, MinecraftServer server, Collection<Identifier> channels) {
        UUID id = handler.getPlayer().getUUID();
        Map<String, String> notMatching = NOT_MATCHING.get(id);
        // The list is kept for everyone now, because everyone who has fallen behind is told about it on
        // joining; whether that is also grounds for turning them away is this setting's business
        if (notMatching == null || !ConfigManager.config().entities.refuseMismatchedMods) {
            return;
        }

        Map<String, String> theirs = mismatched(notMatching, channels);
        // Removed on the way out, so a player is only ever refused once however many channels follow
        if (theirs.isEmpty() || NOT_MATCHING.remove(id) == null) {
            return;
        }

        PolymerPatcher.LOGGER.warn("Disconnecting {}: their client has {} at a different version. This server runs {}.",
            handler.getPlayer().getGameProfile().name(), theirs.keySet(), String.join(", ", theirs.values()));
        REFUSALS.put(handler, new Refusal(refusal(theirs), REFUSAL_DELAY_TICKS));
    }

    /** Of the mods a client lacks at this server's version, the ones it has a channel for - so has at another. */
    private static Map<String, String> mismatched(Map<String, String> notMatching, Collection<Identifier> channels) {
        Map<String, String> theirs = new TreeMap<>();
        for (Identifier channel : channels) {
            String needed = notMatching.get(channel.getNamespace());
            if (needed != null) {
                theirs.put(channel.getNamespace(), needed);
            }
        }
        return theirs;
    }

    /** The screen a refused player sees, naming each version they need. */
    private static Component refusal(Map<String, String> theirs) {
        return Component.literal("Your mods don't match this server.\n\n"
            + (theirs.size() == 1 ? "Install this version to join:\n" : "Install these versions to join:\n")
            + String.join("\n", theirs.values()));
    }

    /**
     * How long a refused player is let finish joining before being sent away.
     * <p>
     * The refusal is decided the moment the client names its channels, which is in the middle of joining -
     * before other mods have finished with the player. Disconnecting there let the disconnect overtake
     * AuthCore's own join: it handled the player leaving first, then the join, and so recorded a dead
     * connection as logged in. From then on it turned the player away as "already logged in" on every
     * attempt until the server restarted - Sir_ChickenNug was locked out that way after one refusal.
     * Three seconds lets every join finish, so the disconnect is an ordinary one that everything sees.
     */
    private static final int REFUSAL_DELAY_TICKS = 60;

    private record Refusal(Component reason, int ticksLeft) {
    }

    private static final Map<ServerGamePacketListenerImpl, Refusal> REFUSALS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            var refusals = REFUSALS.entrySet().iterator();
            while (refusals.hasNext()) {
                var entry = refusals.next();
                ServerGamePacketListenerImpl handler = entry.getKey();
                Refusal refusal = entry.getValue();
                if (!handler.isAcceptingMessages()) {
                    refusals.remove();
                } else if (refusal.ticksLeft() <= 0) {
                    refusals.remove();
                    handler.disconnect(refusal.reason());
                } else {
                    entry.setValue(new Refusal(refusal.reason(), refusal.ticksLeft() - 1));
                }
            }
        });
    }

    private static Set<String> hiddenNamespaces() {
        Set<String> known = hiddenNamespaces;
        if (known == null) {
            Set<String> found = new HashSet<>();
            collectServerEntries(BuiltInRegistries.ITEM, found);
            collectServerEntries(BuiltInRegistries.BLOCK, found);
            collectServerEntries(BuiltInRegistries.ENTITY_TYPE, found);
            found.remove("minecraft");
            hiddenNamespaces = known = Set.copyOf(found);
        }
        return known;
    }

    private static <T> void collectServerEntries(Registry<T> registry, Set<String> into) {
        for (T entry : registry) {
            if (RegistrySyncUtils.isServerEntry(registry, entry)) {
                Identifier key = registry.getKey(entry);
                if (key != null) {
                    into.add(key.getNamespace());
                }
            }
        }
    }

    private static void clearNamespaces(ServerConfigurationPacketListenerImpl handler) {
        try {
            if (connectionOf(handler) instanceof NativeItemConnection state) {
                state.polymerPatcher$setSyncedItemNamespaces(Set.of());
                state.polymerPatcher$clearNumberings();
            }
        } catch (Throwable ignored) {
            // Nothing was set, then
        }
    }

    private static @Nullable Connection connectionOf(ServerCommonPacketListenerImpl handler) {
        return ((ServerCommonPacketListenerImplAccessor) handler).polymerPatcher$connection();
    }

    private static @Nullable UUID ownerId(ServerConfigurationPacketListenerImpl handler) {
        try {
            return handler.getOwner().id();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String ownerName(ServerConfigurationPacketListenerImpl handler) {
        try {
            return handler.getOwner().name();
        } catch (Throwable e) {
            return "a joining player";
        }
    }

    /** The mods behind these namespaces with the versions this server runs, for saying what is needed. */
    private static String versions(Set<String> namespaces) {
        return namespaces.stream()
            .map(namespace -> FabricLoader.getInstance().getModContainer(namespace)
                .map(mod -> mod.getMetadata().getName() + " " + mod.getMetadata().getVersion().getFriendlyString())
                .orElse(namespace))
            .collect(Collectors.joining(", "));
    }
}
