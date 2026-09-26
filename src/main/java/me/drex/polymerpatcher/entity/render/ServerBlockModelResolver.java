package me.drex.polymerpatcher.entity.render;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stands in for the thing that looks a block's model up, for renderers that carry one.
 * <p>
 * A mob holding a block asks this to fill in the model it should draw. On a server there is no model
 * manager behind it, so the slot was left empty - and a renderer that reaches for it does not fall
 * back, it throws. Friends&amp;Foes' moobloom does exactly that while working out its render state: it
 * wears a flower, asks this for the flower's model, and the whole mob is lost to the exception before
 * anything of it is drawn. That is why a moobloom was invisible rather than merely flowerless.
 * <p>
 * So the slot is filled with something that answers without doing anything. The render state is left
 * as it was, which is a state carrying no block - the mob itself draws normally, and what is missing
 * is the flower on its back rather than the cow underneath it.
 */
public class ServerBlockModelResolver extends BlockModelResolver {

    public ServerBlockModelResolver() {
        // The manager is only reached through the methods below, and neither of them reaches it
        super(null);
    }

    @Override
    public void update(BlockModelRenderState state, BlockState blockState, BlockDisplayContext context) {
    }

    @Override
    public void updateForItemFrame(BlockModelRenderState state, boolean one, boolean two) {
    }
}
