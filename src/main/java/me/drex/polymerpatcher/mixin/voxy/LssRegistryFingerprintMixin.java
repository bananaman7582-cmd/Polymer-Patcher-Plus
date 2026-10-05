package me.drex.polymerpatcher.mixin.voxy;

import me.drex.polymerpatcher.util.DistantTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets Voxy Server Side know when the terrain it keeps on disk no longer matches what modded blocks are shown as.
 * <p>
 * It keeps the terrain it has served, with every block already named, and throws the lot away when this
 * server's blocks change - judged by a fingerprint of the block registry. Which carrier a modded block is
 * shown as can change between restarts without the registry changing at all, so that is added to the
 * fingerprint. The first start with this in place rebuilds the store once, which also clears out the terrain
 * kept from before, when modded blocks were sent by their own names.
 */
@Pseudo
@Mixin(targets = "dev.vox.lss.common.store.RegistryFingerprint", remap = false)
public abstract class LssRegistryFingerprintMixin {
    @ModifyVariable(method = {
        "of(Ljava/lang/Iterable;Ljava/lang/Iterable;)Ljava/lang/String;",
        "contentOf(Ljava/lang/Iterable;Ljava/lang/Iterable;)Ljava/lang/String;"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Iterable<String> polymerPatcher$withCarriers(Iterable<String> blockStateIdentities) {
        return DistantTerrain.withFingerprint(blockStateIdentities);
    }
}
