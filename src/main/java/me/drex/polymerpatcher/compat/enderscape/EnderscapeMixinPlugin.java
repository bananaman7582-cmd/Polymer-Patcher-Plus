package me.drex.polymerpatcher.compat.enderscape;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Loads Enderscape-specific patches only when they are useful and safe to apply. */
public final class EnderscapeMixinPlugin implements IMixinConfigPlugin {
    private static final String ENDERSCAPE = "enderscape";
    private static final String STANDALONE_PATCH = "enderscape-polymer-patch";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded(ENDERSCAPE) || loader.isModLoaded(STANDALONE_PATCH)) {
            return List.of();
        }

        LogUtils.getLogger().info("Enabling built-in Enderscape Polymer compatibility");
        return List.of(
            "EnderscapeNoteBlockInstrumentsMixin",
            "ServerPlayNetworkingMixin",
            "ServerCommonPacketListenerMixin",
            "ServerPlayerMixin",
            "DashJumpManagerMixin",
            "DriftJellyBlockMixin",
            "DrifterMixin",
            "LowGravityManagerMixin",
            "MagniaAffectedMixin",
            "RustleMixin",
            "VoidManagerMixin"
        );
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
