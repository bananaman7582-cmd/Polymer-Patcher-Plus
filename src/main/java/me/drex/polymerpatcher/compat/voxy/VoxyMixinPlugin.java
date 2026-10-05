package me.drex.polymerpatcher.compat.voxy;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Loads the distant terrain mixins only for the server-side Voxy mods actually installed. See
 * {@link me.drex.polymerpatcher.util.DistantTerrain}.
 */
public final class VoxyMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (FabricLoader.getInstance().isModLoaded("voxyworldgenv2")) {
            mixins.add("VoxyWorldGenNetworkMixin");
        }
        // Voxy Server Side is LOD Server Support under another name; both use the id lss
        if (FabricLoader.getInstance().isModLoaded("lss")) {
            mixins.add("LssIdentityTablesMixin");
            mixins.add("LssRegistryFingerprintMixin");
        }
        return mixins;
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
