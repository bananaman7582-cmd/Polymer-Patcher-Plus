package me.drex.polymerpatcher.compat.enderscape;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

/**
 * Stops Enderscape from extending a vanilla enum that cannot be extended on an unmodded client.
 * The replacement mixin maps Enderscape's two instruments to their closest vanilla counterparts.
 */
public final class EnderscapeMixinCanceller implements MixinCanceller {
    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("enderscape") || loader.isModLoaded("enderscape-polymer-patch")) {
            return false;
        }

        return mixinClassName.equals("net.penumbra.enderscape.mixin.block.NoteBlockInstrumentMixin")
            || mixinClassName.equals("net.penumbra.enderscape.client.mixin.MusicManagerMixin");
    }
}
