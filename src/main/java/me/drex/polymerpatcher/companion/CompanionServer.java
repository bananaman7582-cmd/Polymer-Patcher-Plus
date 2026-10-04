package me.drex.polymerpatcher.companion;

import eu.pb4.polymer.networking.api.server.PolymerServerNetworking;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import me.drex.polymerpatcher.companion.shared.CompanionPayloads;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.mixin.sync.ServerCommonPacketListenerImplAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The server half of the companion: which players' clients hold real copies of this server's blocks.
 * <p>
 * A player with the companion mod, who has restarted since their client was last given this server's
 * manifest, has every modded block registered under its real name. Polymer's client then swaps the
 * vanilla carrier for that block at every position, so for them the carriers - and everything done to
 * cover for a carrier being the wrong block - are not needed:
 * <ul>
 *   <li>a display drawing a block's model would draw it a second time over their real block;</li>
 *   <li>colliding them against the carrier's shape would fight their client, which uses the real one;</li>
 *   <li>timing their mining on the server, with mining fatigue to stop their client running ahead, is
 *       only needed because a client would otherwise time it from the carrier's hardness.</li>
 * </ul>
 * Everything here answers "no" for a player whose client does not match, so they get exactly what they
 * got before.
 */
public final class CompanionServer {

    private CompanionServer() {
    }

    private static final class Session {
        final boolean active;
        /** Set once Polymer has told the client which server blocks its own blocks stand for. */
        volatile boolean tagsReady;

        Session(boolean active) {
            this.active = active;
        }
    }

    private static final Map<Connection, Session> SESSIONS = new ConcurrentHashMap<>();

    private static volatile CompanionManifestBuilder.@Nullable Built built;

    private record Task() implements ConfigurationTask {
        static final Type TYPE = new Type("polymer-patcher:companion");

        @Override
        public void start(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking.createClientboundPacket(
                new CompanionPayloads.Request(CompanionPayloads.PROTOCOL)));
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.clientboundConfiguration().register(CompanionPayloads.Request.TYPE, CompanionPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().registerLarge(CompanionPayloads.Manifest.TYPE, CompanionPayloads.Manifest.CODEC,
            CompanionPayloads.MAX_MANIFEST_BYTES + 1024);
        PayloadTypeRegistry.clientboundConfiguration().register(CompanionPayloads.Status.TYPE, CompanionPayloads.Status.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(CompanionPayloads.Hello.TYPE, CompanionPayloads.Hello.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!ConfigManager.config().companion.enabled) {
                return;
            }
            try {
                long started = System.nanoTime();
                built = CompanionManifestBuilder.build(server);
                PolymerPatcher.LOGGER.info("Companion manifest ready: {} block(s), {} fluid(s), {} KiB, {} ({} ms)",
                    built.manifest().blocks.size(), built.manifest().fluids.size(), built.bytes().length / 1024,
                    built.hash(), (System.nanoTime() - started) / 1_000_000);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.error("Could not build the companion manifest; players with the companion mod will be treated as vanilla", e);
            }
        });

        ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> {
            if (built != null && ServerConfigurationNetworking.canSend(listener, CompanionPayloads.Request.TYPE)) {
                listener.addTask(new Task());
            }
        });

        ServerConfigurationNetworking.registerGlobalReceiver(CompanionPayloads.Hello.TYPE, (payload, context) -> {
            var listener = context.packetListener();
            var current = built;
            try {
                Connection connection = connection(listener);
                boolean active = current != null
                    && payload.protocol() == CompanionPayloads.PROTOCOL
                    && payload.registered()
                    && current.hash().equals(payload.manifestHash());
                if (connection != null) {
                    SESSIONS.put(connection, new Session(active));
                }
                if (current != null && payload.protocol() == CompanionPayloads.PROTOCOL && !current.hash().equals(payload.manifestHash())) {
                    context.responseSender().sendPacket(new CompanionPayloads.Manifest(current.hash(), current.bytes()));
                }
                context.responseSender().sendPacket(new CompanionPayloads.Status(active, current == null ? "" : current.hash()));
                PolymerPatcher.LOGGER.info("{} has the Polymer Patcher++ companion: {}", listener.getOwner().name(),
                    active ? "using this server's real blocks"
                        : current != null && current.hash().equals(payload.manifestHash())
                        ? "has this server's blocks saved, and will use them after a restart"
                        : "sent this server's blocks, which it will use after a restart");
            } finally {
                listener.completeTask(Task.TYPE);
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> {
            Connection connection = connection(listener);
            if (connection != null && !connection.isConnected()) {
                SESSIONS.remove(connection);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Connection connection = connection(handler);
            if (connection != null) {
                SESSIONS.remove(connection);
            }
        });

        // Polymer tells its client which server blocks exist only once the player is in the game, after
        // the tags were already sent while they joined. Tags name blocks by the server's numbers, and
        // until that list has arrived the client cannot tell which of its own blocks a number means - so
        // tags naming modded blocks are held back until then and sent again now, which is what puts the
        // real blocks into mineable/pickaxe and the rest and makes the right tool the right tool.
        PolymerServerNetworking.ON_PLAY_SYNC.register((handler, fullSync) -> {
            Connection connection = connection(handler);
            Session session = connection == null ? null : SESSIONS.get(connection);
            if (session == null || !session.active) {
                return;
            }
            MinecraftServer server = handler.getPlayer().level().getServer();
            // Queued, so it goes out after everything Polymer sends from this same event
            server.execute(() -> {
                session.tagsReady = true;
                handler.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
            });
        });
    }

    private static @Nullable Connection connection(ServerCommonPacketListenerImpl listener) {
        try {
            return ((ServerCommonPacketListenerImplAccessor) listener).polymerPatcher$connection();
        } catch (Throwable e) {
            return null;
        }
    }

    private static @Nullable Session session(@Nullable ServerPlayer player) {
        if (player == null || player.connection == null) {
            return null;
        }
        Connection connection = connection(player.connection);
        return connection == null ? null : SESSIONS.get(connection);
    }

    private static @Nullable Session session(@Nullable PacketContext context) {
        if (context == null) {
            return null;
        }
        try {
            Connection connection = context.get(PacketContext.CONNECTION);
            return connection == null ? null : SESSIONS.get(connection);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Whether this player's client holds this server's real blocks this session. */
    public static boolean active(@Nullable ServerPlayer player) {
        Session session = session(player);
        return session != null && session.active;
    }

    /** Whether this player's client has its own copy of this block. */
    public static boolean hasBlock(@Nullable ServerPlayer player, Block block) {
        var current = built;
        return current != null && current.blocks().contains(block) && active(player);
    }

    /**
     * Whether this player's client draws this block itself, so a display drawing its model would only
     * draw it twice.
     */
    public static boolean drawsItself(@Nullable ServerPlayer player, @Nullable BlockState state) {
        var current = built;
        return state != null && current != null && current.drawnByClient().contains(state) && active(player);
    }

    /**
     * Whether tags naming this server's modded blocks can be sent to whoever this packet is for.
     * <p>
     * Only after Polymer's own list has reached them; see the note in {@link #init()}.
     */
    public static boolean tagsReady(@Nullable PacketContext context, Block block) {
        Session session = session(context);
        var current = built;
        return session != null && session.active && session.tagsReady && current != null && current.blocks().contains(block);
    }

    /** As {@link #tagsReady(PacketContext, Block)}, for a fluid. */
    public static boolean tagsReady(@Nullable PacketContext context, net.minecraft.world.level.material.Fluid fluid) {
        Session session = session(context);
        var current = built;
        return session != null && session.active && session.tagsReady && current != null && current.fluids().contains(fluid);
    }

    /** The manifest, for the command that reports on it. */
    public static CompanionManifestBuilder.@Nullable Built built() {
        return built;
    }

    public static CompanionManifest manifestOrNull() {
        var current = built;
        return current == null ? null : current.manifest();
    }
}
