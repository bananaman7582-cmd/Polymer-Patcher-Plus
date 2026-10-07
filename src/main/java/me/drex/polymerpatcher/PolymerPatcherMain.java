package me.drex.polymerpatcher;

import me.drex.polymerpatcher.compat.subtlyd.SubtlydCompatibility;
import net.fabricmc.api.ModInitializer;

/**
 * Main entrypoint. Fabric's dedicated-server entrypoint runs after the built-in registries are
 * frozen; the main one runs in the same phase in which mods - Subtly Dungeons included - register
 * their own content into those registries, which is the only phase in which a late registration can
 * still land. See {@link SubtlydCompatibility}.
 */
public class PolymerPatcherMain implements ModInitializer {
    @Override
    public void onInitialize() {
        SubtlydCompatibility.init();
    }
}