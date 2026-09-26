package me.drex.polymerpatcher.compat.enderscape;

import net.fabricmc.loader.api.FabricLoader;

/** Safe facade that does not resolve Enderscape classes when the optional mod is absent. */
public final class EnderscapeCompatibility {
    private static final boolean ACTIVE = FabricLoader.getInstance().isModLoaded("enderscape")
        && !FabricLoader.getInstance().isModLoaded("enderscape-polymer-patch");

    private EnderscapeCompatibility() {
    }

    public static boolean isActive() {
        return ACTIVE;
    }

    public static void init() {
        if (ACTIVE) {
            EnderscapeIntegration.init();
        }
    }

}
