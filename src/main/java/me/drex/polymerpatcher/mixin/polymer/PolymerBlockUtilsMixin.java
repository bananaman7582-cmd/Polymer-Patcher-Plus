package me.drex.polymerpatcher.mixin.polymer;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps every block state written to a client inside the pristine Minecraft 26.2 state mapper.
 *
 * <p>Polymer correctly asks overlays for their carrier, but a carrier pool is built from the running
 * server registry. Mods can append states to vanilla blocks, so a state can look like a vanilla carrier
 * and still have a raw id which does not exist on the client. Chunk palettes write that id directly and
 * the client disconnects while decoding the chunk. Sanitizing the final answer here covers chunks,
 * single-block updates and every other Polymer state codec through the same central path.</p>
 */
@Mixin(value = PolymerBlockUtils.class, remap = false)
public abstract class PolymerBlockUtilsMixin {

    /**
     * A real block goes in as the state a client would have it in, before Polymer looks at it.
     * <p>
     * See {@link BlockSyncCheck#asClientTwin}: a property only this server has (The Sift's ichorlogged)
     * gives every block a twin Polymer never took as a carrier and so never keeps clear of one. Only
     * while writing to a client - with no packet context the caller wants the real state back.
     */
    @ModifyVariable(method = "getPolymerBlockState", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static BlockState polymerPatcher$asTheClientWouldHaveIt(BlockState state,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) @Nullable PacketContext context) {
        return context == null ? state : BlockSyncCheck.asClientTwin(state);
    }

    @ModifyReturnValue(method = "getPolymerBlockState", at = @At("RETURN"))
    private static BlockState polymerPatcher$onlyReturnClientReadableState(
        BlockState state, BlockState serverState, @Nullable PacketContext context
    ) {
        // With no packet context callers expect a semantic server state. During encoding the only thing
        // subsequently observed is its raw id, which must be the pristine client's id for this semantic
        // block/properties combination.
        return context == null ? state : BlockSyncCheck.clientReadableWorldState(state);
    }
}
