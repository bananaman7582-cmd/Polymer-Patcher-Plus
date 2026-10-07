package me.drex.polymerpatcher.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import me.drex.polymerpatcher.util.NativeItemSync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.LivingEntity;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

public record PolyBaseItem(Item item) implements PolymerItem {

    /**
     * The stack a client is sent in place of this one: the item itself where the client's registry was made
     * to agree with this server's for its mod, and a stand-in everywhere else. See {@link NativeItemSync}.
     */
    @Override
    public ItemStack getPolymerItemStack(ItemStack itemStack, TooltipFlag tooltipType, PacketContext context, HolderLookup.Provider lookup) {
        if (NativeItemSync.sendsRaw(context, item)) {
            return itemStack;
        }

        // A stand-in carries the real stack along with it, written down the way a stack is written into a
        // file - and that writing refuses any count above 99, because no ordinary stack has one. A stack
        // read out of a mod's own container can: a hundred and seventy-eight guanostone in a barrel is a
        // perfectly good stack that simply cannot be written. Every one of those threw, hundreds of times
        // over, and the stand-in came back without the stack it was supposed to be carrying.
        // So it is written at a count the writing allows and handed back at the count it really has. The
        // count travels in the stack itself, where it was always going to come from
        int count = itemStack.getCount();
        if (count <= MAX_WRITTEN_COUNT) {
            return keepDamageOutOfIdentity(
                transformSafely(itemStack, tooltipType, context, lookup), itemStack);
        }

        ItemStack written = transformSafely(itemStack.copyWithCount(MAX_WRITTEN_COUNT), tooltipType, context, lookup);
        written.setCount(count);
        return keepDamageOutOfIdentity(written, itemStack);
    }

    /**
     * The transform inside Polymer's encode writes a {@code sync/items} payload as it goes and, when
     * anything throws, swallows the throw and sends the client the truncated stream anyway, which
     * disconnects the player at decode time. A single item whose components cannot be encoded - a
     * mod registering a component type lazily after its registry froze is what has done it so far -
     * must therefore cost that item its stand-in, not every client its connection.
     */
    private ItemStack transformSafely(ItemStack source, TooltipFlag tooltipType, PacketContext context,
                                      HolderLookup.Provider lookup) {
        try {
            return PolymerItem.super.getPolymerItemStack(source, tooltipType, context, lookup);
        } catch (Throwable t) {
            PolymerPatcher.LOGGER.error(
                "Could not transform {} for a vanilla client; sending a bare stand-in instead", source, t);
            try {
                return new ItemStack(getPolymerItem(source, context), source.getCount());
            } catch (Throwable t2) {
                return new ItemStack(Items.TRIAL_KEY, source.getCount());
            }
        }
    }

    /**
     * The vanilla hand renderer deliberately ignores the public DAMAGE component when deciding whether
     * an item was replaced. Polymer also stores the whole original stack inside CUSTOM_DATA, however,
     * and that second copy of DAMAGE was not ignored. Every durability point therefore looked like an
     * entirely new held item and replayed the equip bob.
     *
     * <p>The visible carrier still keeps its ordinary DAMAGE component and durability bar. Only the
     * redundant nested copy is removed, and {@link #getDecodedItemStack} restores it if a carrier ever
     * travels back from a client.</p>
     */
    private static ItemStack keepDamageOutOfIdentity(ItemStack out, ItemStack original) {
        CustomData custom = out.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return out;
        }

        CompoundTag root = custom.copyTag();
        root.getCompound(PolymerItemUtils.POLYMER_STACK)
            .flatMap(stack -> stack.getCompound("components"))
            .ifPresent(components -> {
                components.remove("minecraft:damage");
                Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(original.getItem());
                if (id != null) {
                    components.getCompound("minecraft:custom_data").ifPresent(data ->
                        StackIdentitySanitizers.sanitize(original, id, data));
                }
            });
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return out;
    }

    @Override
    public ItemStack getDecodedItemStack(ItemStack clientItem, ItemStack out, PacketContext context,
                                         HolderLookup.Provider lookup) {
        Integer damage = clientItem.get(DataComponents.DAMAGE);
        if (damage != null && out.isDamageableItem()) {
            out.set(DataComponents.DAMAGE, Math.clamp(damage, 0, out.getMaxDamage()));
        }
        return out;
    }

    /** The largest count the game will write a stack with; see {@code ItemStack.CODEC}. */
    private static final int MAX_WRITTEN_COUNT = 99;

    /**
     * Answered the same way as the stack above, because Polymer asks this separately wherever it writes an
     * item without its stack - in tags, recipe displays and holder sets. If the two disagreed a client would
     * be handed the real item in its inventory and a stand-in in the recipe that makes it.
     */
    @Override
    public boolean canSyncRawToClient(PacketContext context) {
        return NativeItemSync.sendsRaw(context, item);
    }

    /**
     * Answered the same way again, because this is what actually decides the item's number on the wire. Polymer
     * writes every item id - the one inside an item stack included - by asking for the replacement, without
     * asking {@link #canSyncRawToClient} first. Left to the default, the real stack went out with a stand-in's
     * number on it and none of the stand-in's model: a plain trial key.
     */
    @Override
    public Item getPolymerReplacement(Item item, PacketContext context) {
        if (NativeItemSync.sendsRaw(context, item)) {
            return item;
        }
        return PolymerItem.super.getPolymerReplacement(item, context);
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext packetContext) {
        // Modern shields are component-defined and do not have to subclass ShieldItem (Enderscape's
        // rubble shield is one example). The component is the capability, so this works for shields
        // from any mod instead of relying on a class-name or mod whitelist.
        // A cave biome map says where its biome is, which is all an explorer map needs - see CaveMaps.
        // Before anything else, because a map is a map whatever else it also is
        if (ConvertedMaps.isConverted(itemStack)) {
            return Items.FILLED_MAP;
        }

        // Component-defined equipment follows native client behavior without any mod list. Gliders
        // are checked before generic chest equipment so they remain real Elytra carriers.
        Item equipmentCarrier = EquipmentPresentations.carrier(itemStack);
        if (equipmentCarrier != null) {
            return equipmentCarrier;
        }

        // Some custom tools deliberately use no vanilla use animation, but still need the ordinary
        // hand geometry and mining pose of a tool. Providers describe that semantic carrier while
        // Polymer keeps the custom model and server-side behaviour. The extension is global; mod
        // compat only supplies facts which cannot be inferred from components.
        Item carrier = HeldItemPresentations.carrier(itemStack);
        if (carrier != null) {
            return carrier;
        }

        // How the thing is used decides how it is held and how the arms move, and a client works that
        // out from the item it was given - not from anything the server says afterwards. A modded spear
        // sent as a trial key is therefore thrown with no wind-up and no throw, a modded bow is drawn
        // with the hands in the wrong place, and a mob holding one holds it wrongly too. Matching the
        // animation to a vanilla item that has it costs nothing - Polymer keeps the mod's own model on
        // top - and every one of those motions comes back.
        ItemUseAnimation using = itemStack.getUseAnimation();
        Item byMotion = switch (using) {
            case BOW -> Items.BOW;
            case CROSSBOW -> Items.CROSSBOW;
            case SPEAR, TRIDENT -> Items.TRIDENT;
            case SPYGLASS -> Items.SPYGLASS;
            case TOOT_HORN -> Items.GOAT_HORN;
            case BRUSH -> Items.BRUSH;
            case DRINK -> Items.POTION;
            case EAT -> Items.BREAD;
            default -> null;
        };
        if (byMotion != null) {
            return byMotion;
        }

        // An item used by holding the button down is handled below rather than here - see heldDown. It
        // used to be sent as a shield, because a shield is the obvious thing a client will hold; the
        // cost was that a client holds a shield the way you hold a shield, so a gun came up across the
        // chest. Nothing about the carrier has to say "held" any more
        return Items.TRIAL_KEY;
    }

    /**
     * Whether this item is used by holding the button down, with no motion of its own to show for it.
     * <p>
     * A client decides whether it is holding an item from the item it was given, and nothing the server
     * says afterwards changes its mind: an item it thinks is used instantly never begins a hold, never
     * sends the release, and an item whose whole behaviour happens between those two does nothing at
     * all. That is what left the galena gauntlet inert.
     */
    private static boolean heldDown(ItemStack stack, PacketContext context) {
        try {
            LivingEntity holder = PolymerCommonUtils.getPlayer(context);
            // Item stacks are also converted for inventory/recipe packets whose PacketContext has no
            // live player. Duration implementations such as Alex's Caves' galena gauntlet do not use
            // the holder at all, and refusing those contexts meant the same gauntlet sometimes reached
            // the client without the component that makes right-click begin a hold. Let the item answer;
            // implementations which truly require a holder are contained by the guard below.
            return stack.getUseDuration(holder) > 0
                && stack.getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.NONE;
        } catch (Throwable ignored) {
            // Asking cost more than the answer was worth; the item is no worse off than before
            return false;
        }
    }

    /**
     * What makes a client hold an item down without holding it like anything in particular.
     * <p>
     * Every way a client can be made to hold a button comes with a pose: a shield is held across the
     * chest, a bow is drawn, a horn goes to the lips. All but one - a thing being consumed may name the
     * motion it is consumed with, and one of the motions it may name is none at all. So that is what a
     * gun and a gauntlet are sent as: held, for an hour, with the arm left alone.
     * <p>
     * An hour is also what keeps it silent. The eating sounds and crumbs do not start until roughly a
     * fifth of the way through, which for an hour is thirteen minutes - longer than anybody holds a
     * trigger - and nothing is ever finished, so nothing is ever swallowed.
     */
    private static final net.minecraft.world.item.component.Consumable HELD_STILL =
        net.minecraft.world.item.component.Consumable.builder()
            .consumeSeconds(3600.0F)
            .animation(net.minecraft.world.item.ItemUseAnimation.NONE)
            .hasConsumeParticles(false)
            .build();

    /**
     * What makes a shield a shield to a client: without it, a stand-in sent as one is never held down.
     * <p>
     * It reduces nothing and takes no damage. Every one of those is decided on this server by the item
     * the player is really holding; this exists only so the client agrees that the button can be held.
     */
    private static final net.minecraft.world.item.component.BlocksAttacks HELD_DOWN =
        new net.minecraft.world.item.component.BlocksAttacks(0.0F, 1.0F, java.util.List.of(),
            net.minecraft.world.item.component.BlocksAttacks.ItemDamageFunction.DEFAULT,
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

    @Override
    public void modifyBasePolymerItemStack(ItemStack out, ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        // A client works out whether it is holding an item down from the item it was given, and nothing
        // the server says afterwards changes its mind. In 26.2 a shield is only usable while it carries
        // the component that makes it block - so a stand-in sent as a shield without one is never used at
        // all: the hold never begins, the release is never sent, and an item whose whole behaviour happens
        // between those two does nothing. That is what left the galena gauntlet inert, and what left both
        // it and the resistor shield standing still while their models waited on a use that never started.
        if (out.is(Items.SHIELD) && !out.has(DataComponents.BLOCKS_ATTACKS)) {
            out.set(DataComponents.BLOCKS_ATTACKS, HELD_DOWN);
        }

        if (heldDown(stack, context) && HeldItemPresentations.allowHeldStill(stack)
            && !out.has(DataComponents.CONSUMABLE)
            && !out.has(DataComponents.BLOCKS_ATTACKS)) {
            out.set(DataComponents.CONSUMABLE, HELD_STILL);
        }

        ItemStackPatches.apply(out, stack, context);
        HeldItemPresentations.modifyItemStack(out, stack);
        me.drex.polymerpatcher.resources.ItemTintFallbacks.modifyItemStack(out, stack, context);
        me.drex.polymerpatcher.resources.EquipmentFallbacks.modifyItemStack(out, stack);
        me.drex.polymerpatcher.resources.GliderFallbacks.modifyItemStack(out, stack);

        // Sent as an explorer map to the cave biome: a real map, centred there and marked with a
        // cross, which fills itself in and shows the player where they are as they walk
        if (ConvertedMaps.isConverted(stack)) {
            ServerPlayer holder = PolymerCommonUtils.getPlayer(context);
            if (holder != null) {
                ConvertedMaps.dress(out, stack, holder);
            }
        }

        // Do not remove the equipment asset for custom-rendered armour. When an ordinary equipment
        // texture exists it is the only representation that can follow the wearer at render-frame
        // speed and participate in the inventory player preview.

        // Last, so it also catches a model set by anything above. A definition only the mod's own code can
        // read is left in the pack as the mod wrote it, for clients that have the mod, and a readable copy
        // sits beside it - which is the one a stand-in has to name. See ItemModelFallbacks
        net.minecraft.resources.Identifier model = out.get(DataComponents.ITEM_MODEL);
        if (model != null) {
            net.minecraft.resources.Identifier standIn = me.drex.polymerpatcher.resources.ItemModelFallbacks.standInFor(model);
            if (standIn != null) {
                out.set(DataComponents.ITEM_MODEL, standIn);
            }
        }
    }

    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult, InteractionResult actionResult) {
        return actionResult.consumesAction();
    }

    @Override
    public boolean isIgnoringBlockInteractionPlaySoundExceptedEntity(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult) {
        return item instanceof BlockItem;
    }
}
