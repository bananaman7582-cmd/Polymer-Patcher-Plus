package me.drex.polymerpatcher.entity.armor;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.entity.PolyModelInstance;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The armour pieces that are drawn from a model of their own rather than painted onto the player.
 * <p>
 * Ordinary armour is a texture the game lays over the player's own body, and that arrives through the
 * resource pack and works already. A piece with real shape to it - Alex's Mobs' rocky chestplate, its
 * hats, its boots - is not that: the mod registers a renderer for it and draws a model of its own,
 * client side. A player without the mod has no such renderer, so the piece is simply not drawn, which
 * is why the plain armour showed up and the interesting armour did not.
 * <p>
 * The client dump asks Fabric's registered armor renderers what model they actually select and records
 * the item-to-layer association. The layer does not have to follow any naming convention and no list of
 * mods is involved. Older dumps retain the original item-name heuristic as a compatibility fallback.
 * A selected layer is built into a plain {@link HumanoidModel}, which animates arms, legs and head; what
 * is left behind is any extra animation that the armor's own Java model added on top.
 */
public final class ArmorModels {

    /** Where a piece of armour keeps its texture, by long-standing convention. */
    private static final String TEXTURE_PREFIX = "textures/armor/";

    private static final Map<Item, List<Entry>> BY_ITEM = new IdentityHashMap<>();

    /** Handed to the pack generator so each part of each piece gets an item model written for it. */
    public static final List<PolyModelInstance<?, ?, ?>> ASSETS = new ArrayList<>();
    private static final List<RegisteredFallback> FALLBACKS = new CopyOnWriteArrayList<>();

    private ArmorModels() {
    }

    /**
     * One piece of armour: the model to pose, and the texture its generated part models are filed under.
     */
    public record Entry(Item item, ModelLayerLocation layer, HumanoidModel<HumanoidRenderState> model, Identifier texture) {
    }

    @FunctionalInterface
    public interface FallbackSink {
        void add(Item item, ModelLayerLocation layer, ModelPart root, Identifier texture,
                 @Nullable HumanoidModel<HumanoidRenderState> model);
    }

    @FunctionalInterface
    public interface Fallback {
        void discover(FallbackSink sink);
    }

    private record RegisteredFallback(String description, Fallback fallback) {
    }

    /** Adds a naming/reflection convention used only when the renderer dump has no authoritative entry. */
    public static void registerFallback(String description, Fallback fallback) {
        FALLBACKS.add(new RegisteredFallback(description, fallback));
    }

    @Nullable
    public static Entry get(ItemStack stack) {
        List<Entry> entries = getAll(stack);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public static List<Entry> getAll(ItemStack stack) {
        return stack.isEmpty() ? List.of() : BY_ITEM.getOrDefault(stack.getItem(), List.of());
    }

    public static boolean isEmpty() {
        return BY_ITEM.isEmpty();
    }

    /**
     * Finds every piece of armour that carries a model of its own among the layers the dump recorded.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void discover(Map<ModelLayerLocation, ModelPart> bakedModels,
                                List<RenderRegistry.ArmorInfo> rendererArmor) {
        BY_ITEM.clear();
        ASSETS.clear();

        List<String> rejected = new ArrayList<>();

        // The authoritative path: the client dump records which layer an ArmorRenderer actually drew
        // for which item. No item-name convention or mod-specific knowledge is involved.
        for (RenderRegistry.ArmorInfo info : rendererArmor) {
            ModelPart root = bakedModels.get(info.modelLayer());
            if (root == null) {
                rejected.add(BuiltInRegistries.ITEM.getKey(info.item()) + " (its recorded model layer "
                    + info.modelLayer() + " was not present in the dump)");
                continue;
            }
            add(info.item(), info.modelLayer(), root, info.texture(), rejected);
        }

        // Optional naming/reflection conventions are registered by compat packages. The discovery engine
        // itself has no mod names, and an authoritative renderer-dump entry always wins.
        for (RegisteredFallback registered : FALLBACKS) {
            try {
                registered.fallback().discover((item, layer, root, texture, model) -> {
                    if (!BY_ITEM.containsKey(item)) {
                        add(item, layer, root, texture, rejected, model);
                    }
                });
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not look for {} armour models", registered.description(), e);
            }
        }

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                continue;
            }

            if (BY_ITEM.containsKey(item)) {
                continue;
            }

            // A texture that is not there would be written into the atlas as a sprite pointing at
            // nothing, which costs every other sprite in that atlas rather than just this one
            Identifier texture = Identifier.fromNamespaceAndPath(id.getNamespace(), TEXTURE_PREFIX + id.getPath() + ".png");
            boolean hasTexture = ResourceHelper.getAsset(texture.getNamespace(), texture.getPath()) != null;

            // Any layer the item's own name points at, whatever that layer is called - "main" is only
            // the usual choice, not a rule
            ModelPart root = null;
            ModelLayerLocation layer = null;
            for (Map.Entry<ModelLayerLocation, ModelPart> entry : bakedModels.entrySet()) {
                if (entry.getKey().model().equals(id)) {
                    layer = entry.getKey();
                    root = entry.getValue();
                    break;
                }
            }

            if (root == null) {
                // Only worth saying for something that looks like armour, or every item in the game
                // would have a line of its own
                if (hasTexture) {
                    rejected.add(id + " (no model layer is registered under that name)");
                }
                continue;
            }

            add(item, layer, root, texture, rejected);
        }

        if (!BY_ITEM.isEmpty()) {
            PolymerPatcher.LOGGER.info("Found {} armour pieces with models of their own: {}", BY_ITEM.size(),
                BY_ITEM.keySet().stream().map(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))).sorted().toList());
        }

        if (!rejected.isEmpty()) {
            PolymerPatcher.LOGGER.info("Armour that looks like it carries a model of its own but could not be used: {}",
                rejected.stream().sorted().toList());
        }
    }

    private static void add(Item item, ModelLayerLocation layer, ModelPart root, Identifier texture,
                            List<String> rejected) {
        add(item, layer, root, texture, rejected, null);
    }

    /**
     * @param prebuilt the mod's own model where one could be built, rather than a plain humanoid body.
     *                 It matters for anything with parts of its own: a plain body has nowhere to put a
     *                 cloak's cape and tails, so they stay wherever the model file left them - which is
     *                 what made the cloak of darkness a black shape jutting out of the wearer's side
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void add(Item item, ModelLayerLocation layer, ModelPart root, Identifier texture,
                            List<String> rejected, @Nullable HumanoidModel<HumanoidRenderState> prebuilt) {
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) {
            return;
        }

        if (ResourceHelper.getAsset(texture.getNamespace(), texture.getPath()) == null) {
            rejected.add(itemId + " (no texture at " + texture + ")");
            return;
        }

        HumanoidModel<HumanoidRenderState> model;
        try {
            model = prebuilt != null ? prebuilt : new HumanoidModel<>(root);
        } catch (Throwable e) {
            rejected.add(itemId + " (its model layer is not a humanoid body: " + e + ")");
            return;
        }

        Identifier stripped = texture.withPath(texture.getPath().replace("textures/", "").replace(".png", ""));
        List<Entry> entries = BY_ITEM.computeIfAbsent(item, ignored -> new ArrayList<>());
        boolean duplicate = entries.stream().anyMatch(entry ->
            entry.layer().equals(layer) && entry.texture().equals(stripped));
        if (duplicate) {
            return;
        }

        entries.add(new Entry(item, layer, model, stripped));
        ASSETS.add(PolyModelInstance.create(null, layer, root.getAllParts(), stripped));
    }
}
