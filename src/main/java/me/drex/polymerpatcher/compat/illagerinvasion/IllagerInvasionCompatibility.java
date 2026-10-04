package me.drex.polymerpatcher.compat.illagerinvasion;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import me.drex.polymerpatcher.util.ModdedMenus;
import net.minecraft.resources.Identifier;

/** Vanilla-client bridge for Illager Invasion's slot-only Imbuing Table. */
public final class IllagerInvasionCompatibility {
    static final Identifier MENU_ID = Identifier.fromNamespaceAndPath("illagerinvasion", "imbuing");
    static final String MENU_CLASS = "fuzs.illagerinvasion.common.world.inventory.ImbuingMenu";
    private static boolean initialized;

    private IllagerInvasionCompatibility() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        RenderCaptureRules.registerAssets(ImbuingUiAssets::generate);
        ModdedMenus.registerReplacement((player, provider, menu, menuId) -> {
            if (!MENU_ID.equals(menuId) || !MENU_CLASS.equals(menu.getClass().getName())) {
                return false;
            }
            try {
                ImbuingUi ui = new ImbuingUi(player, menu, provider.getDisplayName());
                if (!ui.open()) {
                    return false;
                }
                PolymerPatcher.LOGGER.debug("Opened vanilla Imbuing Table UI for {}",
                    player.getGameProfile().name());
                return true;
            } catch (Throwable throwable) {
                PolymerPatcher.LOGGER.error("Could not open the vanilla Imbuing Table UI for {}",
                    player.getGameProfile().name(), throwable);
                return false;
            }
        });
    }
}
