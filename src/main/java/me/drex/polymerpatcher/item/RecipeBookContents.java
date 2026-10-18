package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.util.NativeClients;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * Decides what a player's recipe book is told, including native and partially modded clients.
 * <p>
 * A client works out for itself which recipes it can make: it counts up what it is holding and matches
 * that against the ingredients. Both halves of that are stand-ins here - a hundred different modded
 * items arrive as the same few vanilla ones - so the matching collapses, and the book lights up with
 * recipes that cannot be made. Two things are done about it, because the collapse happens twice.
 * <p>
 * <b>Missing-mod recipes can optionally be hidden.</b> This is controlled separately from craftability
 * so servers can still show modded recipes to players using the resource pack.
 * <p>
 * <b>Craftability is judged on the server.</b> Vanilla recipes take tags rather than items - two planks,
 * not two oak planks - and mods add their own wood to those tags. A client running Polymer but not the
 * mod is sent those entries as the same stand-ins it is shown everywhere else, so the tag that reaches
 * it has a handful of stand-ins sitting in it, and its own modded items are those very stand-ins.
 * Carrying any two modded items is then carrying two of something every one of those tags accepts:
 * two lumps of guano lit up every recipe asking for two of anything a mod has joined, and clicking one
 * laid out a grid the server would not fill. Even vanilla-only recipes can collide with an unrelated
 * inventory item's carrier. All known requirements are evaluated against the real server inventory.
 * Native clients use the same real counts; having some or all mods never changes ingredient identity
 * on the server, and must not bypass this check while other inventory items still use carriers.
 * <p>
 * The recipes themselves are untouched, and so is what the book <em>shows</em> - the ingredient still
 * cycles through every item it accepts, modded ones included. Put the real thing in a grid and it
 * still crafts. This is only about which entries are lit.
 */
public final class RecipeBookContents {

    private RecipeBookContents() {
    }

    /**
     * A recipe-book display is a dispatched protocol value, not an item/block carrier. Changing its
     * type number cannot convert its payload. A cooking-pot display labelled as vanilla shapeless
     * left its extra fields in the stream, which were then read as category IDs (1533 on this pack).
     * Only send displays whose actual codec remains selected for this recipient. This also preserves
     * genuinely native/shared custom displays; merely having a mod's channel is not enough.
     * The real recipe and its machine/container menu are never changed by this book-only check.
     */
    public static boolean displayIsReadable(RecipeDisplayEntry entry, PacketContext context) {
        var type = entry.display().type();
        var synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.RECIPE_DISPLAY, type);
        return synced == null || synced.getPolymerReplacement(type, context) == type;
    }

    /**
     * Whether this recipe is worth putting in this player's book.
     */
    public static boolean worthShowing(ServerPlayer player, ResourceKey<Recipe<?>> recipe) {
        if (!ConfigManager.config().entities.hideRecipesForMissingMods) {
            return true;
        }

        Identifier id = recipe.identifier();
        String namespace = id.getNamespace();
        if (namespace.equals(Identifier.DEFAULT_NAMESPACE) || !PolymerPatcher.PATCHED_MODS.contains(namespace)) {
            return true;
        }

        return NativeClients.has(player, namespace);
    }

    /**
     * Whether the server judges this entry for the client. Inventory carriers can collide with
     * ingredients even when every ingredient in the recipe is vanilla.
     * <p>
     * A client lights a recipe by counting its own inventory against the ingredients, and for a player
     * without the mods both sides of that sum are stand-ins - several modded items share one vanilla look,
     * so two guano count as whatever else wears the same one, and a recipe lit up that the table then
     * refused. Cutting the modded items out of the sum only traded that for recipes that could never
     * light at all. So the server, which has the real items, does the sum, and hands the client an answer
     * it cannot get wrong: no requirements at all when the recipe can be made (which a client counts as
     * satisfied), and none offered when it cannot (which it counts as not). {@link #tick} keeps the
     * answer current as the inventory changes.
     */
    static boolean judgedByServer(boolean enabled, RecipeDisplayEntry entry) {
        // Looking only at the recipe's ingredients misses the other half of the collision: an unrelated
        // modded stack in the inventory can use the SAME carrier as an entirely vanilla ingredient.
        // NativeClients.has also does not imply raw items were synchronized for this connection.
        // Judge all known requirements using real server items, including purely vanilla recipes.
        return enabled && entry.craftingRequirements().isPresent();
    }

    public static RecipeDisplayEntry asTheyCanJudgeIt(ServerPlayer player, ResourceKey<Recipe<?>> recipe, RecipeDisplayEntry entry) {
        if (!judgedByServer(ConfigManager.config().entities.onlyCountItemsClientsCanTellApart, entry)) {
            return entry;
        }
        StackedItemContents contents = contentsOf(player);
        boolean craftable = entry.canCraft(contents);
        JUDGED.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>())
            .put(entry.id(), new Judged(recipe, entry, craftable));
        return answer(entry, craftable);
    }

    static RecipeDisplayEntry answer(RecipeDisplayEntry entry, boolean craftable) {
        return withRequirements(entry, craftable ? Optional.of(List.of()) : Optional.empty());
    }

    /** What the player could craft from: everything they carry, and whatever is in the grid in front of them. */
    private static StackedItemContents contentsOf(ServerPlayer player) {
        StackedItemContents contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        if (player.containerMenu instanceof RecipeBookMenu menu) {
            menu.fillCraftSlotsStackedContents(contents);
        }
        return contents;
    }

    private static final class Judged {
        final ResourceKey<Recipe<?>> recipe;
        final RecipeDisplayEntry entry;
        boolean craftable;

        Judged(ResourceKey<Recipe<?>> recipe, RecipeDisplayEntry entry, boolean craftable) {
            this.recipe = recipe;
            this.entry = entry;
            this.craftable = craftable;
        }
    }

    private static final Map<UUID, Map<RecipeDisplayId, Judged>> JUDGED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_INVENTORY = new ConcurrentHashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 10 != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    tick(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not update {}'s recipe book", player.getGameProfile().name(), e);
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            JUDGED.remove(handler.getPlayer().getUUID());
            LAST_INVENTORY.remove(handler.getPlayer().getUUID());
        });
    }

    /** Re-judges the player's recipes when what they could craft from has changed, and sends what flipped. */
    private static void tick(ServerPlayer player) {
        Map<RecipeDisplayId, Judged> judged = JUDGED.get(player.getUUID());
        if (judged == null || judged.isEmpty()) {
            return;
        }
        int fingerprint = fingerprint(player);
        Integer last = LAST_INVENTORY.put(player.getUUID(), fingerprint);
        if (last != null && last == fingerprint) {
            return;
        }

        StackedItemContents contents = contentsOf(player);
        List<ClientboundRecipeBookAddPacket.Entry> changed = new ArrayList<>();
        for (var iterator = judged.values().iterator(); iterator.hasNext(); ) {
            Judged one = iterator.next();
            if (!player.getRecipeBook().contains(one.recipe)) {
                iterator.remove();
                continue;
            }
            boolean craftable = one.entry.canCraft(contents);
            if (craftable != one.craftable) {
                one.craftable = craftable;
                changed.add(new ClientboundRecipeBookAddPacket.Entry(answer(one.entry, craftable), (byte) 0));
            }
        }
        if (!changed.isEmpty()) {
            player.connection.send(new ClientboundRecipeBookAddPacket(changed, false));
        }
    }

    private static int fingerprint(ServerPlayer player) {
        int hash = System.identityHashCode(player.containerMenu);
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            hash = hash * 31 + (stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()) * 17 + stack.getCount());
        }
        if (player.containerMenu instanceof RecipeBookMenu) {
            for (var slot : player.containerMenu.slots) {
                ItemStack stack = slot.getItem();
                hash = hash * 31 + (stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()) * 17 + stack.getCount());
            }
        }
        return hash;
    }

    private static RecipeDisplayEntry withRequirements(RecipeDisplayEntry entry, Optional<List<Ingredient>> requirements) {
        return new RecipeDisplayEntry(entry.id(), entry.display(), entry.group(), entry.category(), requirements);
    }
}
