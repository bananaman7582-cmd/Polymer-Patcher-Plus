package me.drex.polymerpatcher.mixin.witherstorm;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Block clusters serialize their map with a private raw-id codec, not Minecraft's block-state codec.
 * The same per-recipient mapping used for terrain must run here: a real leaf state may have been
 * borrowed as another block's carrier, and server-local numbers need not mean the same client state.
 * Only the wire id changes; never replace the cluster's stored states (it can place those back).
 */
@Pseudo
@Mixin(targets = "nonamecrackers2.witherstormmod.common.serializer.WitherStormModDataSerializers")
public abstract class ClusterBlockStateSerializerMixin {
    // Match the invocation, not javac's synthetic lambda number, which changes with unrelated edits.
    @Redirect(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/Block;getId(Lnet/minecraft/world/level/block/state/BlockState;)I"),
        require = 1, allow = 1)
    private static int polymerPatcher$clusterBlockForRecipient(BlockState state) {
        PacketContext context = PacketContext.get();
        // This codec is shared with the client. No network recipient means local serialization, where
        // changing the real state would be wrong. Native clients still need mapping: they may lack other
        // server mods, and the server pack still reassigns vanilla leaf states as carriers.
        return Block.getId(context == null ? state : PolymerBlockUtils.getPolymerBlockState(state, context));
    }
}
