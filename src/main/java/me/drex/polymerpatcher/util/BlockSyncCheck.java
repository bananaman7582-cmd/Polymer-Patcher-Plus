package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps count of the modded blocks that cannot be described to a client as themselves.
 * <p>
 * The work is done as each block's entry is built - see
 * {@code me.drex.polymerpatcher.mixin.polymer.PolymerBlockEntryMixin} - and the count is kept here rather
 * than there because a mixin may hold no state of its own that anything else can read.
 * <p>
 * Those entries are built when the first client joins rather than while the server starts, so this says
 * the first one out loud as it happens and then keeps quiet: what matters is that it is on the record that
 * this server has blocks whose stand-in had to be substituted, and by how many.
 */
public final class BlockSyncCheck {

    /** Number of states in an unmodified Minecraft 26.2 registry (raw ids 0 through 32365). */
    public static final int VANILLA_26_2_STATE_COUNT = 32_366;

    private BlockSyncCheck() {
    }

    private static final Set<Identifier> REPLACED = ConcurrentHashMap.newKeySet();
    private static final String VANILLA_STATE_MAP = "/assets/polymer-patcher/vanilla_block_states_26_2.tsv";
    private static final Map<String, Integer> CLIENT_RAW_IDS = loadClientRawIds();
    private static final Map<BlockState, Integer> RESOLVED_CLIENT_RAW_IDS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> CLIENT_PROPERTIES = clientProperties();

    /**
     * The sole state placed in Polymer's block metadata packet. The packet's codec writes a raw number,
     * so even barrier itself is unsafe when an earlier vanilla block gained modded states. Return the
     * server-registry token whose number is barrier's pristine-client number instead.
     */
    public static BlockState clientReadableMetadata() {
        return clientReadableWorldState(Blocks.BARRIER.defaultBlockState());
    }

    public static boolean isClientReadableMetadata(BlockState state) {
        return state == clientReadableMetadata();
    }

    /**
     * Whether this exact semantic state exists on an unmodified 26.2 client.
     *
     * <p>The server registry is larger because installed mods can add states even to blocks in the
     * {@code minecraft} namespace. Names and block assets therefore cannot answer this question; the
     * packet contains only the numeric id.</p>
     */
    public static boolean isClientReadableWorldState(BlockState state) {
        return clientRawId(state) >= 0;
    }

    /** The raw id the pristine 26.2 client assigns to this exact block/property combination. */
    public static int clientRawId(BlockState state) {
        return state == null ? -1 : RESOLVED_CLIENT_RAW_IDS.computeIfAbsent(state,
            candidate -> CLIENT_RAW_IDS.getOrDefault(stateKey(candidate), -1));
    }

    /** Never allow a server-only carrier id into a vanilla client's chunk palette. */
    public static BlockState clientReadableWorldState(BlockState state) {
        int clientRawId = clientRawId(state);
        if (clientRawId < 0) {
            clientRawId = clientRawId(Blocks.BARRIER.defaultBlockState());
        }
        if (clientRawId < 0) {
            // The embedded map is a build invariant, but raw zero is still preferable to disconnecting.
            clientRawId = 0;
        }

        // The codec will ask the server mapper for this object's id. Return whichever server object now
        // occupies the client's desired number; its server-side identity is irrelevant at that boundary.
        BlockState token = Block.BLOCK_STATE_REGISTRY.byId(clientRawId);
        return token != null ? token : Blocks.AIR.defaultBlockState();
    }

    /**
     * Stable semantic identity shared by the pristine build registry and the modded server registry.
     * <p>
     * A mod may add a property to a block the game already ships. The Sift gives everything that can be
     * waterlogged an {@code ichorlogged} beside it, exactly the way waterlogging itself works, and the
     * server's oak leaves then carry a property no client has ever heard of. A key written from the
     * server's own properties matches nothing in the pristine map, so every one of those blocks was read
     * as "not on the client at all" and sent as the barrier below: invisible leaves, invisible chests,
     * invisible stairs, on every client at once, the moment such a mod was installed.
     * <p>
     * So the key is written in the client's terms rather than this server's. A property the client has
     * never heard of is left out, which is the only honest thing to do with it: a client that cannot
     * represent ichorlogged cannot be told which of the two a block is, and the ordinary one is the
     * right answer. Nothing here names a mod or a property - what counts as known is read out of the
     * pristine map itself, so the next mod to do this needs no change here.
     */
    public static String stateKey(BlockState state) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder key = new StringBuilder(id == null ? "minecraft:air" : id.toString());
        // Null for a block the client has not got at all, where every property is equally unknown and
        // the lookup will miss whatever is written - so nothing is dropped and the miss stays honest
        Set<String> known = CLIENT_PROPERTIES.get(key.toString());
        var values = state.getValues().toList().stream()
            .filter(value -> known == null || known.contains(value.property().getName()))
            .sorted(Comparator.comparing(value -> value.property().getName()))
            .toList();
        if (!values.isEmpty()) {
            key.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    key.append(',');
                }
                var value = values.get(i);
                key.append(value.property().getName()).append('=').append(value.valueName());
            }
            key.append(']');
        }
        return key.toString();
    }

    private static Map<String, Integer> loadClientRawIds() {
        Map<String, Integer> ids = new HashMap<>(VANILLA_26_2_STATE_COUNT * 2);
        try (var stream = BlockSyncCheck.class.getResourceAsStream(VANILLA_STATE_MAP)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + VANILLA_STATE_MAP);
            }
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('\t');
                    if (separator <= 0) {
                        throw new IllegalStateException("Malformed vanilla state row: " + line);
                    }
                    ids.put(line.substring(separator + 1), Integer.parseInt(line.substring(0, separator)));
                }
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        if (ids.size() != VANILLA_26_2_STATE_COUNT) {
            throw new ExceptionInInitializerError("Expected " + VANILLA_26_2_STATE_COUNT
                + " pristine block states, found " + ids.size());
        }
        return Map.copyOf(ids);
    }

    /**
     * Which properties each block in the pristine map carries, taken from the map's own keys.
     * <p>
     * Read out of the map rather than written down separately, so this knows exactly as much about
     * vanilla as the map does and cannot drift from it.
     */
    private static Map<String, Set<String>> clientProperties() {
        Map<String, Set<String>> properties = new HashMap<>();
        for (String key : CLIENT_RAW_IDS.keySet()) {
            int open = key.indexOf('[');
            if (open < 0) {
                properties.computeIfAbsent(key, block -> new java.util.HashSet<>());
                continue;
            }

            Set<String> names = properties.computeIfAbsent(key.substring(0, open), block -> new java.util.HashSet<>());
            for (String pair : key.substring(open + 1, key.length() - 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    names.add(pair.substring(0, equals));
                }
            }
        }
        return Map.copyOf(properties);
    }

    /**
     * Whether this state can carry a modded block to a client that has none of the mods.
     * <p>
     * A carrier is only any use if a client can be sent it and tell it apart from every other state. A mod
     * that hangs a property on a vanilla block doubles that block's states here, and the new half do not
     * exist on a client: each arrives as the ordinary state beside it. Taken as carriers they put a modded
     * block on top of a real one, which is how thornwood, void shale and drift jelly came to be drawn as
     * leaves. So a carrier has to be a state the client has - every property it knows free to vary, every
     * property it does not left at the block's own default, which is where the client always is.
     */
    public static boolean usableAsCarrier(BlockState state) {
        if (clientRawId(state) < 0) {
            return false;
        }
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Set<String> known = id == null ? null : CLIENT_PROPERTIES.get(id.toString());
        if (known == null) {
            return false;
        }

        Map<String, String> defaults = valueNames(state.getBlock().defaultBlockState());
        for (Map.Entry<String, String> value : valueNames(state).entrySet()) {
            if (!known.contains(value.getKey()) && !value.getValue().equals(defaults.get(value.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * This state with every property a client has not got set back to the block's default.
     * <p>
     * A client cannot see a property it has never heard of, so to a client a block with ichor in it is
     * simply the block. Polymer does not know that. It keeps the ordinary state of a carrier block clear of
     * real blocks by sending them somewhere else, but it only knows to do that for the states it took -
     * and a real block sitting in the ichor-soaked twin of one of those is a state it never took. Sent as
     * it is, that twin arrives as the ordinary state, which is now a modded block's carrier: a soaked leaf
     * drawn as thornwood. Handed to Polymer as its ordinary twin in the first place, it is sent wherever
     * the ordinary one goes, and the carrier stays clear.
     *
     * @return the same object wherever nothing needed changing, which is every block on an unmodded server
     */
    public static BlockState asClientTwin(BlockState state) {
        List<net.minecraft.world.level.block.state.properties.Property<?>> unknown = UNKNOWN_PROPERTIES.computeIfAbsent(
            state.getBlock(), BlockSyncCheck::unknownPropertiesOf);
        if (unknown.isEmpty()) {
            return state;
        }
        return CLIENT_TWINS.computeIfAbsent(state, candidate -> {
            BlockState defaults = candidate.getBlock().defaultBlockState();
            BlockState twin = candidate;
            for (var property : unknown) {
                twin = withValueOf(twin, defaults, property);
            }
            return twin;
        });
    }

    private static final Map<Block, List<net.minecraft.world.level.block.state.properties.Property<?>>> UNKNOWN_PROPERTIES =
        new ConcurrentHashMap<>();
    private static final Map<BlockState, BlockState> CLIENT_TWINS = new ConcurrentHashMap<>();

    /** The properties of a block the client has that the client does not know it by; empty for any other block. */
    private static List<net.minecraft.world.level.block.state.properties.Property<?>> unknownPropertiesOf(Block block) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        Set<String> known = id == null ? null : CLIENT_PROPERTIES.get(id.toString());
        if (known == null) {
            return List.of();
        }
        return block.getStateDefinition().getProperties().stream()
            .filter(property -> !known.contains(property.getName()))
            .<net.minecraft.world.level.block.state.properties.Property<?>>map(property -> property)
            .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withValueOf(BlockState into, BlockState from,
                                          net.minecraft.world.level.block.state.properties.Property property) {
        return into.setValue(property, from.getValue(property));
    }

    /** Each property of this state by name, with its value as a blockstate file would spell it. */
    public static Map<String, String> valueNames(BlockState state) {
        Map<String, String> names = new HashMap<>();
        state.getValues().toList().forEach(value -> names.put(value.property().getName(), value.valueName()));
        return names;
    }

    /**
     * What an unmodified 26.2 client reads this block number as - the inverse of {@link #clientRawId} - or
     * null for a number it has no block for. For saying what a player is actually being shown.
     */
    public static @org.jspecify.annotations.Nullable String clientStateNameAt(int rawId) {
        String[] names = CLIENT_NAMES_BY_ID;
        if (names == null) {
            names = new String[VANILLA_26_2_STATE_COUNT];
            for (Map.Entry<String, Integer> entry : CLIENT_RAW_IDS.entrySet()) {
                if (entry.getValue() >= 0 && entry.getValue() < names.length) {
                    names[entry.getValue()] = entry.getKey();
                }
            }
            CLIENT_NAMES_BY_ID = names;
        }
        return rawId >= 0 && rawId < names.length ? names[rawId] : null;
    }

    private static volatile String[] CLIENT_NAMES_BY_ID;

    /** The properties an unmodified client knows this block by, or null where it has no such block. */
    public static @org.jspecify.annotations.Nullable Set<String> clientPropertiesOf(String blockId) {
        return CLIENT_PROPERTIES.get(blockId);
    }

    /** Pure invariant used by the standalone regression check without bootstrapping Minecraft. */
    public static boolean preservesArbitraryRawStateIds() {
        return false;
    }

    public static void note(Identifier block) {
        if (!REPLACED.add(block) || REPLACED.size() != 1) {
            return;
        }

        PolymerPatcher.LOGGER.info("{} has a server-local raw block-state id, so Polymer's client metadata uses a stable "
            + "vanilla placeholder. Its real per-player carrier is still used in the world. Before this, a differently "
            + "modded client could be disconnected while reading the block list. Others are handled silently.",
            block);
    }

    /** How many blocks have needed it so far, for anything that wants to report on it. */
    public static int replaced() {
        return REPLACED.size();
    }
}
