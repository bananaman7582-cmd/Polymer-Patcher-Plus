package me.drex.polymerpatcher.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Offers Polymer every vanilla block state a client cannot tell apart from another one.
 * <p>
 * A carrier costs nothing when a real block in that state can be sent as a twin that looks exactly the same,
 * which is the whole trick Polymer's own pools are built on - waxed copper sent as unwaxed, a powered door
 * sent as an unpowered one. It found its twins by hand, and missed most of them. The game has 7,889, read
 * out of its own blockstate files at build time by {@code CarrierTwinFinder}; Polymer offered 4,154.
 * <p>
 * The largest group by far is stairs. A stair corner is drawn by one model turned to face one of four ways,
 * and every corner can be reached two ways round - an outer corner facing east and turned left is the very
 * same model, at the very same angle, as one facing north and turned right. So every stair block in the game
 * has thirty-two states that are free, and stairs are the kind modded blocks run short of worst: each stair
 * shape had three carriers and turned away nine hundred blocks.
 * <p>
 * A twin only goes in when nothing about it gives it away: no light, no particles, no tint, no renderer of
 * its own, the same shapes, and something to aim at. It goes in the pool whose existing carriers are the
 * same sort of block with exactly the same shapes, at the back, so Polymer's own choices are still spent
 * first. Nothing changes until a twin is actually handed out - an untaken one is never written to the pack
 * and never sent as anything else.
 */
final class CarrierTwins {
    private static final String TABLE = "/assets/polymer-patcher/carrier_twins_26_2.tsv";

    /** Kinds of block whose carriers only ever stand in for that same kind of block. */
    private static final List<Class<?>> FAMILIES = List.of(StairBlock.class, DoorBlock.class, TrapDoorBlock.class,
        FenceGateBlock.class, BedBlock.class, TripWireBlock.class);

    /** What a twin may differ from its partner in and still be a free carrier. */
    private static final Set<String> HARMLESS = Set.of("", "block-entity", "cross-block", "multipart");

    private CarrierTwins() {
    }

    static void addTo(Map<BlockModelType, List<BlockState>> pools, Map<BlockState, BlockState> remaps) {
        try (InputStream in = CarrierTwins.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                PolymerPatcher.LOGGER.warn("No table of twin block states in this jar; carriers are only the ones Polymer found");
                return;
            }
            add(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().skip(1).toList(), pools, remaps);
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> verify());
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not add twin block states as carriers; carriers are only the ones Polymer found", e);
        }
    }

    private static void add(List<String> rows, Map<BlockModelType, List<BlockState>> pools, Map<BlockState, BlockState> remaps) {
        Set<BlockState> offered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<BlockState> pool : pools.values()) {
            if (pool != null) {
                offered.addAll(pool);
            }
        }

        // Each pool's first carrier stands for the pool: its kind of block and its shapes are what a twin must
        // match. Taken before anything is moved below, so a pool emptied for a moment still has one
        Map<BlockModelType, BlockState> templates = new EnumMap<>(BlockModelType.class);
        for (Map.Entry<BlockModelType, List<BlockState>> entry : pools.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty() && !entry.getKey().name().startsWith("BIOME_")) {
                templates.put(entry.getKey(), entry.getValue().getFirst());
            }
        }

        // The look-alike groups, as the table lists them: the state it would send the group as, then the others.
        // That first state is given the first row's flags - it looks the same, and is the same block or its twin
        record Member(BlockState state, String flags) {
        }
        Map<BlockState, List<Member>> groups = new LinkedHashMap<>();
        for (String row : rows) {
            String[] columns = row.split("\t", -1);
            if (columns.length < 4) {
                continue;
            }
            BlockState listed = parse(columns[0]);
            BlockState member = parse(columns[1]);
            if (listed != null && member != null) {
                groups.computeIfAbsent(listed, k -> new ArrayList<>(List.of(new Member(listed, columns[3])))).add(new Member(member, columns[3]));
            }
        }

        // The one state each group's real blocks are all sent as. It must never be a carrier.
        // Where the group is copper, it is the waxed one. A client goes by what it is shown: shown real waxed
        // copper as the unwaxed block it looks just like, it let the player wax it again, played the wax, and
        // took the honeycomb out of their hand - while the server, which knew it was waxed already, did nothing.
        // Shown as waxed, the client has nothing to try, and it is the unwaxed states that carry instead
        Set<Block> waxed = Collections.newSetFromMap(new IdentityHashMap<>());
        waxed.addAll(HoneycombItem.WAXABLES.get().values());
        int flipped = 0;
        Map<BlockState, BlockModelType> handedBack = new IdentityHashMap<>();
        Map<BlockState, BlockState> canonicalOf = new IdentityHashMap<>();
        for (List<Member> group : groups.values()) {
            BlockState canonical = null;
            for (Member member : group) {
                if (waxed.contains(member.state().getBlock())) {
                    canonical = member.state();
                    if (offered.remove(canonical)) {
                        for (Map.Entry<BlockModelType, List<BlockState>> pool : pools.entrySet()) {
                            if (pool.getValue() != null && pool.getValue().remove(canonical)) {
                                handedBack.put(canonical, pool.getKey());
                            }
                        }
                        flipped++;
                    }
                    break;
                }
            }
            if (canonical == null && !offered.contains(group.getFirst().state())) {
                canonical = group.getFirst().state();
            }
            // Otherwise the one Polymer already sends them as, so nothing it decided has to change
            for (Member member : group) {
                if (canonical == null && offered.contains(member.state())) {
                    BlockState current = remaps.getOrDefault(member.state(), fallbackOf(member.state()));
                    if (!offered.contains(current) && group.stream().anyMatch(m -> m.state() == current)) {
                        canonical = current;
                    }
                }
            }
            for (Member member : group) {
                if (canonical == null && !offered.contains(member.state())) {
                    canonical = member.state();
                }
            }
            if (canonical != null) {
                for (Member member : group) {
                    canonicalOf.put(member.state(), canonical);
                }
            }
        }

        // Every real block of a group is sent as that one state. Polymer sends its carriers' real blocks as
        // whichever twin it picked by hand - a powered door as the unpowered one beside it - and each of those
        // twins then has to stay as it is. Sent as the group's one state instead, they look no different, and
        // every other state in the group is free.
        // A twin Polymer picked that is not one at all is corrected the same way: infested deepslate was sent as
        // chiseled deepslate, which looks nothing like it, wherever its carrier was in use
        int pointed = 0;
        List<String> corrected = new ArrayList<>();
        for (BlockState carrier : offered) {
            BlockState canonical = canonicalOf.get(carrier);
            if (canonical == null) {
                continue;
            }
            BlockState current = remaps.getOrDefault(carrier, fallbackOf(carrier));
            if (current != canonical) {
                if (canonicalOf.get(current) != canonical) {
                    corrected.add(carrier + " was sent as " + current);
                }
                remaps.put(carrier, canonical);
                pointed++;
            }
        }
        if (!corrected.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} carrier(s) were sent, for real blocks, as a state that does not look the same; "
                + "now sent as one that does: {}", corrected.size(), corrected.subList(0, Math.min(12, corrected.size())));
        }

        // Every state a taken carrier is sent as. None of them may be handed out: a real block sent as a state
        // that is now carrying something else would show that instead
        Set<BlockState> sentAs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockState state : offered) {
            BlockState target = remaps.getOrDefault(state, fallbackOf(state));
            sentAs.add(target);
            if (!remaps.containsKey(state) && target.hasProperty(BlockStateProperties.WATERLOGGED)) {
                sentAs.add(target.cycle(BlockStateProperties.WATERLOGGED));
            }
        }

        // Each waxed carrier handed back is replaced, in the very same pool, by the unwaxed state it is the twin
        // of - an exact swap, light and all, so a pool made only of waxed copper (lanterns, chains, bars) is not
        // left empty
        for (Map.Entry<BlockState, BlockModelType> back : handedBack.entrySet()) {
            BlockState waxedState = back.getKey();
            Block unwaxedBlock = HoneycombItem.WAX_OFF_BY_BLOCK.get().get(waxedState.getBlock());
            if (unwaxedBlock == null) {
                continue;
            }
            BlockState unwaxed = unwaxedBlock.withPropertiesOf(waxedState);
            if (canonicalOf.get(unwaxed) == waxedState && !offered.contains(unwaxed) && !sentAs.contains(unwaxed)) {
                pools.get(back.getValue()).add(unwaxed);
                remaps.put(unwaxed, waxedState);
                offered.add(unwaxed);
            }
        }

        Map<String, Integer> added = new TreeMap<>();
        List<ExtraCarriers.Twin> leftover = new ArrayList<>();
        int total = 0;
        for (List<Member> group : groups.values()) {
            for (Member member : group) {
                BlockState carrier = member.state();
                BlockState canonical = canonicalOf.get(carrier);
                if (canonical == null || canonical == carrier || !harmless(member.flags()) || carrier.getBlock() instanceof LiquidBlock
                    || offered.contains(carrier) || sentAs.contains(carrier)) {
                    continue;
                }
                BlockModelType kind = kindFor(carrier, templates, pools);
                if (kind == null) {
                    // A shape Polymer has no kind for. Kept for one of this mod's own, if its file is plain enough
                    if (!member.flags().contains("multipart")) {
                        leftover.add(new ExtraCarriers.Twin(carrier, canonical));
                    }
                    continue;
                }
                pools.get(kind).add(carrier);
                remaps.put(carrier, canonical);
                offered.add(carrier);
                added.merge(kind.name().split("_")[0], 1, Integer::sum);
                total++;
            }
        }
        PolymerPatcher.LOGGER.info("Added {} twin block state(s) as carriers, each a vanilla state a client cannot tell from another: {} "
            + "({} of Polymer's own carriers now sent as their group's one state; {} waxed copper state(s) handed back, so real "
            + "waxed copper is shown as waxed)", total, added, pointed, flipped);
        ExtraCarriers.offer(leftover);
    }

    /**
     * Checks, once every carrier has been handed out, that no real block is shown as a modded one.
     * <p>
     * A carrier's real blocks are sent as some other state. If that state has itself been handed out, they
     * arrive wearing whatever model it was given - a real oak stair drawn as some mod's stair. This is the one
     * way taking more of the game's states as carriers can go wrong, so it is looked for every time.
     */
    static void verify() {
        try {
            Map<BlockState, BlockState> taken = eu.pb4.polymer.blocks.impl.BlockExtBlockMapper.INSTANCE.stateMap;
            List<String> leaks = new ArrayList<>();
            for (Map.Entry<BlockState, BlockState> entry : taken.entrySet()) {
                if (taken.containsKey(entry.getValue())) {
                    leaks.add(entry.getKey() + " -> " + entry.getValue());
                }
            }
            if (leaks.isEmpty()) {
                PolymerPatcher.LOGGER.info("All {} carriers in use are sent, for real blocks, as states that carry nothing themselves", taken.size());
            } else {
                PolymerPatcher.LOGGER.error("{} carrier(s) send their real blocks as a state that is itself carrying a modded block, "
                    + "so those real blocks look modded: {}", leaks.size(), leaks.subList(0, Math.min(20, leaks.size())));
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not check the carriers in use", e);
        }
    }

    /** What Polymer sends a taken carrier's real blocks as when it was given no particular twin. */
    private static BlockState fallbackOf(BlockState state) {
        BlockState fallback = state.getBlock() instanceof LeavesBlock
            ? state.getBlock().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
            : state.getBlock().defaultBlockState();
        if (state.getBlock() instanceof SimpleWaterloggedBlock && fallback.hasProperty(BlockStateProperties.WATERLOGGED)) {
            fallback = fallback.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED));
        }
        return fallback;
    }

    private static boolean harmless(String flags) {
        for (String flag : flags.trim().split(",")) {
            if (!HARMLESS.contains(flag.trim())) {
                return false;
            }
        }
        return true;
    }

    /** The pool this twin belongs in: same sort of block, same shapes. The emptiest, when more than one fits. */
    private static BlockModelType kindFor(BlockState carrier, Map<BlockModelType, BlockState> templates, Map<BlockModelType, List<BlockState>> pools) {
        BlockModelType best = null;
        for (Map.Entry<BlockModelType, BlockState> entry : templates.entrySet()) {
            BlockState template = entry.getValue();
            if (!family(template).equals(family(carrier)) || !sameShapes(template, carrier)) {
                continue;
            }
            if (best == null || pools.get(entry.getKey()).size() < pools.get(best).size()) {
                best = entry.getKey();
            }
        }
        return best;
    }

    private static String family(BlockState state) {
        Block block = state.getBlock();
        for (Class<?> family : FAMILIES) {
            if (family.isInstance(block)) {
                return family.getSimpleName();
            }
        }
        if (Shapes.equal(collision(state), Shapes.block())) {
            return state.canOcclude() ? "cube" : "see-through-cube";
        }
        return block.getClass().getName();
    }

    private static boolean sameShapes(BlockState a, BlockState b) {
        return a.canOcclude() == b.canOcclude()
            && a.getFluidState().equals(b.getFluidState())
            && waterlogged(a) == waterlogged(b)
            && Shapes.equal(collision(a), collision(b))
            && Shapes.equal(outline(a), outline(b));
    }

    private static boolean waterlogged(BlockState state) {
        return state.getBlock() instanceof SimpleWaterloggedBlock && state.getValueOrElse(BlockStateProperties.WATERLOGGED, false);
    }

    private static VoxelShape collision(BlockState state) {
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
    }

    private static VoxelShape outline(BlockState state) {
        return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
    }

    /** {@code oak_stairs[facing=east,half=bottom]}, as the table writes a vanilla state. */
    private static BlockState parse(String key) {
        key = key.trim();
        int bracket = key.indexOf('[');
        String path = bracket < 0 ? key : key.substring(0, bracket);
        Identifier id = Identifier.withDefaultNamespace(path);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if (bracket < 0) {
            return state;
        }
        for (String pair : key.substring(bracket + 1, key.length() - 1).split(",")) {
            String[] kv = pair.split("=", 2);
            Property<?> property = state.getBlock().getStateDefinition().getProperty(kv[0]);
            if (property == null || kv.length < 2) {
                return null;
            }
            state = with(state, property, kv[1]);
            if (state == null) {
                return null;
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(null);
    }
}
