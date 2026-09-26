package me.drex.polymerpatcher.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Loads the Borrowed Echo tracking hook only when its optional target mod exists. */
public final class BorrowedEchoMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

    @Override
    public List<String> getMixins() {
        if (!FabricLoader.getInstance().isModLoaded("borrowed_echo")) {
            return List.of();
        }
        LogUtils.getLogger().info("Borrowed Echo passive disguises will use native vanilla creatures; animated player forms will use baked real profile skins");
        return List.of("BorrowedEchoEntityMixin", "BorrowedEchoRendererMixin");
    }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
