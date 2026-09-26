package me.drex.polymerpatcher.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Turns on the one GeckoLib mixin a dedicated server is never given.
 * <p>
 * GeckoLib keeps every render state's animation data - which bone is where, what pose it is in, which
 * way it is facing - in a map bolted onto {@code EntityRenderState} by {@code EntityRenderStateMixin}.
 * That mixin sits in GeckoLib's {@code client} section, and a dedicated server applies no client
 * mixins, so the class arrives without it. GeckoLib's own drawing code then asks a render state for
 * data it has no way to hold, and every GeckoLib mob fails before a single bone is posed. On a client
 * none of this shows, because there the mixin is applied.
 * <p>
 * So GeckoLib's own mixin - its class, unmodified, out of its own jar - is named again from a config
 * of ours, which has no client section for a server to skip. Nothing is reimplemented and nothing is
 * patched twice: a mixin already applied would be refused, and this is only reached because the first
 * pass skipped it.
 * <p>
 * The name is supplied here rather than listed in the config, because the class only exists when
 * GeckoLib is installed. This is also the only supported moment to decide: adding a configuration
 * while the server is starting mutates the set Mixin is walking at that very moment, which took the
 * whole server down with a {@code ConcurrentModificationException} before it had loaded a single mod.
 */
public class GeckoLibMixinPlugin implements IMixinConfigPlugin {

    /**
     * Only this one. GeckoLib's other client mixins hang armour layers and model callbacks off classes
     * a server has no use for, and each one applied is another thing that can fail at start-up rather
     * than in a single mob.
     */
    private static final String RENDER_STATE_MIXIN = "EntityRenderStateMixin";

    /** Our own patch to GeckoLib, which lives in a package of ours rather than one of theirs. */
    private static final String OUR_PACKAGE = "me.drex.polymerpatcher.mixin.geckolib";

    private String mixinPackage = "";

    /**
     * Mixin hands this the package with a trailing dot on it, which an exact comparison does not
     * survive. Getting that wrong sent this config down the other branch, where it named a mixin that
     * does not exist in it - and a config that is allowed to fail discards such a name without a word,
     * so the patch simply never applied and nothing anywhere said so.
     */
    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage == null ? "" : mixinPackage.replaceAll("\\.+$", "");
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
        if (!FabricLoader.getInstance().isModLoaded("geckolib")) {
            return List.of();
        }

        if (OUR_PACKAGE.equals(mixinPackage)) {
            LogUtils.getLogger().info("Patching GeckoLib's animation clock");
            if (canLoad("com.geckolib.network.GeckoLibNetworkingFabric")) {
                return List.of("ClientUtilMixin", "EntityAnimationTriggerMixin");
            }
            return List.of("ClientUtilMixin");
        }

        if (!canLoad("com.geckolib.mixin.client." + RENDER_STATE_MIXIN)) {
            // A GeckoLib that has moved or renamed it costs its mobs their models, not the server
            LogUtils.getLogger().warn("GeckoLib is installed but its render state mixin was not found; its entities will not render");
            return List.of();
        }

        return List.of(RENDER_STATE_MIXIN);
    }

    private static boolean canLoad(String className) {
        return GeckoLibMixinPlugin.class.getClassLoader().getResource(className.replace('.', '/') + ".class") != null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Confirms the class attached at all. The injection inside it landing is a separate question,
        // which the class itself answers at runtime - the two failing silently for different reasons is
        // exactly what made this so slow to pin down
        if (mixinClassName.endsWith("ClientUtilMixin")) {
            LogUtils.getLogger().info("Attached the animation-clock patch to {}", targetClassName);
        } else if (mixinClassName.endsWith("EntityAnimationTriggerMixin")) {
            LogUtils.getLogger().info("Attached the GeckoLib entity-animation trigger bridge to {}", targetClassName);
        }
    }
}
