package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which of the server's mods a player already has, once, and then never changes its mind.
 * <p>
 * Everything this mod does to a packet exists for a client that has not got the mod the packet is
 * about: a modded mob becomes an item display, a modded item becomes a trial key, tracked data is
 * renumbered. To a client that <i>does</i> have the mod, all of that is wrong.
 * <p>
 * <b>The answer has to be the same everywhere, and it has to stop changing.</b> Four separate things
 * ask this question about one mob - what the registry says its type is, what type the spawn packet
 * carries, which fields its updates may hold, and whether a stand-in is drawn for it - and they are
 * asked at different moments. A client declares its channels partway through joining, so asking
 * afresh each time gave "no" to the first question and "yes" to the next: the player was handed a
 * marker, which has room for the eight fields every entity has, and then sent a raccoon's fields.
 * Field nine has nowhere to go and the connection ends there.
 * <p>
 * So the answer is worked out on the first question asked about a player and kept for as long as they
 * are connected. Being decided too early is harmless - it means "not native", and they are shown the
 * stand-ins everybody else sees. Being decided twice is not.
 */
public final class NativeClients {

    private NativeClients() {
    }

    /** What was decided about each connected player, by the first question asked about them. */
    private static final Map<Connection, Set<String>> DECIDED = new ConcurrentHashMap<>();

    /**
     * The namespaces a client has announced, written down the moment it announces them.
     * <p>
     * Asking the play connection what a client can receive only answers once the client has got as far
     * as saying so, and the first displays go out before that. A player who really does have Alex's
     * Caves was therefore read as vanilla for the first second of their session - long enough for the
     * item displays around their spawn to be renumbered to a layout their client does not use, and for
     * the first one that moved to end the connection. A second later the right answer arrived, to a
     * player who was already gone.
     * <p>
     * A client announces its channels during configuration, which is finished before it is shown
     * anything at all. Listened for there, the answer is ready before the first packet that needs it.
     */
    private static final Map<Connection, Set<String>> ANNOUNCED = new ConcurrentHashMap<>();

    /** Starts listening for what clients say about themselves. Called once, as the server starts. */
    public static void init() {
        net.fabricmc.fabric.api.networking.v1.ClientboundConfigurationChannelEvents.REGISTER.register(
            (handler, sender, server, channels) -> note(connection(handler), channels));
        net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents.REGISTER.register(
            (handler, sender, server, channels) -> note(connection(handler), channels));
    }

    /**
     * The channel Fabric reconciles registries over.
     * <p>
     * Worth watching for by name rather than by namespace. Sending a player a modded item means sending
     * a number out of this server's item registry, which only means the same thing at the other end if
     * their registry was made to agree with ours - and this is the thing that makes it agree. Fabric
     * refuses the connection outright when it cannot, so a player who is in the game has already passed
     * that check; seeing the channel is what turns that from an assumption into something observed.
     */
    private static final Identifier REGISTRY_SYNC = Identifier.fromNamespaceAndPath("fabric", "registry/sync");

    /** Players whose registries were reconciled against this server's, by name rather than by guess. */
    private static final Set<Connection> RECONCILED = ConcurrentHashMap.newKeySet();

    private static void note(@Nullable Connection who, java.util.List<Identifier> channels) {
        if (who == null) {
            return;
        }
        Set<String> namespaces = ANNOUNCED.computeIfAbsent(who, key -> ConcurrentHashMap.newKeySet());
        for (Identifier channel : channels) {
            namespaces.add(channel.getNamespace());
            if (channel.equals(REGISTRY_SYNC)) {
                RECONCILED.add(who);
            }
        }
    }

    /**
     * Whether this player's registries were reconciled against this server's.
     * <p>
     * Recorded rather than relied upon: this is not used to decide anything, because a player can be
     * perfectly well reconciled without ever announcing the channel and refusing them on that basis
     * would be worse than the fault it guards against. It is here so the log can say which it was.
     */
    public static boolean registriesReconciled(@Nullable ServerPlayer player) {
        Connection connection = connection(player);
        return connection != null && RECONCILED.contains(connection);
    }

    /** The physical session, rather than the account using it. Two sessions may overlap during relog. */
    private static @Nullable Connection connection(Object handler) {
        try {
            return ((me.drex.polymerpatcher.mixin.sync.ServerCommonPacketListenerImplAccessor) handler)
                .polymerPatcher$connection();
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not identify a client's physical connection", e);
        }
        return null;
    }

    private static @Nullable Connection connection(@Nullable ServerPlayer player) {
        return player == null || player.connection == null ? null : connection(player.connection);
    }

    /**
     * Which of the detectable mods this player has, decided once and remembered.
     */
    private static Set<String> decide(ServerPlayer player) {
        Connection connection = connection(player);
        if (connection == null) {
            return Set.of();
        }
        Set<String> settled = DECIDED.get(connection);
        if (settled != null) {
            return settled;
        }

        // A client announces its channels a moment after it arrives, and until it has there is nothing
        // here to read. Settling on "none of them" in that moment was taken for the harmless answer,
        // on the grounds that it only means showing somebody the stand-ins everybody else sees.
        //
        // For tracked data it is not harmless at all, and it is the opposite way round from what that
        // reasoning assumed. A player who really does have Alex's Caves has an Entity of twelve fields,
        // so every display the server sends them is numbered from twelve. Told they were vanilla, this
        // renumbered those displays down to eight to match a layout their client does not use - and the
        // first item display that so much as moved handed them a float where their client keeps an
        // integer, and ended the connection. Deciding early did not cost them a stand-in; it cost them
        // the server.
        //
        // So nothing is settled while the client has said nothing. The answer stays provisional and is
        // asked again next time, and is only written down - once, for good - after the client speaks.
        Set<Identifier> channels = ServerPlayNetworking.getSendable(player);
        Set<String> announced = ANNOUNCED.getOrDefault(connection, Set.of());
        // What the client said about its mods while joining, which it says before it is in the game at all.
        // Waiting for channels alone left a player with Alex's Caves unsettled for their first seconds, and
        // their own fields went out numbered for a vanilla client in the meantime - the first effect they
        // were given put a particle list where their client keeps health, and ended the connection
        Set<String> packs = modsFromKnownPacks(player);
        if (channels.isEmpty() && announced.isEmpty() && packs.isEmpty()) {
            return Set.of();
        }

        // Judged against every mod this server patches, not only the ones it happens to listen to.
        // A mod that only ever sends to a client registers no receiver here, so asking what this
        // server listens for missed them entirely - Variants & Ventures and Crop Critters both, even
        // though their clients announce a channel of their own that says plainly they are installed
        Set<String> found = new HashSet<>();
        for (Identifier channel : channels) {
            if (PolymerPatcher.PATCHED_MODS.contains(channel.getNamespace())) {
                found.add(channel.getNamespace());
            }
        }
        // What it said while configuring counts the same as what it can receive now, and is the half
        // that arrives in time to matter
        for (String namespace : announced) {
            if (PolymerPatcher.PATCHED_MODS.contains(namespace)) {
                found.add(namespace);
            }
        }
        for (String mod : packs) {
            if (PolymerPatcher.PATCHED_MODS.contains(mod)) {
                found.add(mod);
            }
        }

        Set<String> decided = Set.copyOf(found);
        Set<String> raced = DECIDED.putIfAbsent(connection, decided);
        if (raced != null) {
            return raced;
        }

        PolymerPatcher.LOGGER.info("{} will be treated as having {} for as long as they are connected",
            player.getGameProfile().name(), decided.isEmpty() ? "none of the patched mods" : decided);
        return decided;
    }

    /**
     * The mods this player's client reported having at this server's exact version while it joined.
     * <p>
     * Read off the connection rather than filed by player: an account joining on a second client while
     * its first is still being thrown off shares one id between the two, and the first one leaving wiped
     * what the second had just said.
     */
    private static Set<String> modsFromKnownPacks(ServerPlayer player) {
        try {
            if (player.connection != null
                && ((me.drex.polymerpatcher.mixin.sync.ServerCommonPacketListenerImplAccessor) player.connection).polymerPatcher$connection()
                instanceof NativeItemConnection state) {
                return state.polymerPatcher$sameVersionMods();
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read what {}'s client said about its mods", player.getGameProfile().name(), e);
        }
        return Set.of();
    }

    /**
     * Forgets what was decided about a player, so their next visit is decided afresh.
     */
    public static void forget(ServerPlayer player) {
        Connection connection = connection(player);
        if (connection != null) {
            DECIDED.remove(connection);
            ANNOUNCED.remove(connection);
            RECONCILED.remove(connection);
        }
    }

    /**
     * Whether anything is yet known about what this player has.
     * <p>
     * A client says what it carries a second or three after it arrives, and the displays around its
     * spawn are sent the instant it does. Everything asked in between was answered "vanilla", because
     * that was the only answer available - and for a player who is not vanilla that answer is not a
     * worse-looking world, it is a disconnect. Knowing that the question cannot yet be answered is
     * worth more than answering it wrongly.
     */
    public static boolean settled(@Nullable ServerPlayer player) {
        Connection connection = connection(player);
        return connection != null && DECIDED.containsKey(connection);
    }

    /**
     * Whether this player actually has one particular mod, by its namespace.
     * <p>
     * Deliberately not behind the setting that {@link #has} respects. That setting is a choice about
     * whether to show somebody the real mob or the stand-in, and either is a picture they can draw.
     * Whether their client has the code to draw a screen is not a choice at all - it is a fact about
     * what they installed, and answering it with a preference would shut a station in the face of
     * somebody perfectly able to use it.
     */
    public static boolean carries(@Nullable ServerPlayer player, String modId) {
        return player != null && decide(player).contains(modId);
    }

    /**
     * Whether this player should be shown the real mob rather than a stand-in.
     * <p>
     * Behind the setting, because being wrong here costs the player their connection: the type they
     * are sent has to be one the registry they were given agrees about.
     */
    public static boolean has(@Nullable ServerPlayer player, String namespace) {
        if (player == null || !ConfigManager.config().entities.nativeClients) {
            return false;
        }
        return decide(player).contains(namespace);
    }

    /**
     * Whether this player numbers an entity's fields exactly as this server does.
     * <p>
     * Asked only of the mods that actually add tracked data, which is what decides the numbering and is
     * a much smaller set than "every mod this server patches". Asking the larger question is what broke
     * logging in when Enderscape was added: it adds no fields, but every player without it was declared
     * to number theirs differently, and their own player entity was then renumbered down to the layout
     * the game ships with. For a player who has Alex's Mobs - which does add a field, and so shifts
     * everything below it - that put the skin settings where the main hand goes, and the client dropped
     * the connection rather than read it.
     * <p>
     * All of them rather than any, because tracked data is renumbered for the whole entity: one such mod
     * missing shifts the numbering, and a client that has the rest still needs the translation.
     * <p>
     * <b>Not behind the setting</b>, unlike {@link #has}. Which mob a player is shown is a choice; how
     * their fields are numbered is not. Turning the setting off has to stop them seeing real mobs, not
     * start mistranslating them.
     */
    public static boolean hasAll(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }

        Set<String> required = TrackedDataMods.shifting();
        return !required.isEmpty() && decide(player).containsAll(required);
    }
    /**
     * Which of the mods that shift the numbering this player actually has.
     * <p>
     * Not simply whether they have them all. A player with some of them numbers their fields the way
     * the game ships plus whatever those mods added, which is neither what this server uses nor bare
     * vanilla - and renumbering them down to bare vanilla is wrong by exactly the fields they do have.
     * That is what put a player's skin settings where their main hand goes and dropped them.
     */
    public static Set<String> shiftingModsOf(@Nullable ServerPlayer player) {
        if (player == null) {
            return Set.of();
        }
        Set<String> shifting = TrackedDataMods.shifting();
        Set<String> has = new java.util.HashSet<>(decide(player));
        has.retainAll(shifting);
        return Set.copyOf(has);
    }
}
