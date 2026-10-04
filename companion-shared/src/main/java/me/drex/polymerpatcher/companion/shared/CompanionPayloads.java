package me.drex.polymerpatcher.companion.shared;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What the server and the companion say to each other while a player joins.
 * <p>
 * All of it happens during configuration, before the player is in the world, so by the time the first
 * chunk arrives both sides already agree on whether the client's blocks match the server's:
 * <ol>
 *   <li>the server asks ({@link Request}), which only a client with the companion can hear;</li>
 *   <li>the client answers with the manifest it registered for this server, if any ({@link Hello});</li>
 *   <li>if that is not the server's current one, the server sends the current one ({@link Manifest}),
 *       which the client saves for its next launch;</li>
 *   <li>the server says whether the client's blocks are in use this session ({@link Status}).</li>
 * </ol>
 */
public final class CompanionPayloads {

    private CompanionPayloads() {
    }

    /** Bumped when these payloads change shape. A mismatch means "no companion", never a disconnect. */
    public static final int PROTOCOL = 1;

    public static final String NAMESPACE = "polymer-patcher";

    /** Large enough for a server with every block anyone has ever made, compressed. */
    public static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    public record Request(int protocol) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(id("companion/request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Request::protocol, Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * @param manifestHash the manifest this client registered blocks from for this server, or empty
     * @param registered   whether those blocks were actually registered - false when the manifest was
     *                     saved but the game has not been restarted since, or when it clashed with another
     */
    public record Hello(int protocol, String manifestHash, boolean registered) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("companion/hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Hello::protocol,
            ByteBufCodecs.STRING_UTF8, Hello::manifestHash,
            ByteBufCodecs.BOOL, Hello::registered,
            Hello::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Manifest(String hash, byte[] data) implements CustomPacketPayload {
        public static final Type<Manifest> TYPE = new Type<>(id("companion/manifest"));
        public static final StreamCodec<FriendlyByteBuf, Manifest> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Manifest::hash,
            ByteBufCodecs.byteArray(MAX_MANIFEST_BYTES), Manifest::data,
            Manifest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * @param active whether the client's blocks are used this session
     * @param hash   the server's current manifest
     */
    public record Status(boolean active, String hash) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(id("companion/status"));
        public static final StreamCodec<FriendlyByteBuf, Status> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Status::active,
            ByteBufCodecs.STRING_UTF8, Status::hash,
            Status::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
