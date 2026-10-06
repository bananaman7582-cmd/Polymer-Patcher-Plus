package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.autohost.impl.AutoHost;
import me.drex.polymerpatcher.resources.SplitResourcePacks;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Stops Polymer adding the generated pack itself once its fragments have taken over.
 *
 * <p>Polymer assumes one pack: it adds that pack on its own, to the list the client is sent, and there
 * is no event for taking it away again. This is the whole of the compatibility layer, and it only ever
 * acts when {@link SplitResourcePacks} has a complete set of fragments to send in its place - so with
 * splitting off, with a pack small enough to send whole, or after a split that did not work, Polymer's
 * behaviour here is exactly what it has always been.</p>
 *
 * <p>The admin's own {@code external_resource_packs} used to be added just after the generated pack by
 * this same method, so they are added by {@link SplitResourcePacks} instead, in the same order they
 * were. They still sit below the generated content rather than above it, which is what they did before
 * and what a client resolving the two packs against each other expects.</p>
 *
 * @see SplitResourcePacks
 */
@Mixin(value = AutoHost.class, remap = false)
public abstract class SplitAutoHostMixin {

    @Inject(method = "sendRegularPacks", at = @At("HEAD"), cancellable = true)
    private void polymerPatcher$fragmentsStandInForTheMainPack(ResourcePackDataProvider provider,
                                                              PacketContext context,
                                                              Consumer<MinecraftServer.ServerResourcePackInfo> consumer,
                                                              CallbackInfo ci) {
        if (SplitResourcePacks.replacesMainPack()) {
            ci.cancel();
        }
    }
}
