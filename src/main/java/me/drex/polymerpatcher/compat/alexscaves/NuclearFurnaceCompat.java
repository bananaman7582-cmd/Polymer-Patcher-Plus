package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.ModdedMenus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Vanilla-client entry point for Alex's Caves' Nuclear Furnace. */
public final class NuclearFurnaceCompat {

    static final String MENU_CLASS =
        "com.github.alexmodguy.alexscaves.server.inventory.NuclearFurnaceMenu";
    private NuclearFurnaceCompat() {
    }

    public static void init() {
        ModdedMenus.registerReplacement((player, provider, menu, menuId) ->
            openForVanillaClient(player, provider, menu));
    }

    /**
     * Opens a packet-safe vanilla menu around the already-created live Nuclear Furnace menu.
     *
     * @return {@code true} only when the caller must cancel the original menu-opening method
     */
    public static boolean openForVanillaClient(
        ServerPlayer player,
        MenuProvider provider,
        AbstractContainerMenu menu
    ) {
        if (!MENU_CLASS.equals(menu.getClass().getName())) {
            return false;
        }

        try {
            NuclearFurnaceUi ui = new NuclearFurnaceUi(player, menu, provider.getDisplayName());
            if (!ui.open()) {
                return false;
            }
            PolymerPatcher.LOGGER.debug("Opened vanilla Nuclear Furnace UI for {}",
                player.getGameProfile().name());
            return true;
        } catch (Throwable throwable) {
            // Falling through is safe: ModdedMenus will close the original unsupported screen at
            // RETURN and restore the inventory, rather than leaving a mismatched slot layout open.
            PolymerPatcher.LOGGER.error("Could not open the vanilla Nuclear Furnace UI for {}",
                player.getGameProfile().name(), throwable);
            return false;
        }
    }
}
