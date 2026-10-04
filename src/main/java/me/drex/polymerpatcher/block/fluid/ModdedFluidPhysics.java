package me.drex.polymerpatcher.block.fluid;

import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ManualAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gives an unmodded client water movement only in the custom-fluid cells touching its player.
 *
 * <p>The world itself stays on the dry, chunk-batched carriers used by {@link ModdedFluidBlock}; that
 * is what removed the catastrophic lake-sized display count. A client nevertheless decides swimming
 * locally from the block states it sees. Replacing at most a few cells inside its own body with a
 * waterlogged carrier supplies that fact without changing the rest of the pool or the server.</p>
 */
public final class ModdedFluidPhysics {
    private static final Map<UUID, Set<BlockPos>> SHOWN_AS_WATER = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, Overlay>> OVERLAYS = new HashMap<>();

    private ModdedFluidPhysics() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Set<UUID> connected = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                connected.add(player.getUUID());
                update(player);
            }
            SHOWN_AS_WATER.keySet().removeIf(id -> !connected.contains(id));
            OVERLAYS.entrySet().removeIf(entry -> {
                if (connected.contains(entry.getKey())) {
                    return false;
                }
                entry.getValue().values().forEach(Overlay::destroy);
                return true;
            });
        });
    }

    public static void forget(ServerPlayer player) {
        SHOWN_AS_WATER.remove(player.getUUID());
        Map<BlockPos, Overlay> overlays = OVERLAYS.remove(player.getUUID());
        if (overlays != null) {
            overlays.values().forEach(Overlay::destroy);
        }
    }

    private static void update(ServerPlayer player) {
        Set<BlockPos> wanted = new HashSet<>();
        Map<BlockPos, BlockState> physics = new HashMap<>();
        Map<BlockPos, FluidSkin> skins = new HashMap<>();
        AABB box = player.getBoundingBox().deflate(1.0E-4);

        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState actual = player.level().getBlockState(pos);
                    ModdedFluidBlock patch = ModdedFluidBlock.forBlock(actual.getBlock());
                    if (patch != null && patch.needsWaterPhysics(player)) {
                        BlockPos immutable = pos.immutable();
                        wanted.add(immutable);
                        physics.put(immutable, patch.physicsCarrier(actual));
                        // Cover every locally-water cell, including the one containing the camera. Leaving
                        // that one bare exposes the backs of the surrounding one-sided surface models; from
                        // inside the liquid it looks like rectangular holes through to the ground. The
                        // occupied model already has inward-facing sheets specifically for this view.
                        skins.put(immutable, new FluidSkin(patch, actual));
                    }
                }
            }
        }

        Set<BlockPos> previous = SHOWN_AS_WATER.getOrDefault(player.getUUID(), Set.of());

        // A waterlogged carrier is the only vanilla block state that gives an unmodified client swim
        // physics, and the client inevitably renders its water too. Keep the custom skin in front of
        // that local water with one targeted display per intersected body cell. Cover the eye cell as
        // well as the feet: a player in a two-block-deep column otherwise sees unskinned water above.
        // This stays bounded by the player's body, not the size of the surrounding lake.
        Map<BlockPos, Overlay> overlays = OVERLAYS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        overlays.entrySet().removeIf(entry -> {
            if (skins.containsKey(entry.getKey())) {
                return false;
            }
            entry.getValue().destroy();
            return true;
        });
        for (Map.Entry<BlockPos, FluidSkin> entry : skins.entrySet()) {
            // Use every locally-water cell for connectivity. Otherwise the cell below the player's eyes
            // grows a false top face inside a deep pool.
            int open = openSides(entry.getKey(), wanted);
            Overlay overlay = overlays.get(entry.getKey());
            if (overlay == null || !overlay.matchesLevel((ServerLevel) player.level())) {
                if (overlay != null) {
                    overlay.destroy();
                }
                overlay = new Overlay(player, entry.getKey(), entry.getValue(), open);
                overlays.put(entry.getKey(), overlay);
            } else {
                // A draining/flowing fluid changes LEVEL repeatedly. Updating the existing display
                // item avoids an entity removal/spawn gap (and a flash of the old liquid or water)
                // for every step of that animation.
                overlay.show(entry.getValue(), open);
            }
            overlay.tick();
        }

        for (BlockPos pos : previous) {
            if (!wanted.contains(pos)) {
                // Remove the old skin before restoring the world block. This matters when a drained
                // cell immediately exposes a different custom liquid below or behind it.
                player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
            }
        }

        // Spawn the custom skin before revealing the local water carrier. Reversing this order left a
        // one-packet window in which entering a cell visibly flashed blue water. Existing cells only
        // need an occasional repair after a chunk/neighbour refresh; re-sending them every tick made
        // the client repeatedly rebuild the block and caused intermittent water/untextured flashes.
        boolean repair = player.tickCount % 20 == 0;
        for (Map.Entry<BlockPos, BlockState> entry : physics.entrySet()) {
            if (repair || !previous.contains(entry.getKey())) {
                player.connection.send(new ClientboundBlockUpdatePacket(entry.getKey(), entry.getValue()));
            }
        }

        if (wanted.isEmpty()) {
            SHOWN_AS_WATER.remove(player.getUUID());
            OVERLAYS.remove(player.getUUID());
        } else {
            SHOWN_AS_WATER.put(player.getUUID(), Set.copyOf(wanted));
        }
    }

    /** Which faces of this cell look out of the player's own cells, and so need the skin. */
    private static int openSides(BlockPos pos, Set<BlockPos> local) {
        int open = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (!local.contains(pos.relative(direction))) {
                // Written into the model on the side that the display's half-turn brings round to face
                // this way; the same name on both was every open wall drawn on the opposite side
                open |= FluidModels.bit(FluidModels.modelSide(direction));
            }
        }
        return open;
    }

    private record FluidSkin(ModdedFluidBlock fluid, BlockState state) {
        private int level() {
            return fluid.level(state);
        }
    }

    private static final class Overlay {
        private final ServerLevel level;
        private ModdedFluidBlock fluid;
        private int fluidLevel;
        private int open;
        private final ElementHolder holder = new ElementHolder();
        private final BlockPos pos;
        private final ManualAttachment attachment;
        private final ItemDisplayElement element;

        private Overlay(ServerPlayer player, BlockPos pos, FluidSkin skin, int open) {
            this.level = (ServerLevel) player.level();
            this.fluid = skin.fluid();
            this.fluidLevel = skin.level();
            this.open = open;
            this.pos = pos.immutable();

            element = ItemDisplayElementUtil.createSimple(fluid.occupiedModel(skin.state(), this.pos, open));
            element.setDisplaySize(1.5F, 1.5F);
            // Sit just outside the carrier geometry so its translucent custom skin wins the depth
            // comparison against the waterlogged block, including from inside the cell.
            element.setScale(new Vector3f(1.003F));
            holder.addElement(element);
            this.attachment = new ManualAttachment(holder, level, () -> Vec3.atCenterOf(pos));
            this.attachment.startWatching(player);
        }

        private boolean matchesLevel(ServerLevel level) {
            return this.level == level;
        }

        private void show(FluidSkin skin, int open) {
            if (fluid == skin.fluid() && fluidLevel == skin.level() && this.open == open) {
                return;
            }
            fluid = skin.fluid();
            fluidLevel = skin.level();
            this.open = open;
            element.setItem(fluid.occupiedModel(skin.state(), pos, open).get());
            element.tick();
        }

        private void tick() {
            attachment.tick();
        }

        private void destroy() {
            holder.destroy();
        }
    }
}
