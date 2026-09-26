package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives back the models of the vanilla block states that were not turned into carriers.
 * <p>
 * A carrier is a vanilla block state the pack re-skins so a modded block can be shown to a vanilla client
 * as a real block. Polymer writes one blockstate file per vanilla block it borrows from, and that file
 * lists <em>only the states it took</em>. Every other state of that block is then named nowhere in the
 * pack, and a state with no entry has no model: the client logs "Missing model for variant" and draws the
 * black-and-magenta cube instead.
 * <p>
 * It is easy to miss because the states left over are usually the ordinary ones. Farmland ships as
 * moisture two through six, so dry farmland and wet farmland - which is all farmland anybody ever sees -
 * have no model at all. Cactus ships as ages one to fifteen, and a cactus grows at age zero. Birch, spruce,
 * azalea and pale oak leaves ship without {@code persistent=true, distance=7}, which is both what a player
 * places and what every naturally grown leaf is sent as.
 * <p>
 * Polymer already does exactly this repair for the blocks it describes with multipart rules, where the
 * vanilla rules are merged back in underneath its own. The blocks described with plain variants never got
 * the same treatment. So after the pack is otherwise finished, every vanilla block in it is checked, and
 * any state the file does not name is given the model the game itself would have given it.
 * <p>
 * Worth doing on its own, and it is also what makes adding carriers safe: a pool can now be spent down to
 * its last state without any vanilla block losing its appearance for it.
 */
public final class CarrierBlockStates {
    private CarrierBlockStates() {
    }

    private static final String PREFIX = "assets/minecraft/blockstates/";

    public static void repair(ResourcePackBuilder builder) {
        int blocksRepaired = 0;
        int statesRepaired = 0;
        List<String> unresolved = new ArrayList<>();

        for (Map.Entry<ResourceKey<Block>, Block> entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!RegistryPatcher.isVanillaBlock(id)) {
                continue;
            }

            try {
                int added = repairBlock(builder, id, entry.getValue(), unresolved);
                if (added > 0) {
                    blocksRepaired++;
                    statesRepaired += added;
                }
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not check the blockstate file of {}", id, e);
            }
        }

        if (statesRepaired > 0) {
            PolymerPatcher.LOGGER.info("Gave back the model of {} vanilla block state(s) across {} block(s) that were left without one by having their other states used as carriers",
                statesRepaired, blocksRepaired);
        }
        if (!unresolved.isEmpty()) {
            PolymerPatcher.LOGGER.warn("These vanilla block states are still without a model, because the game's own file does not describe them either: {}",
                unresolved.size() > 20 ? unresolved.subList(0, 20) + " and " + (unresolved.size() - 20) + " more" : unresolved);
        }
    }

    private static int repairBlock(ResourcePackBuilder builder, Identifier id, Block block, List<String> unresolved) {
        String path = PREFIX + id.getPath() + ".json";

        byte[] packed = builder.getDataOrSource(path);
        if (packed == null) {
            return 0;
        }

        JsonElement parsed = JsonParser.parseString(new String(packed, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            return 0;
        }
        JsonObject packFile = parsed.getAsJsonObject();

        // A block described with multipart rules is already whole: Polymer merges the game's own rules
        // back in beneath its own, guarded so they do not also apply to the states it took
        if (packFile.has("multipart") || !packFile.has("variants")) {
            return 0;
        }
        JsonObject packVariants = packFile.getAsJsonObject("variants");

        List<BlockState> missing = new ArrayList<>();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            // A variant key may intentionally omit properties. "type=bottom" on a slab covers both
            // values of waterlogged, and the empty key covers every state. Looking only for the fully
            // qualified state name treated all of those states as absent and added a second rule for
            // each one. The client quite correctly reported tens of thousands of overlapping blockstate
            // definitions. A state needs repairing only when no rule already applies to it.
            if (!hasModelFor(packVariants, state)) {
                missing.add(state);
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }

        JsonObject vanillaVariants = readVanillaVariants(id);
        if (vanillaVariants == null) {
            return 0;
        }

        int added = 0;
        for (BlockState state : missing) {
            JsonElement model = modelFor(vanillaVariants, state);
            if (model == null) {
                unresolved.add(id + "#" + stateName(state));
                continue;
            }
            // Deep-copied because several states routinely share one entry - a block whose file is keyed
            // on nothing at all has every state pointing at the same one - and handing out the same node
            // repeatedly would put one object in the tree under many names
            packVariants.add(stateName(state), model.deepCopy());
            added++;
        }

        if (added > 0) {
            builder.addData(path, packFile.toString().getBytes(StandardCharsets.UTF_8));
        }
        return added;
    }

    /**
     * The game's own description of a block, before the pack replaced it.
     * <p>
     * Read from the assets rather than from the builder, because by this point the builder holds the
     * replacement - which is the thing with the holes in it.
     */
    private static JsonObject readVanillaVariants(Identifier id) {
        IoSupplier<InputStream> asset = ResourceHelper.getAsset(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        if (asset == null) {
            return null;
        }
        try (InputStream stream = asset.get()) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            return object.has("variants") ? object.getAsJsonObject("variants") : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The first entry of a blockstate file that applies to this state, in the file's own order. */
    private static JsonElement modelFor(JsonObject variants, BlockState state) {
        Map<String, String> values = valuesOf(state);

        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            if (applies(entry.getKey(), values)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Whether any existing variant, including a partial or empty key, already describes this state. */
    private static boolean hasModelFor(JsonObject variants, BlockState state) {
        Map<String, String> values = valuesOf(state);
        for (String key : variants.keySet()) {
            if (applies(key, values)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> valuesOf(BlockState state) {
        Map<String, String> values = new HashMap<>();
        for (Property.Value<?> value : state.getValues().toList()) {
            values.put(value.property().getName(), value.valueName());
        }
        return values;
    }

    /**
     * Whether a blockstate file's key names this state.
     * <p>
     * A key is either empty, meaning every state, or a comma-separated list of {@code property=value}
     * pairs, meaning every state that has all of them. Nothing else is valid in the format, so this is
     * the whole of it.
     */
    private static boolean applies(String key, Map<String, String> values) {
        if (key.isEmpty()) {
            return true;
        }
        for (String part : key.split(",")) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                return false;
            }
            if (!part.substring(equals + 1).equals(values.get(part.substring(0, equals)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The name a state goes by in a blockstate file: its properties in alphabetical order.
     * <p>
     * Has to agree exactly with the names Polymer writes, because the whole question being asked is
     * whether a state is already named in the file it wrote.
     */
    private static String stateName(BlockState state) {
        List<Property.Value<?>> values = new ArrayList<>(state.getValues().toList());
        values.sort(Comparator.comparing(value -> value.property().getName()));

        StringBuilder name = new StringBuilder();
        for (Property.Value<?> value : values) {
            if (!name.isEmpty()) {
                name.append(',');
            }
            name.append(value.property().getName()).append('=').append(value.valueName());
        }
        return name.toString();
    }
}
