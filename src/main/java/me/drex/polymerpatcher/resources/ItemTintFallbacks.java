package me.drex.polymerpatcher.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Translates code-backed item tint sources into vanilla custom-model-data colours.
 *
 * <p>The transport is deliberately general: a supported mod tint anywhere in an otherwise vanilla-readable
 * item definition is replaced with {@code minecraft:custom_model_data}, and the colour calculated from the
 * real server stack is put in the corresponding colour slot. What a particular mod means by a tint remains
 * in its compat package; this class only owns the vanilla bridge and can carry more resolvers later.</p>
 */
public final class ItemTintFallbacks {
    private ItemTintFallbacks() {
    }

    @FunctionalInterface
    public interface Resolver {
        @Nullable Integer resolve(JsonObject source, ItemStack stack, @Nullable Level level);
    }

    private record Provider(Predicate<JsonObject> supports, Resolver resolver) {
    }

    private record Binding(int index, JsonObject source, Resolver resolver) {
    }

    private static final Map<Identifier, List<Binding>> BINDINGS = new ConcurrentHashMap<>();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /**
     * Adds the exact meaning of one mod-defined tint source to the otherwise mod-independent bridge.
     * Compat packages register facts about their own format here; pack rewriting and stack transport do
     * not need to know which mod supplied them.
     */
    public static void register(Predicate<JsonObject> supports, Resolver resolver) {
        PROVIDERS.add(new Provider(supports, resolver));
    }

    /**
     * Returns a vanilla-readable copy when every remaining piece of the definition is vanilla-readable.
     * A null result leaves the caller's existing whole-model fallback in charge.
     */
    public static @Nullable JsonObject rewrite(JsonObject original, String namespace, String itemPath) {
        JsonObject copy = original.deepCopy();
        JsonElement model = copy.get("model");
        if (model == null) {
            return null;
        }

        List<Binding> bindings = new ArrayList<>();
        replaceTintSources(model, bindings);
        if (bindings.isEmpty() || ItemModelFallbacks.needsModCode(model)) {
            return null;
        }

        Identifier id = Identifier.fromNamespaceAndPath(namespace, itemPath);
        BINDINGS.put(id, List.copyOf(bindings));
        return copy;
    }

    private static void replaceTintSources(JsonElement element, List<Binding> bindings) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                replaceTintSources(child, bindings);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement tintsElement = object.get("tints");
        if (tintsElement != null && tintsElement.isJsonArray()) {
            JsonArray tints = tintsElement.getAsJsonArray();
            for (int i = 0; i < tints.size(); i++) {
                JsonElement tintElement = tints.get(i);
                if (!tintElement.isJsonObject()) {
                    continue;
                }
                JsonObject tint = tintElement.getAsJsonObject();
                Resolver resolver = resolverFor(tint);
                if (resolver == null) {
                    continue;
                }

                int colorIndex = bindings.size();
                bindings.add(new Binding(colorIndex, tint.deepCopy(), resolver));

                JsonObject vanilla = new JsonObject();
                vanilla.addProperty("type", "minecraft:custom_model_data");
                vanilla.addProperty("index", colorIndex);
                vanilla.addProperty("default", -1);
                tints.set(i, vanilla);
            }
        }

        // Definitions may nest models (condition/select/composite). The tints above are already replaced,
        // and visiting their tiny vanilla objects once more is harmless.
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            replaceTintSources(entry.getValue(), bindings);
        }
    }

    private static @Nullable Resolver resolverFor(JsonObject tint) {
        for (Provider provider : PROVIDERS) {
            if (provider.supports().test(tint)) {
                return provider.resolver();
            }
        }
        return null;
    }

    /** Copies all resolved colours to the vanilla carrier without disturbing other CMD channels. */
    public static void modifyItemStack(ItemStack out, ItemStack original, PacketContext context) {
        Identifier model = original.get(DataComponents.ITEM_MODEL);
        if (model == null) {
            return;
        }
        List<Binding> bindings = BINDINGS.get(model);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        LivingEntity holder = PolymerCommonUtils.getPlayer(context);
        Level level = holder == null ? null : holder.level();
        CustomModelData existing = out.get(DataComponents.CUSTOM_MODEL_DATA);
        List<Float> floats = existing == null ? List.of() : existing.floats();
        List<Boolean> flags = existing == null ? List.of() : existing.flags();
        List<String> strings = existing == null ? List.of() : existing.strings();
        List<Integer> colors = new ArrayList<>(existing == null ? List.of() : existing.colors());

        boolean changed = false;
        for (Binding binding : bindings) {
            Integer color = binding.resolver().resolve(binding.source(), original, level);
            if (color == null) {
                continue;
            }
            while (colors.size() <= binding.index()) {
                colors.add(-1);
            }
            colors.set(binding.index(), color);
            changed = true;
        }

        if (changed) {
            out.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(floats, flags, strings, List.copyOf(colors)));
        }
    }
}
