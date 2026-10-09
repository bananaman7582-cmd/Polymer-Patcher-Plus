package me.drex.polymerpatcher.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Finds every vanilla block state a client cannot tell apart from another one.
 * <p>
 * A carrier is a vanilla state the pack re-skins for a modded block, and it only costs nothing when a real
 * block in that state can be sent as a twin that looks exactly the same. This reads the game's own
 * blockstate files and lists every such twin: same models with the same rotations, same tint, same light,
 * same shapes, and nothing drawn by a block entity on top. Development only; nothing at runtime uses it.
 */
public final class CarrierTwinFinder {
    private CarrierTwinFinder() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Object colors = null;
        try {
            colors = Class.forName("net.minecraft.client.color.block.BlockColors").getMethod("createDefault").invoke(null);
        } catch (Throwable e) {
            System.out.println("No tint information: " + e);
        }
        Set<Object> renderedEntities = renderedBlockEntityTypes();

        Map<BlockState, String> polymerPools = polymerPools();
        Set<Block> multipartBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, List<BlockState>> groups = new LinkedHashMap<>();
        int states = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            JsonObject json = blockstateJson(id);
            if (json == null) {
                continue;
            }
            if (json.has("multipart")) {
                multipartBlocks.add(block);
            }
            Set<Property<?>> coloring = coloringProperties(colors, block);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                states++;
                groups.computeIfAbsent(signature(json, state, coloring, renderedEntities), k -> new ArrayList<>()).add(state);
            }
        }

        List<String> out = new ArrayList<>();
        out.add("canonical\tcarrier\tclass\tflags\tpolymer");
        Map<String, Integer> byClass = new TreeMap<>();
        Map<String, Integer> byClassClean = new TreeMap<>();
        for (List<BlockState> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            BlockState canonical = group.stream().filter(s -> s == s.getBlock().defaultBlockState()).findFirst()
                .orElse(group.stream().min(Comparator.comparingInt(Block::getId)).orElseThrow());
            for (BlockState state : group) {
                if (state == canonical) {
                    continue;
                }
                String shapeClass = shapeClass(state);
                List<String> flags = new ArrayList<>();
                if (state.getBlock() != canonical.getBlock()) {
                    flags.add("cross-block");
                    if (state.getBlock().defaultDestroyTime() != canonical.getBlock().defaultDestroyTime()) {
                        flags.add("hardness");
                    }
                    if (state.getSoundType() != canonical.getSoundType()) {
                        flags.add("sound");
                    }
                }
                if (multipartBlocks.contains(state.getBlock())) {
                    // Its blockstate file is written in parts, which only Polymer itself knows how to rewrite
                    flags.add("multipart");
                }
                if (tinted(colors, state)) {
                    // Tints whatever model it wears, so it is only a carrier for models that want the same tint
                    flags.add("tinted");
                }
                if (state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).isEmpty()) {
                    // Nothing to aim at, so a block carried by it could never be broken
                    flags.add("no-outline");
                }
                if (state.getLightEmission() > 0) {
                    flags.add("light" + state.getLightEmission());
                }
                if (overridesAnimateTick(state.getBlock())) {
                    flags.add("particles");
                }
                if (state.getBlock() instanceof EntityBlock) {
                    flags.add("block-entity");
                }
                byClass.merge(shapeClass, 1, Integer::sum);
                if (flags.isEmpty() || flags.equals(List.of("block-entity"))) {
                    byClassClean.merge(shapeClass, 1, Integer::sum);
                }
                out.add(key(canonical) + "\t" + key(state) + "\t" + shapeClass + "\t" + String.join(",", flags)
                    + "\t" + polymerPools.getOrDefault(state, "new"));
            }
        }

        Path output = Path.of(args.length > 0 ? args[0] : "carrier-twins.tsv");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, out, StandardCharsets.UTF_8);
        System.out.println("Looked at " + states + " vanilla states; " + (out.size() - 1) + " have a twin a client cannot tell apart");
        System.out.println("Twins by shape: " + byClass);
        System.out.println("Of those with no light, particles or other difference: " + byClassClean);
        System.out.println("Written to " + output.toAbsolutePath());
    }

    private static JsonObject blockstateJson(Identifier id) {
        String path = "/assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json";
        try (InputStream in = CarrierTwinFinder.class.getResourceAsStream(path)) {
            return in == null ? null : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Everything about a state a client draws or acts on, in a form two identical states share. */
    private static String signature(JsonObject json, BlockState state, Set<Property<?>> coloring, Set<Object> renderedEntities) {
        StringBuilder sig = new StringBuilder(appearance(json, state));
        sig.append("|light=").append(state.getLightEmission());
        sig.append("|occlude=").append(state.canOcclude());
        sig.append("|lightBlock=").append(state.getLightDampening()).append("|skylight=").append(state.propagatesSkylightDown());
        sig.append("|render=").append(state.getRenderShape());
        sig.append("|fluid=").append(state.getFluidState());
        sig.append("|collision=").append(shape(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty())));
        sig.append("|outline=").append(shape(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty())));
        for (Property<?> property : coloring) {
            sig.append("|tint:").append(property.getName()).append('=').append(state.getValue(property));
        }
        if (state.getBlock() instanceof EntityBlock entityBlock) {
            Object entity;
            try {
                var made = entityBlock.newBlockEntity(BlockPos.ZERO, state);
                entity = made == null ? null : made.getType();
            } catch (Throwable e) {
                entity = "unknown";
            }
            if (entity != null && (renderedEntities == null || renderedEntities.contains(entity))) {
                // Drawn by a renderer from the block entity's own data, which a re-skin cannot reach
                sig.append("|rendered-entity=").append(System.identityHashCode(state));
            }
        }
        return sig.toString();
    }

    private static String appearance(JsonObject json, BlockState state) {
        List<String> parts = new ArrayList<>();
        if (json.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("variants").entrySet()) {
                if (matchesVariant(entry.getKey(), state)) {
                    parts.add(canonical(entry.getValue()));
                }
            }
        }
        if (json.has("multipart")) {
            for (JsonElement part : json.getAsJsonArray("multipart")) {
                JsonObject obj = part.getAsJsonObject();
                if (!obj.has("when") || matchesWhen(obj.get("when").getAsJsonObject(), state)) {
                    parts.add(canonical(obj.get("apply")));
                }
            }
        }
        return parts.isEmpty() ? "<none>" : String.join("+", parts);
    }

    private static boolean matchesVariant(String key, BlockState state) {
        if (key.isEmpty()) {
            return true;
        }
        for (String pair : key.split(",")) {
            String[] kv = pair.split("=", 2);
            if (!valueIs(state, kv[0], kv[1])) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesWhen(JsonObject when, BlockState state) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) {
                if (matchesWhen(e.getAsJsonObject(), state)) {
                    return true;
                }
            }
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) {
                if (!matchesWhen(e.getAsJsonObject(), state)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
            String wanted = entry.getValue().getAsString();
            boolean negate = wanted.startsWith("!");
            boolean any = false;
            for (String option : (negate ? wanted.substring(1) : wanted).split("\\|")) {
                any |= valueIs(state, entry.getKey(), option);
            }
            if (any == negate) {
                return false;
            }
        }
        return true;
    }

    private static boolean valueIs(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return valueName(state, property).equals(value);
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(BlockState state, Property property) {
        return property.getName(state.getValue(property));
    }

    /** A model entry or list of them, written the same way whatever order its keys came in. */
    private static String canonical(JsonElement element) {
        if (element.isJsonArray()) {
            List<String> entries = new ArrayList<>();
            for (JsonElement e : (JsonArray) element) {
                entries.add(canonical(e));
            }
            return entries.toString();
        }
        if (element.isJsonObject()) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue().toString());
            }
            // Leaving out a rotation or uvlock means zero or false, so say so either way
            sorted.putIfAbsent("x", "0");
            sorted.putIfAbsent("y", "0");
            sorted.putIfAbsent("z", "0");
            sorted.putIfAbsent("uvlock", "false");
            sorted.putIfAbsent("weight", "1");
            sorted.computeIfPresent("model", (k, v) -> v.replace("\"minecraft:", "\""));
            return sorted.toString();
        }
        return element.toString();
    }

    private static String shape(VoxelShape shape) {
        return shape.isEmpty() ? "empty" : shape.toAabbs().toString();
    }

    private static String shapeClass(BlockState state) {
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        if (collision.isEmpty()) {
            return "walk-through";
        }
        if (Shapes.equal(collision, Shapes.block())) {
            return state.canOcclude() ? "full-cube" : "see-through-cube";
        }
        return "other:" + collision.bounds();
    }

    private static Set<Property<?>> coloringProperties(Object colors, Block block) {
        if (colors == null) {
            return Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Set<Property<?>> props = (Set<Property<?>>) colors.getClass().getMethod("getColoringProperties", Block.class).invoke(colors, block);
            return props == null ? Set.of() : props;
        } catch (Throwable e) {
            return Set.of();
        }
    }

    private static boolean tinted(Object colors, BlockState state) {
        if (colors == null) {
            return false;
        }
        try {
            List<?> sources = (List<?>) colors.getClass().getMethod("getTintSources", BlockState.class).invoke(colors, state);
            return sources != null && !sources.isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    /** The block entity types the client draws with a renderer of their own. */
    private static Set<Object> renderedBlockEntityTypes() {
        Set<Object> types = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Class<?> renderers = Class.forName("net.minecraft.client.renderer.blockentity.BlockEntityRenderers");
            for (var field : renderers.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType()) && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    types.addAll(((Map<?, ?>) field.get(null)).keySet());
                }
            }
            System.out.println("Block entity types with a renderer: " + types.size());
        } catch (Throwable e) {
            System.out.println("Could not read block entity renderers (" + e + "); every block entity counts as drawn");
            return null;
        }
        return types;
    }

    private static boolean overridesAnimateTick(Block block) {
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            for (var method : c.getDeclaredMethods()) {
                if (method.getName().equals("animateTick")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String key(BlockState state) {
        StringBuilder key = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
        List<String> values = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            values.add(property.getName() + "=" + valueName(state, property));
        }
        Collections.sort(values);
        if (!values.isEmpty()) {
            key.append('[').append(String.join(",", values)).append(']');
        }
        return key.toString();
    }
    /** Which pool Polymer already offers each state in, if it does. */
    private static Map<BlockState, String> polymerPools() {
        Map<BlockState, String> pools = new IdentityHashMap<>();
        try {
            Class<?> data = Class.forName("eu.pb4.polymer.blocks.impl.DefaultModelData");
            Map<?, ?> usable = (Map<?, ?>) data.getField("USABLE_STATES").get(null);
            for (Map.Entry<?, ?> entry : usable.entrySet()) {
                for (Object state : (List<?>) entry.getValue()) {
                    pools.put((BlockState) state, String.valueOf(entry.getKey()));
                }
            }
            System.out.println("Polymer offers " + pools.size() + " carrier states");
        } catch (Throwable e) {
            System.out.println("Could not read Polymer's pools: " + e);
        }
        return pools;
    }
}
