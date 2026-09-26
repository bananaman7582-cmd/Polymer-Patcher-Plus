package me.drex.polymerpatcher.item;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Weapon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executable regression check for the 26.2 semantic carrier selection. */
public final class HeldItemPresentationCheck {
    private HeldItemPresentationCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Unit tests do not reload the game's datapacks, so bind only the relevant vanilla tags
        // explicitly. This also tests that specific tags win over the generic TOOL component.
        Map<TagKey<Item>, List<Holder<Item>>> tags = new LinkedHashMap<>();
        tags.put(ItemTags.SWORDS, List.of(Items.IRON_SWORD.builtInRegistryHolder()));
        tags.put(ItemTags.AXES, List.of(Items.IRON_AXE.builtInRegistryHolder()));
        tags.put(ItemTags.PICKAXES, List.of(Items.IRON_PICKAXE.builtInRegistryHolder()));
        tags.put(ItemTags.SHOVELS, List.of(Items.IRON_SHOVEL.builtInRegistryHolder()));
        tags.put(ItemTags.HOES, List.of(Items.IRON_HOE.builtInRegistryHolder()));
        tags.put(ItemTags.SPEARS, List.of(Items.IRON_SPEAR.builtInRegistryHolder()));
        tags.put(ItemTags.MACE_ENCHANTABLE, List.of(Items.MACE.builtInRegistryHolder()));
        tags.put(ConventionalItemTags.BOW_TOOLS, List.of(Items.BOW.builtInRegistryHolder()));
        tags.put(ConventionalItemTags.CROSSBOW_TOOLS, List.of(Items.CROSSBOW.builtInRegistryHolder()));
        tags.put(ConventionalItemTags.TOOLS, List.of(Items.APPLE.builtInRegistryHolder()));
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, tags)).apply();

        // The standalone bootstrap cannot run the complete datapack component initializers: they
        // require dynamic registries (for example damage-type tags). Bind only the components this
        // focused check needs, using the same 26.2 TOOL/WEAPON convention as Item.Properties.sword/tool.
        DataComponentMap sword = DataComponentMap.builder()
            .set(DataComponents.TOOL, new Tool(List.of(), 1.0F, 2, false))
            .set(DataComponents.WEAPON, new Weapon(1)).build();
        DataComponentMap miningTool = DataComponentMap.builder()
            .set(DataComponents.TOOL, new Tool(List.of(), 1.0F, 1, true))
            .set(DataComponents.WEAPON, new Weapon(2)).build();
        Items.IRON_SWORD.builtInRegistryHolder().bindComponents(sword);
        for (Item item : List.of(Items.IRON_AXE, Items.IRON_PICKAXE, Items.IRON_SHOVEL,
            Items.IRON_HOE, Items.STICK)) {
            item.builtInRegistryHolder().bindComponents(miningTool);
        }
        Items.BONE.builtInRegistryHolder().bindComponents(sword);
        Items.FEATHER.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.WEAPON, new Weapon(1)).build());
        Items.BLAZE_ROD.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.TOOL, new Tool(List.of(), 1.0F, 1, true)).build());
        for (Item item : List.of(Items.IRON_SPEAR, Items.MACE, Items.BOW, Items.CROSSBOW, Items.SHEARS,
            Items.APPLE, Items.OAK_PLANKS)) {
            item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        }

        expect("sword", Items.IRON_SWORD, Items.IRON_SWORD);
        expect("axe", Items.IRON_AXE, Items.IRON_AXE);
        expect("pickaxe", Items.IRON_PICKAXE, Items.IRON_PICKAXE);
        expect("shovel", Items.IRON_SHOVEL, Items.IRON_SHOVEL);
        expect("hoe", Items.IRON_HOE, Items.IRON_HOE);
        expect("spear", Items.IRON_SPEAR, Items.IRON_SPEAR);
        expect("mace", Items.MACE, Items.MACE);
        expect("bow", Items.BOW, Items.BOW);
        expect("crossbow", Items.CROSSBOW, Items.CROSSBOW);
        expect("shears", Items.SHEARS, Items.SHEARS);
        expect("ordinary item, even in broad c:tools", Items.APPLE, null);
        expect("ordinary block item", Items.OAK_PLANKS, null);
        expect("TOOL and sword-like WEAPON components", Items.BONE, Items.IRON_SWORD);
        expect("TOOL and mining-like WEAPON components", Items.STICK, Items.IRON_PICKAXE);
        expect("mining TOOL component", Items.BLAZE_ROD, Items.IRON_PICKAXE);
        expect("WEAPON component without TOOL", Items.FEATHER, Items.IRON_SWORD);

        // A mod-specific carrier override must win even when a sword's TOOL/WEAPON components
        // provide a semantic answer. Real use: Alex's Mobs' carver keeps its pickaxe carrier.
        HeldItemPresentations.register(new HeldItemPresentations.Provider() {
            @Override
            public Item carrier(ItemStack original) {
                return original.is(Items.IRON_SWORD) ? Items.DIAMOND_PICKAXE : null;
            }
        });
        Item overridden = HeldItemPresentations.carrier(new ItemStack(Items.IRON_SWORD));
        if (overridden != Items.DIAMOND_PICKAXE) {
            throw new AssertionError("Explicit carrier override lost to semantic detection: " + overridden);
        }

        System.out.println("Verified 26.2 held-item components/tags, ordinary items, block items and explicit override precedence");
    }

    private static void expect(String label, Item input, Item expected) {
        Item actual = HeldItemPresentations.semanticCarrier(new ItemStack(input));
        if (actual != expected) {
            throw new AssertionError(label + " carrier: expected " + expected + ", got " + actual);
        }
    }
}
