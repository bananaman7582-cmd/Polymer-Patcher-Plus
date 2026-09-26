package me.drex.polymerpatcher.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class PPMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        try {
            ClientClassLoader.init();
        } catch (IOException | NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            throw new RuntimeException("Failed to load client class", e);
        }

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
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /** The mod's own way of asking a render state which entity it came from, and the class that asks. */
    private static final String AC_STATE_ACCESS = "com/github/alexmodguy/alexscaves/client/render/compat/ACStateAccess";

    private static final String CAPTURE_MIXIN = "me.drex.polymerpatcher.mixin.client.EntityRenderStateCaptureMixin";

    /**
     * Says that a render state can answer Alex's Caves' question about which entity it came from.
     * <p>
     * The methods themselves are written in {@code EntityRenderStateCaptureMixin}; what is left is for the
     * class to admit to the interface they belong to, so that mod's {@code (ACStateAccess) state} succeeds.
     * That interface belongs to a mod this one is not built against and may not be installed at all, so it
     * is added here, by name, where it can be checked for first - rather than declared in the mixin, which
     * would mean compiling against it and failing without it.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!CAPTURE_MIXIN.equals(mixinClassName) || targetClass.interfaces.contains(AC_STATE_ACCESS)) {
            return;
        }

        try {
            Class.forName(AC_STATE_ACCESS.replace('/', '.'), false, PPMixinPlugin.class.getClassLoader());
        } catch (Throwable notInstalled) {
            // Nothing asks the question on this server, so nothing has to answer it
            return;
        }

        targetClass.interfaces.add(AC_STATE_ACCESS);
        LogUtils.getLogger().info("Render states will answer Alex's Caves when its renderers ask which entity they are drawing");
    }
}
