package me.drex.polymerpatcher.item;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import java.util.*;

/** Exercises the real 26.2 recipe-book selection with mismatched carrier and server inventories. */
public final class RecipeBookCraftabilityCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : List.of(Items.COBBLESTONE, Items.DIRT, Items.OAK_PLANKS, Items.STICK)) {
            item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        }
        RecipeDisplayEntry furnace = recipe(0, Collections.nCopies(8, Ingredient.of(Items.COBBLESTONE)));
        if (!RecipeBookContents.judgedByServer(true, furnace)) {
            throw new AssertionError("Pure vanilla furnace recipe escaped server judgment");
        }
        if (RecipeBookContents.judgedByServer(false, furnace)) {
            throw new AssertionError("Explicitly disabled policy was ignored");
        }
        StackedItemContents client = contents(Items.COBBLESTONE, 64);
        StackedItemContents real = contents(Items.DIRT, 64);
        if (!furnace.canCraft(client) || furnace.canCraft(real)) {
            throw new AssertionError("Carrier collision fixture did not reproduce the false positive");
        }
        assertClientAnswer(furnace, real, client, false);
        assertClientAnswer(furnace, contents(Items.COBBLESTONE, 7), client, false);
        assertClientAnswer(furnace, contents(Items.COBBLESTONE, 8), new StackedItemContents(), true);
        assertClientAnswer(furnace, contents(Items.COBBLESTONE, 1), client, false);
        RecipeDisplayEntry sticks = recipe(1, List.of(Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)));
        assertClientAnswer(sticks, contents(Items.OAK_PLANKS, 2), new StackedItemContents(), true);
        assertClientAnswer(sticks, contents(Items.OAK_PLANKS, 1), contents(Items.OAK_PLANKS, 64), false);
        verifyNativeAndMixedInventories();
        RecipeDisplayEntry unknown = new RecipeDisplayEntry(furnace.id(), furnace.display(), furnace.group(),
            furnace.category(), Optional.empty());
        if (RecipeBookContents.judgedByServer(true, unknown)) {
            throw new AssertionError("Missing requirements were incorrectly treated as a free recipe");
        }
        System.out.println("Verified recipe-book carrier collisions, vanilla-only recipes, native/mixed inventories, ingredient quantities and craftable transitions");
    }

    private static void verifyNativeAndMixedInventories() {
        // Distinct registered holders model native items: the server keeps these identities whether
        // the recipient sees the real item or a Polymer carrier. No mod-presence guess gates judgment.
        RecipeDisplayEntry nativeRecipe = recipe(2,
            List.of(Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)));
        assertClientAnswer(nativeRecipe, contents(Items.OAK_PLANKS, 2), contents(Items.OAK_PLANKS, 2), true);
        assertClientAnswer(nativeRecipe, contents(Items.OAK_PLANKS, 1), contents(Items.OAK_PLANKS, 1), false);

        RecipeDisplayEntry mixedRecipe = recipe(3,
            List.of(Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.DIRT)));
        StackedItemContents real = contents(Items.OAK_PLANKS, 1);
        real.accountStack(new ItemStack(Items.DIRT));
        StackedItemContents nativeClient = contents(Items.OAK_PLANKS, 1);
        nativeClient.accountStack(new ItemStack(Items.DIRT));
        StackedItemContents mixedClient = contents(Items.OAK_PLANKS, 1);
        mixedClient.accountStack(new ItemStack(Items.COBBLESTONE)); // Missing mod's dirt-like item uses this carrier.
        assertClientAnswer(mixedRecipe, real, nativeClient, true);
        assertClientAnswer(mixedRecipe, real, mixedClient, true);
        assertClientAnswer(mixedRecipe, contents(Items.OAK_PLANKS, 1), mixedClient, false);

        // Alternatives (including tag-expanded ingredients) must accept the real item, not its look.
        RecipeDisplayEntry alternatives = recipe(4,
            List.of(Ingredient.of(Items.OAK_PLANKS, Items.DIRT), Ingredient.of(Items.OAK_PLANKS, Items.DIRT)));
        assertClientAnswer(alternatives, real, mixedClient, true);
        assertClientAnswer(alternatives, contents(Items.COBBLESTONE, 64), nativeClient, false);
        if (!RecipeBookContents.judgedByServer(true, nativeRecipe)
            || !RecipeBookContents.judgedByServer(true, mixedRecipe)) {
            throw new AssertionError("Native/mixed recipe escaped server judgment");
        }
    }

    private static RecipeDisplayEntry recipe(int id, List<Ingredient> ingredients) {
        var slot = new SlotDisplay.ItemSlotDisplay(Items.COBBLESTONE);
        return new RecipeDisplayEntry(new RecipeDisplayId(id),
            new ShapedCraftingRecipeDisplay(1, 1, List.of(slot), slot, slot), OptionalInt.empty(),
            RecipeBookCategories.CRAFTING_MISC, Optional.of(ingredients));
    }

    private static StackedItemContents contents(Item item, int count) {
        var result = new StackedItemContents();
        result.accountStack(new ItemStack(item, count));
        return result;
    }

    private static void assertClientAnswer(RecipeDisplayEntry recipe, StackedItemContents server,
                                            StackedItemContents client, boolean expected) {
        var answer = RecipeBookContents.answer(recipe, recipe.canCraft(server));
        var collection = new RecipeCollection(List.of(answer));
        collection.selectRecipes(client, display -> true);
        if (collection.isCraftable(recipe.id()) != expected) {
            throw new AssertionError("Wrong client craftability for " + recipe.id());
        }
        if (answer.display() != recipe.display()) throw new AssertionError("Recipe visuals changed");
        if (!answer.id().equals(recipe.id()) || !answer.group().equals(recipe.group())
            || answer.category() != recipe.category()) throw new AssertionError("Recipe identity/category changed");
        if (recipe.craftingRequirements().isEmpty() || recipe.craftingRequirements().get().isEmpty()) {
            throw new AssertionError("Original recipe requirements were mutated");
        }
    }
}
