package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.mixin.item.HolderSetNamedAccessor;
import me.drex.polymerpatcher.mixin.item.RecipeManagerAccessor;
import me.drex.polymerpatcher.mixin.item.RecipePropertySetAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets a smithing table trim modded armour.
 * <p>
 * The trim recipe takes anything in {@code #minecraft:trimmable_armor}, and a mod only gets in there by
 * naming each of its pieces. Plenty do not - Alex's Caves' diving suit, Alex's Mobs' crocodile chestplate
 * and Enditium's set were all left out, and the table simply refused them. Anything worn on the head,
 * chest, legs or feet with a look of its own is armour a trim can go on, so those are added.
 * <p>
 * Added once everything has loaded rather than while the tag is read: what an item is - whether it is
 * worn, and where - is only settled after tags and recipes are, so the tag is widened in place and the
 * smithing table's list of base items is widened to match.
 */
public final class TrimmableModdedArmor {
    private TrimmableModdedArmor() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrimmableModdedArmor::apply);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                apply(server);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void apply(MinecraftServer server) {
        try {
            HolderSet.Named<Item> tag = BuiltInRegistries.ITEM.get(ItemTags.TRIMMABLE_ARMOR).orElse(null);
            if (tag == null) {
                return;
            }
            List<Holder<Item>> added = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
                    continue;
                }
                Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.assetId().isEmpty()) {
                    continue;
                }
                Holder<Item> holder = item.builtInRegistryHolder();
                switch (equippable.slot()) {
                    case HEAD, CHEST, LEGS, FEET -> {
                        if (!tag.contains(holder)) {
                            added.add(holder);
                        }
                    }
                    default -> {
                    }
                }
            }
            if (added.isEmpty()) {
                return;
            }

            List<Holder<Item>> contents = new ArrayList<>();
            tag.forEach(contents::add);
            contents.addAll(added);
            ((HolderSetNamedAccessor<Item>) (Object) tag).polymerPatcher$bind(contents);
            // A holder answers "am I in this tag" from its own list, so each one is told as well
            for (Holder<Item> holder : added) {
                if (holder instanceof Holder.Reference<Item> reference) {
                    List<net.minecraft.tags.TagKey<Item>> tags = new ArrayList<>(reference.tags().toList());
                    tags.add(ItemTags.TRIMMABLE_ARMOR);
                    ((me.drex.polymerpatcher.mixin.item.HolderReferenceAccessor<Item>) (Object) reference).polymerPatcher$bindTags(tags);
                }
            }

            // The table checks what goes in its base slot against this list, worked out from the recipes
            // before the tag above was widened
            RecipeManagerAccessor recipes = (RecipeManagerAccessor) server.getRecipeManager();
            Map<ResourceKey<RecipePropertySet>, RecipePropertySet> sets = new HashMap<>(recipes.polymerPatcher$propertySets());
            RecipePropertySet base = sets.get(RecipePropertySet.SMITHING_BASE);
            Set<Holder<Item>> items = new LinkedHashSet<>(base == null ? Set.of() : ((RecipePropertySetAccessor) base).polymerPatcher$items());
            items.addAll(added);
            sets.put(RecipePropertySet.SMITHING_BASE, RecipePropertySetAccessor.polymerPatcher$create(Set.copyOf(items)));
            recipes.polymerPatcher$setPropertySets(Map.copyOf(sets));

            PolymerPatcher.LOGGER.info("Made {} piece(s) of modded armour trimmable", added.size());
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not make modded armour trimmable", e);
        }
    }
}
