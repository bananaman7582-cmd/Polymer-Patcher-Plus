package me.drex.polymerpatcher.mixin.sync;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps third-party registry data maps usable on the server without making their networking
 * extension a hard requirement for otherwise vanilla clients.
 *
 * <p>MultiLoaderDataExtensions copies NeoForge's data-map implementation onto Fabric. A map may
 * still have a network codec (and is therefore still synchronized to a client which supports the
 * extension), but setting {@code mandatorySync} makes its configuration task disconnect every
 * vanilla client before registry synchronization begins. Polymer clients cannot consume that
 * custom payload, so all such maps must be optional at the protocol boundary. The map itself,
 * codec, datapack loading, and server-side lookups are left untouched.</p>
 *
 * <p>The target is optional and normally arrives as a jar-in-jar dependency of another mod, hence
 * the string target and {@link Pseudo}. Applying this to the shared builder makes the fix global
 * instead of naming Illager Invasion's {@code imbuing_levels} map.</p>
 */
@Pseudo
@Mixin(targets = "net.neoforged.neoforge.registries.datamaps.DataMapType$Builder", remap = false)
public abstract class DataMapTypeBuilderMixin {
    @ModifyVariable(
        method = "synced(Lcom/mojang/serialization/Codec;Z)Lnet/neoforged/neoforge/registries/datamaps/DataMapType$Builder;",
        at = @At("HEAD"),
        argsOnly = true,
        index = 2,
        require = 0
    )
    private boolean polymerPatcher$makeClientDataMapOptional(boolean mandatory) {
        return false;
    }
}
