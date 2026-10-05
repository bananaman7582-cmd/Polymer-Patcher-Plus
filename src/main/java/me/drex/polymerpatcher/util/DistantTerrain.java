package me.drex.polymerpatcher.util;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.impl.PolymerImplUtils;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What a modded block looks like in Voxy's distant terrain, for the server-side mods that send it.
 * <p>
 * Voxy draws terrain beyond view distance from data a server mod sends it - Voxy World Gen V2 and Voxy Server
 * Side (LOD Server Support) on this server - and both read the server's own world, where a modded block is
 * itself. A client without the mod cannot have that block. Voxy World Gen V2 writes its raw server number,
 * which on the client is some other block entirely: a forest of modded leaves came out as a wall of end-like
 * blocks. Voxy Server Side writes its name, which the client cannot find, and falls back to stone.
 * <p>
 * Up close, the same player is sent each modded block as its carrier - a vanilla block the resource pack
 * re-skins to look like it. So distant terrain is sent that carrier too, and the pack draws it in the distance
 * exactly as it does nearby.
 * <p>
 * A block drawn by a display rather than a carrier (stairs once their carriers ran out, a block only its own
 * renderer can draw) sits on a barrier, which Voxy would draw as a hole. Displays do not reach distant
 * terrain, so those are sent as the nearest-coloured plain vanilla block instead: a single-state full cube,
 * which Polymer can never have taken as a carrier, so the pack cannot have re-skinned it into something else.
 * Something you walk through with no carrier is left out, as a hole is what it would be from that far away.
 */
public final class DistantTerrain {
    private DistantTerrain() {
    }

    private static final Map<BlockState, BlockState> SENT_AS = new ConcurrentHashMap<>();
    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();
    private static volatile List<BlockState> plainBlocks;
    private static volatile String fingerprint;

    public static boolean enabled() {
        return ConfigManager.config().blocks.distantTerrainCarriers;
    }

    /** Whether a client could not be sent this state as it is. */
    public static boolean needsStandIn(BlockState state) {
        return PolymerImplUtils.POLYMER_STATES.contains(state);
    }

    /** The state distant terrain shows for this one; the same object for anything a client already has. */
    public static BlockState shownAs(BlockState state) {
        if (!needsStandIn(state)) {
            return state;
        }
        return SENT_AS.computeIfAbsent(state, DistantTerrain::standInFor);
    }

    private static BlockState standInFor(BlockState state) {
        try {
            // Without a packet context Polymer answers with the carrier itself rather than a numbering token
            BlockState carrier = PolymerBlockUtils.getPolymerBlockState(BlockSyncCheck.asClientTwin(state), null);
            // Readable is the test, not "not modded": a vanilla block a mod gave an extra property comes back
            // as its ordinary twin, which every client has
            if (carrier != null && BlockSyncCheck.isClientReadableWorldState(carrier)
                && !carrier.is(Blocks.BARRIER) && !carrier.is(Blocks.STRUCTURE_VOID)) {
                return carrier;
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not work out the carrier of {} for distant terrain", state, e);
        }
        return approximation(state);
    }

    /** A plain vanilla block of the nearest colour, or nothing for a block you walk through. */
    private static BlockState approximation(BlockState state) {
        boolean solid;
        try {
            solid = state.canOcclude()
                || !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable e) {
            solid = true;
        }
        if (!solid || state.getRenderShape() == RenderShape.INVISIBLE) {
            return state.getFluidState().is(FluidTags.WATER)
                ? Blocks.WATER.defaultBlockState()
                : Blocks.AIR.defaultBlockState();
        }

        int colour = colourOf(state);
        BlockState best = Blocks.STONE.defaultBlockState();
        if (colour < 0) {
            return best;
        }
        long bestDistance = Long.MAX_VALUE;
        for (BlockState plain : plainBlocks()) {
            long distance = colourDistance(colour, colourOf(plain));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = plain;
            }
        }
        return best;
    }

    /** Every vanilla full cube with exactly one state: nothing Polymer could have used as a carrier. */
    private static List<BlockState> plainBlocks() {
        List<BlockState> known = plainBlocks;
        if (known == null) {
            List<BlockState> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null || !RegistryPatcher.isVanillaId(id) || block instanceof EntityBlock
                    || block.getStateDefinition().getPossibleStates().size() != 1) {
                    continue;
                }
                BlockState state = block.defaultBlockState();
                try {
                    if (!needsStandIn(state) && BlockSyncCheck.isClientReadableWorldState(state)
                        && state.getRenderShape() == RenderShape.MODEL && state.canOcclude()
                        && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        && colourOf(state) > 0) {
                        found.add(state);
                    }
                } catch (Throwable ignored) {
                    // A block that will not say is simply not offered
                }
            }
            plainBlocks = known = List.copyOf(found);
        }
        return known;
    }

    private static int colourOf(BlockState state) {
        try {
            return state.getMapColor(PolymerCommonUtils.getFakeWorld(), BlockPos.ZERO).col;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static long colourDistance(int a, int b) {
        long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        long db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /**
     * This block palette with every state a client cannot have replaced by what distant terrain shows for it,
     * or the same container when there is nothing to replace. Anything that is not a block palette is
     * returned as it is.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static PalettedContainer<?> shownAs(PalettedContainer<?> container, String sentBy) {
        if (!enabled() || !(container.get(0, 0, 0) instanceof BlockState)) {
            return container;
        }
        PalettedContainer<BlockState> blocks = (PalettedContainer) container;
        if (!blocks.maybeHas(DistantTerrain::needsStandIn)) {
            return container;
        }
        PalettedContainer<BlockState> copy = blocks.copy();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = copy.get(x, y, z);
                    BlockState shown = shownAs(state);
                    if (shown != state) {
                        copy.getAndSetUnchecked(x, y, z, shown);
                    }
                }
            }
        }
        announce(sentBy);
        return copy;
    }

    /** The global block number distant terrain shows for this one, for a mod that names blocks by number. */
    public static int shownAs(int globalId) {
        if (!enabled()) {
            return globalId;
        }
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(globalId);
        if (state == null || !needsStandIn(state)) {
            return globalId;
        }
        int shown = Block.BLOCK_STATE_REGISTRY.getId(shownAs(state));
        if (shown < 0) {
            return globalId;
        }
        announce("Voxy Server Side");
        return shown;
    }

    /**
     * Builds what is about to be sent to this player as if it were a chunk packet to them, so Polymer numbers
     * every block the way their client does.
     */
    public static <T> T buildFor(ServerPlayer player, Supplier<T> build) {
        if (!enabled() || player == null || player.connection == null) {
            return build.get();
        }
        return PacketContext.supplyWithContext((PacketContextProvider) (Object) player.connection, build);
    }

    /**
     * A record of what modded blocks are being shown as, for a mod that keeps distant terrain on disk and has
     * to know when what it kept no longer matches. Carriers move between restarts - the blocks a world uses
     * most are given them first - so terrain kept from before would show the wrong block on them.
     */
    public static String fingerprint() {
        String known = fingerprint;
        if (known == null) {
            long hash = 0xcbf29ce484222325L;
            if (enabled()) {
                for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                    if (!needsStandIn(state)) {
                        continue;
                    }
                    String entry = state + ">" + shownAs(state);
                    for (int i = 0; i < entry.length(); i++) {
                        hash ^= entry.charAt(i);
                        hash *= 0x100000001b3L;
                    }
                }
            }
            fingerprint = known = "polymer-patcher:distant-terrain=" + Long.toHexString(hash);
        }
        return known;
    }

    /** The list a mod fingerprints its registry with, with {@link #fingerprint} added to it. */
    public static Iterable<String> withFingerprint(Iterable<String> identities) {
        if (!enabled()) {
            return identities;
        }
        List<String> all = new ArrayList<>();
        identities.forEach(all::add);
        all.add(fingerprint());
        return all;
    }

    private static void announce(String sentBy) {
        if (ANNOUNCED.add(sentBy)) {
            PolymerPatcher.LOGGER.info("Distant terrain from {} now shows modded blocks as the carriers players see up close, "
                + "so Voxy draws them with the resource pack instead of as the wrong block. Said once.", sentBy);
        }
    }
}
