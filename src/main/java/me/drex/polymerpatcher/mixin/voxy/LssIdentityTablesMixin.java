package me.drex.polymerpatcher.mixin.voxy;

import me.drex.polymerpatcher.util.DistantTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Names each modded block in Voxy Server Side's distant terrain as the block players are shown up close. See
 * {@link DistantTerrain}.
 * <p>
 * Every block it sends is named through this one lookup, from a number in this server's registry, whether
 * the terrain was just read from disk or built from a loaded chunk. Turning the number into the shown block's
 * number first means the name that goes out is one a client has. The reverse table is left alone, so every
 * name it ever wrote still reads back.
 */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.IdentityTables", remap = false)
public abstract class LssIdentityTablesMixin {
    @ModifyVariable(method = "blockIdentityFor(I)Ljava/lang/String;", at = @At("HEAD"), argsOnly = true)
    private static int polymerPatcher$nameAsShown(int globalId) {
        return DistantTerrain.shownAs(globalId);
    }
}
