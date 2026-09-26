package me.drex.polymerpatcher.compat.enderscape;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.bool.BooleanProperty;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.select.SelectProperty;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.penumbra.enderscape.Enderscape;
import net.penumbra.enderscape.item.crafting.RustleRecipe;
import net.penumbra.enderscape.item.crafting.VoidLachrymaRecipe;
import net.penumbra.enderscape.registry.component.EnderscapeDataComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Enderscape-linked half of the optional compatibility layer. */
final class EnderscapeIntegration {
    private static boolean initialized;

    private EnderscapeIntegration() {
    }

    static void init() {
        if (initialized) return;
        initialized = true;

        BooleanProperty.TYPES.put(Enderscape.id("enabled"), EnabledBooleanProperty.MAP_CODEC);
        SelectProperty.TYPES.put(Enderscape.id("dye_color"), EnderscapeDyeColorProperty.TYPE);
        SelectProperty.TYPES.put(Enderscape.id("rubble_shield_variant"), EnderscapeRubbleShieldVariantProperty.TYPE);
        EnderscapeResourcePackGenerator.setup();
        me.drex.polymerpatcher.resources.ItemModelFallbacks.registerConvertedInPlace(
            Enderscape.id("rubble_shield"));
        me.drex.polymerpatcher.resources.ItemModelFallbacks.registerConvertedInPlace(
            Enderscape.id("mirror"));
        me.drex.polymerpatcher.resources.ItemModelFallbacks.registerConvertedInPlace(
            Enderscape.id("magnia_attractor"));
        me.drex.polymerpatcher.item.ItemStackPatches.register(EnderscapeIntegration::modifyItemStack);

        // Enderscape adds equippable data to shulker shells and two custom recipe-book categories.
        // Both must be represented by values known to a vanilla client.
        PolymerItemUtils.syncDefaultComponent(Items.SHULKER_SHELL, DataComponents.EQUIPPABLE);
        PolymerSyncedObject.setSyncedObject(BuiltInRegistries.RECIPE_BOOK_CATEGORY,
            RustleRecipe.CATEGORY, (object, context) -> RecipeBookCategories.CAMPFIRE);
        PolymerSyncedObject.setSyncedObject(BuiltInRegistries.RECIPE_BOOK_CATEGORY,
            VoidLachrymaRecipe.CATEGORY, (object, context) -> RecipeBookCategories.CAMPFIRE);
    }

    static void modifyItemStack(ItemStack out, ItemStack stack, PacketContext context) {
        List<Boolean> flags = new ArrayList<>();
        List<String> strings = new ArrayList<>();

        if (stack.has(EnderscapeDataComponents.ENABLED)) {
            var player = PolymerCommonUtils.getPlayer(context);
            flags.add(EnabledBooleanProperty.test(stack, player != null ? player.level() : null, player));
        }

        if (stack.has(EnderscapeDataComponents.ENABLED) || stack.has(EnderscapeDataComponents.FUELED_TOOL)) {
            out.set(DataComponents.MAX_DAMAGE, 13);
            out.set(DataComponents.DAMAGE, 13 - stack.getBarWidth());
        }

        if (stack.has(EnderscapeDataComponents.DYE_COLOR)) {
            out.set(DataComponents.DYE, stack.get(EnderscapeDataComponents.DYE_COLOR));
        }

        if (stack.has(EnderscapeDataComponents.RUBBLE_SHIELD_VARIANT)) {
            strings.add(Objects.requireNonNull(stack.get(EnderscapeDataComponents.RUBBLE_SHIELD_VARIANT)).toString());
        }

        if (!flags.isEmpty() || !strings.isEmpty()) {
            out.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), flags, strings, List.of()));
        }
    }
}
