package me.drex.polymerpatcher.item;

import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.component.Weapon;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared extension surface for items whose Java renderer supplies hand geometry or state. */
public final class HeldItemPresentations {
    private HeldItemPresentations() {
    }

    public interface Provider {
        default @Nullable String definition(ResourcePackBuilder builder, String namespace,
                                            String itemPath, Identifier shape) {
            return null;
        }

        default @Nullable Float geometryScale(String namespace, String itemPath) {
            return null;
        }

        default void addTextureVariants(ResourcePackBuilder builder, String namespace, String itemPath,
                                        JsonObject baseModel, Identifier modelId) {
        }

        default void modifyItemStack(ItemStack out, ItemStack original) {
        }

        /** A vanilla item whose native hand/use behaviour best matches this custom item. */
        default @Nullable Item carrier(ItemStack original) {
            return null;
        }

        /** Whether this item needs the synthetic hour-long no-pose use component. */
        default boolean allowHeldStill(ItemStack original) {
            return true;
        }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static @Nullable String definition(ResourcePackBuilder builder, String namespace,
                                              String itemPath, Identifier shape) {
        for (Provider provider : PROVIDERS) {
            String definition = provider.definition(builder, namespace, itemPath, shape);
            if (definition != null) {
                return definition;
            }
        }
        return null;
    }

    public static float geometryScale(String namespace, String itemPath) {
        for (Provider provider : PROVIDERS) {
            Float scale = provider.geometryScale(namespace, itemPath);
            if (scale != null) {
                return scale;
            }
        }
        // Citadel boxes are authored in model pixels and the baked vanilla shape is restored to full
        // size by its item definition. This was previously hidden as the default branch of one mod's
        // compat method even though it applies to every Citadel item.
        return 0.25F;
    }

    public static void addTextureVariants(ResourcePackBuilder builder, String namespace, String itemPath,
                                          JsonObject baseModel, Identifier modelId) {
        for (Provider provider : PROVIDERS) {
            provider.addTextureVariants(builder, namespace, itemPath, baseModel, modelId);
        }
    }

    public static void modifyItemStack(ItemStack out, ItemStack original) {
        for (Provider provider : PROVIDERS) {
            provider.modifyItemStack(out, original);
        }
    }

    public static @Nullable Item carrier(ItemStack original) {
        // A mod-specific exception is deliberately first. A custom renderer may need a carrier
        // unlike its mining/combat capability; the model definition and its transforms are a
        // separate decision and are not changed by anything below.
        for (Provider provider : PROVIDERS) {
            Item carrier = provider.carrier(original);
            if (carrier != null) {
                return carrier;
            }
        }

        Item semantic = semanticCarrier(original);
        if (semantic != null) {
            return semantic;
        }

        // Last resort for badly defined items: only a few narrow names, never a growing mod list.
        // Minecraft 26.2's sword builder exposes TOOL and WEAPON components, and well-defined mod
        // daggers/whips already reach the branch above without needing this guess.
        Identifier id = BuiltInRegistries.ITEM.getKey(original.getItem());
        if (id != null) {
            String path = id.getPath();
            if (path.endsWith("_dagger") || path.equals("dagger")
                || path.endsWith("_knife") || path.equals("knife")
                || path.endsWith("_whip") || path.equals("whip")) {
                return Items.IRON_SWORD;
            }
        }
        return null;
    }

    /**
     * A vanilla carrier with the same broad hand/use family, inferred without looking at an item's
     * name. Specific tags take precedence over generic components because both swords and mining tools
     * carry TOOL and WEAPON in 26.2. The generic Fabric {@code c:tools} tag is intentionally omitted:
     * installed mods put shields and rayguns in it as well as hand tools.
     */
    static @Nullable Item semanticCarrier(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem) {
            return null;
        }

        ItemUseAnimation motion = stack.getUseAnimation();
        if (motion == ItemUseAnimation.BOW) {
            return Items.BOW;
        }
        if (motion == ItemUseAnimation.CROSSBOW) {
            return Items.CROSSBOW;
        }
        // The 26.2 spear/trident use pose is not the sword pose. Leave these to PolyBaseItem's
        // existing motion mapping rather than having a broad weapon component override it.
        if (motion == ItemUseAnimation.SPEAR || motion == ItemUseAnimation.TRIDENT) {
            return null;
        }

        if (stack.is(ConventionalItemTags.BOW_TOOLS) || stack.is(ItemTags.BOW_ENCHANTABLE)) {
            return Items.BOW;
        }
        if (stack.is(ConventionalItemTags.CROSSBOW_TOOLS) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)) {
            return Items.CROSSBOW;
        }
        if (stack.getItem() instanceof ShearsItem || stack.is(ConventionalItemTags.SHEAR_TOOLS)) {
            return Items.SHEARS;
        }
        if (stack.is(ItemTags.SPEARS)) {
            return Items.IRON_SPEAR;
        }
        if (stack.is(ItemTags.MACE_ENCHANTABLE)) {
            return Items.MACE;
        }

        if (stack.is(ItemTags.SWORDS) || stack.is(ConventionalItemTags.MELEE_WEAPON_TOOLS)
            || stack.is(ItemTags.MELEE_WEAPON_ENCHANTABLE)) {
            return Items.IRON_SWORD;
        }
        if (stack.is(ItemTags.AXES)) {
            return Items.IRON_AXE;
        }
        if (stack.is(ItemTags.PICKAXES)) {
            return Items.IRON_PICKAXE;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return Items.IRON_SHOVEL;
        }
        if (stack.is(ItemTags.HOES)) {
            return Items.IRON_HOE;
        }

        Weapon weapon = stack.get(DataComponents.WEAPON);
        if (stack.has(DataComponents.TOOL)) {
            // Both families receive TOOL in 26.2. Vanilla swords use one durability point per
            // attack; mining tools use two. Treat only that exact sword convention as evidence for
            // a sword, otherwise use the generic mining-tool hold.
            return weapon != null && weapon.itemDamagePerAttack() == 1
                ? Items.IRON_SWORD : Items.IRON_PICKAXE;
        }
        if (weapon != null) {
            return Items.IRON_SWORD;
        }
        if (stack.is(ConventionalItemTags.MINING_TOOL_TOOLS) || stack.is(ItemTags.MINING_ENCHANTABLE)) {
            return Items.IRON_PICKAXE;
        }

        return null;
    }

    public static boolean allowHeldStill(ItemStack original) {
        for (Provider provider : PROVIDERS) {
            if (!provider.allowHeldStill(original)) {
                return false;
            }
        }
        return true;
    }
}
