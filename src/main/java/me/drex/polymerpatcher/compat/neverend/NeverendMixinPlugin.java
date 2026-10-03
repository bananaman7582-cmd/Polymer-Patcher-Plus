package me.drex.polymerpatcher.compat.neverend;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Loads Neverend mixins only when Neverend itself is installed. */
public final class NeverendMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() {
        return FabricLoader.getInstance().isModLoaded(NeverendCompatibility.MOD_ID)
            ? List.of("PoolfishAnimationMixin", "GrandgousierAnimationMixin", "ServerPlayNetworkingMixin",
                "DivingSuitSprintMixin")
            : List.of();
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
