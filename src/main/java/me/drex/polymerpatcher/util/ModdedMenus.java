package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shuts a screen a client has no code to draw, before it can do any harm.
 * <p>
 * Everything else this mod does rests on the same trick: a modded thing is shown to a vanilla client
 * as some vanilla thing wearing its texture. A screen cannot be done that way. What draws an inventory
 * is not a model or a texture but the mod's own client code, and a client that does not have the mod
 * does not have the code - there is nothing to dress up.
 * <p>
 * What happens instead is that the screen arrives as whatever vanilla menu the registry substituted
 * for it, which is the wrong shape. Alex's Caves' spelunkery table is nine slots of crafting station
 * with rules about what may go where; it turns up as a single row of nothing in particular. The slots
 * the client thinks it is clicking and the slots the server moves are then two different sets, and
 * what comes of that is not a screen that looks wrong but an inventory that scrambles itself - items
 * moved somewhere neither side agrees about.
 * <p>
 * So it is closed instead, and the player is told why. Not being able to use a station is a
 * disappointment; watching an inventory shuffle itself is a good deal worse, and harder to undo.
 */
public final class ModdedMenus {

    private ModdedMenus() {
    }

    /** Said once per mod per player, rather than every time they walk into the same block. */
    private static final Set<String> TOLD = new HashSet<>();
    private static final CopyOnWriteArrayList<ReplacementProvider> REPLACEMENTS = new CopyOnWriteArrayList<>();
    private static boolean initialized;

    @FunctionalInterface
    public interface ReplacementProvider {
        boolean open(ServerPlayer player, net.minecraft.world.MenuProvider provider,
                     AbstractContainerMenu menu, Identifier menuId);
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        me.drex.polymerpatcher.entity.render.RenderCaptureRules.registerAssets(AutomaticMenuUiAssets::generate);
    }

    /** Explicit, item- or mod-specific replacements run before conservative automatic detection. */
    public static void registerReplacement(ReplacementProvider provider) {
        REPLACEMENTS.add(provider);
    }

    /**
     * Replaces a mod menu before its type is sent to a client that cannot understand it.
     *
     * <p>Specific adapters win. The fallback accepts only menus whose complete live layout is already
     * representable by a vanilla 9-wide container; everything else is left for the safe close at RETURN.</p>
     */
    public static boolean openForVanillaClient(ServerPlayer player, net.minecraft.world.MenuProvider provider,
                                               AbstractContainerMenu menu) {
        Identifier id = menuId(menu);
        if (id == null || id.getNamespace().equals("minecraft") || !NativeClients.settled(player)
            || NativeClients.carries(player, id.getNamespace())) {
            return false;
        }

        for (ReplacementProvider replacement : REPLACEMENTS) {
            try {
                if (replacement.open(player, provider, menu, id)) {
                    return true;
                }
            } catch (Throwable throwable) {
                PolymerPatcher.LOGGER.error("Explicit vanilla UI replacement failed for {}", id, throwable);
            }
        }

        if (!me.drex.polymerpatcher.config.ConfigManager.config().entities.automaticModdedMenus) {
            return false;
        }
        try {
            var replacement = AutomaticMenuUi.create(player, menu, id, provider.getDisplayName());
            if (replacement.isPresent() && replacement.get().open()) {
                PolymerPatcher.LOGGER.info("Opened automatic vanilla UI for {} ({}) for {}",
                    id, menu.getClass().getName(), player.getGameProfile().name());
                return true;
            }
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.error("Automatic vanilla UI replacement failed for {}", id, throwable);
        }
        return false;
    }

    /**
     * Rejects an unsupported custom menu before Minecraft sends its open-screen or property packets.
     *
     * <p>Doing this at the end of {@code ServerPlayer.openMenu} is too late: {@code initMenu} has
     * already queued every custom {@code DataSlot}. A vanilla placeholder screen can have no matching
     * property indices and disconnect while processing those packets even if a close packet follows.</p>
     */
    public static boolean rejectBeforeOpen(ServerPlayer player, AbstractContainerMenu menu) {
        Identifier id = menuId(menu);
        if (id == null || id.getNamespace().equals("minecraft")) {
            return false;
        }

        // Nothing is known about this player yet, and refusing them a screen on a guess is its own
        // kind of wrong. A menu opened this early is rare enough to let through
        if (!NativeClients.settled(player)) {
            return false;
        }

        if (NativeClients.carries(player, id.getNamespace())) {
            // They have the mod; the screen is theirs to draw and this has no business here
            return false;
        }

        // The menu was constructed, so let it release any container listener/open count. It has not
        // become player.containerMenu yet and no client packet has been sent, so the existing inventory
        // remains authoritative and needs no repair packet.
        menu.removed(player);

        String mod = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(id.getNamespace())
            .map(container -> container.getMetadata().getName())
            .orElse(id.getNamespace());
        player.sendSystemMessage(Component.literal(
            "That needs " + mod + " installed to use. Nothing in your inventory was moved.")
            .withStyle(ChatFormatting.YELLOW));

        if (TOLD.add(player.getUUID() + "#" + id.getNamespace())) {
            PolymerPatcher.LOGGER.info("Declined {} for {} before sending it: it is a {} screen, which only that mod can draw",
                id, player.getGameProfile().name(), mod);
        }
        return true;
    }

    /** Forgotten on the way out, so a returning player is told again rather than never. */
    public static void forget(ServerPlayer player) {
        TOLD.removeIf(said -> said.startsWith(player.getUUID().toString() + "#"));
    }

    private static Identifier menuId(AbstractContainerMenu menu) {
        try {
            MenuType<?> type = menu.getType();
            return BuiltInRegistries.MENU.getKey(type);
        } catch (Throwable e) {
            // Some menus have no registered type at all and throw when asked; those are not ours to judge.
            return null;
        }
    }
}
