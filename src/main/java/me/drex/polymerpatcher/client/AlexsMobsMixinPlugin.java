package me.drex.polymerpatcher.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Offers the patches that only mean anything when Alex's Mobs is installed.
 * <p>
 * The names are handed over here rather than listed in the config, because the classes they target
 * only exist when that mod is present - naming one in a config that is loaded regardless would have
 * Mixin looking for a class that is not there.
 */
public class AlexsMobsMixinPlugin implements IMixinConfigPlugin {

    private static final String MOD = "alexsmobs";

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
        if (!FabricLoader.getInstance().isModLoaded(MOD)) {
            return List.of();
        }

        LogUtils.getLogger().info("Letting skreechers be heard by sculk sensors; turn entities.skreechersTriggerSculk off to undo it");
        return List.of("EntitySkreecherMixin");
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // The injection landing is a separate question from the class attaching, and the two failing
        // quietly for different reasons is what made the GeckoLib clock so slow to pin down
        LogUtils.getLogger().info("Attached the skreecher patch to {}", targetClassName);
    }
}
