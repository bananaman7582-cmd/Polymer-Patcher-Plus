package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.PolymerPatcher;

import java.util.List;

/**
 * Loads the game classes this mod patches while the server is still starting.
 * <p>
 * A patch is woven into a class the first time that class is loaded, and a patch that no longer fits its
 * class throws at that moment. For most of them that moment is startup and the mistake is obvious. For the
 * ones that matter most here it is not: the class that hands a joining client the world's data is first
 * loaded by the first player to join, and the one that reads their swings by the first player to swing. A
 * patch that had slipped would take the player it was loaded for with it, one after another, while the
 * server itself looked perfectly healthy.
 * <p>
 * Loading them up front moves that to a line in the log before anybody is connected, which is where a
 * mistake like that belongs.
 */
public final class PatchSelfCheck {

    private PatchSelfCheck() {
    }

    /**
     * Classes this mod patches that are otherwise first loaded by a player doing something.
     */
    private static final List<String> LOADED_BY_A_PLAYER = List.of(
        "net.minecraft.server.network.config.SynchronizeRegistriesTask",
        "net.minecraft.server.network.ServerGamePacketListenerImpl",
        "net.minecraft.world.entity.projectile.arrow.AbstractArrow"
    );

    public static void run() {
        for (String name : LOADED_BY_A_PLAYER) {
            try {
                Class.forName(name, true, PatchSelfCheck.class.getClassLoader());
            } catch (Throwable e) {
                // Deliberately loud: the patch is not going to work, and the alternative to saying so now is
                // finding out when somebody tries to play
                PolymerPatcher.LOGGER.error("The patch for {} did not take. Anything that depends on it will not work, "
                    + "and a player may be disconnected the first time the game reaches it.", name, e);
            }
        }
    }
}
