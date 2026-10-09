package me.drex.polymerpatcher.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.impl.BlockExtBlockMapper;
import eu.pb4.polymer.blocks.impl.PolymerBlocksInternal;
import eu.pb4.polymer.blocks.impl.VanillaBlockPropertiesPredicate;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Carriers in shapes Polymer has no kind for.
 * <p>
 * Polymer's kinds are a fixed list, and a modded block is matched to whichever of them has the nearest shape:
 * a block six pixels tall was given a slab, eight, or a closed trapdoor, three, and players walked into the
 * difference. The game has a few more shapes with states a client cannot tell apart - a daylight detector is six
 * pixels tall and has fifteen states for each look, one per light level - so this mod keeps them as kinds of its
 * own. Each is only used where it fits a block's shape better than anything Polymer has, or where what Polymer has
 * for that shape has run out.
 * <p>
 * Polymer does the rest the same way as for its own carriers: a real block in a taken state is sent as its twin
 * (through the same state map, so the startup check in {@link CarrierTwins#verify} covers these too), and the
 * pack draws the taken state with the modded model. Only twins of the same block are used: a real sticky piston
 * sent as a plain one would retract on the client without pulling its block back.
 */
public final class ExtraCarriers {
    private static final double EPSILON = 1.0E-6;

    /** A free carrier, and the state its real blocks are sent as. */
    public record Twin(BlockState carrier, BlockState canonical) {
    }

    private static final class Kind {
        final String name;
        final ShapeKey collision;
        final boolean waterlogged;
        final Deque<Twin> free = new ArrayDeque<>();
        final Map<Object, BlockState> byModel = new HashMap<>();
        int capacity;

        Kind(String name, ShapeKey collision, boolean waterlogged) {
            this.name = name;
            this.collision = collision;
            this.waterlogged = waterlogged;
        }
    }

    private static final List<Kind> KINDS = new ArrayList<>();
    /** What each taken carrier is drawn as, by its block, for the pack. */
    private static final Map<Block, Map<BlockState, PolymerBlockModel[]>> TAKEN = new IdentityHashMap<>();

    private ExtraCarriers() {
    }

    static void offer(List<Twin> twins) {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Twin twin : twins) {
            BlockState carrier = twin.carrier();
            if (carrier.getBlock() != twin.canonical().getBlock() || !BlockSyncCheck.usableAsCarrier(carrier)
                || !BlockSyncCheck.isClientReadableWorldState(twin.canonical())) {
                continue;
            }
            VoxelShape collision = carrier.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (collision.isEmpty()) {
                // Nothing to walk on; Polymer's walk-through kinds already cover every such shape
                continue;
            }
            boolean waterlogged = carrier.getBlock() instanceof SimpleWaterloggedBlock
                && carrier.getValueOrElse(BlockStateProperties.WATERLOGGED, false);
            ShapeKey key = ShapeKey.of(collision);
            String name = BuiltInRegistries.BLOCK.getKey(carrier.getBlock()).getPath() + (waterlogged ? "_waterlogged" : "");
            kinds.computeIfAbsent(name + "|" + key, k -> new Kind(name, key, waterlogged)).free.add(twin);
        }
        for (Kind kind : kinds.values()) {
            kind.capacity = kind.free.size();
            KINDS.add(kind);
        }
        if (!KINDS.isEmpty()) {
            PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(ExtraCarriers::writeBlockstates);
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> report());
        }
        List<String> names = KINDS.stream().map(kind -> kind.name + " " + kind.capacity).toList();
        PolymerPatcher.LOGGER.info("Kept {} carrier kind(s) in shapes Polymer has none for: {}", KINDS.size(), names);
    }

    /**
     * A carrier from one of these kinds for a modded block's look, if one fits its shape better than Polymer's
     * nearest - or, with {@code orEqual}, at least as well, for when Polymer's have run out.
     */
    static @Nullable BlockState request(ShapeKey collision, boolean waterlogged, double polymerDistance, boolean orEqual,
                                        Object look, PolymerBlockModel[] models) {
        Kind best = null;
        double bestDistance = polymerDistance;
        for (Kind kind : KINDS) {
            if (kind.waterlogged != waterlogged) {
                continue;
            }
            double distance = collision.distanceTo(kind.collision);
            boolean better = distance + EPSILON < bestDistance || orEqual && best == null && distance <= bestDistance + EPSILON;
            if (better && (kind.byModel.containsKey(look) || !kind.free.isEmpty())) {
                best = kind;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return null;
        }
        BlockState cached = best.byModel.get(look);
        if (cached != null) {
            return cached;
        }
        Twin twin = best.free.poll();
        if (twin == null) {
            return null;
        }
        BlockExtBlockMapper.INSTANCE.stateMap.put(twin.carrier(), twin.canonical());
        TAKEN.computeIfAbsent(twin.carrier().getBlock(), k -> new IdentityHashMap<>()).put(twin.carrier(), models);
        best.byModel.put(look, twin.carrier());
        return twin.carrier();
    }

    private static void report() {
        List<String> lines = new ArrayList<>();
        for (Kind kind : KINDS) {
            lines.add(kind.name + " " + (kind.capacity - kind.free.size()) + " of " + kind.capacity);
        }
        PolymerPatcher.LOGGER.info("This mod's own carrier kinds in use: {}", lines);
    }

    /**
     * Writes the blockstate file of every block with a taken carrier: the modded model on each taken state, and
     * the game's own on every other one, spelled out state by state so nothing in the original can also match.
     */
    private static void writeBlockstates(ResourcePackBuilder builder) {
        if (!(builder instanceof DefaultRPBuilder<?> pack)) {
            return;
        }
        pack.buildEvent.register(credits -> {
            for (Map.Entry<Block, Map<BlockState, PolymerBlockModel[]>> entry : TAKEN.entrySet()) {
                Block block = entry.getKey();
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                String path = "assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json";
                try {
                    byte[] vanilla = pack.getDataOrSource(path);
                    JsonObject original = vanilla == null ? null : JsonParser.parseString(new String(vanilla, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (original == null || !original.has("variants")) {
                        PolymerPatcher.LOGGER.warn("Could not read the game's {}; its carriers will be drawn wrongly", path);
                        continue;
                    }
                    Set<Map.Entry<String, JsonElement>> vanillaVariants = original.getAsJsonObject("variants").entrySet();
                    JsonObject variants = new JsonObject();
                    for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                        PolymerBlockModel[] models = entry.getValue().get(state);
                        JsonElement value = models != null ? PolymerBlocksInternal.createJsonElement(models) : vanillaFor(vanillaVariants, block, state);
                        if (value != null) {
                            variants.add(PolymerBlocksInternal.generateStateName(state), value);
                        }
                    }
                    JsonObject out = new JsonObject();
                    out.add("variants", variants);
                    pack.addData(path, DefaultRPBuilder.GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    PolymerPatcher.LOGGER.warn("Could not write {} for this mod's own carriers", path, e);
                }
            }
        });
    }

    private static @Nullable JsonElement vanillaFor(Set<Map.Entry<String, JsonElement>> variants, Block block, BlockState state) {
        for (Map.Entry<String, JsonElement> variant : variants) {
            if (VanillaBlockPropertiesPredicate.parse(block.getStateDefinition(), variant.getKey()).test(state)) {
                return variant.getValue();
            }
        }
        return null;
    }
}
