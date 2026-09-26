package me.drex.polymerpatcher.client;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns on the Citadel capture for whichever mods actually carry a copy of it.
 * <p>
 * Citadel is shaded, so every mod that uses it carries its own copy under its own package. There is
 * no shared class to patch and no way to name them all in advance - each has to be named separately,
 * and naming one that is not installed is not a warning but a refusal that takes the whole config
 * with it. So the names are supplied here, once the class loader can be asked which of them exist.
 */
public class CitadelMixinPlugin implements IMixinConfigPlugin {

    /** Each mod's copy of Citadel's base model class, and the mixin that patches it. */
    private static final Map<String, String> COPIES = Map.of(
        "AlexsCavesModelMixin", "com.github.alexmodguy.alexscaves.citadel.client.model.basic.BasicEntityModel",
        "AlexsMobsModelMixin", "com.github.alexthe666.alexsmobs.citadel.client.model.basic.BasicEntityModel",
        "AlexsCavesBlockDrawMixin", "com.github.alexmodguy.alexscaves.client.ACClientCompat",
        "AlexsCavesBufferMixin", "com.github.alexmodguy.alexscaves.client.render.compat.ACSubmitBuffers",
        "AlexsCavesSubmarineMixin", "com.github.alexmodguy.alexscaves.client.render.entity.SubmarineRenderer",
        "AlexsCavesProgressMixin", "com.github.alexmodguy.alexscaves.client.render.item.ACItemstackRenderer"
    );

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
        List<String> enabled = new ArrayList<>();
        for (var copy : COPIES.entrySet()) {
            if (canLoad(copy.getValue())) {
                enabled.add(copy.getKey());
            }
        }

        if (!enabled.isEmpty()) {
            LogUtils.getLogger().info("Catching Citadel models as they are drawn, for {}", enabled);
            me.drex.polymerpatcher.entity.citadel.CitadelDraw.attached();
        }
        return enabled;
    }

    private static boolean canLoad(String className) {
        return CitadelMixinPlugin.class.getClassLoader().getResource(className.replace('.', '/') + ".class") != null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
