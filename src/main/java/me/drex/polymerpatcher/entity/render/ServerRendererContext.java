package me.drex.polymerpatcher.entity.render;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.model.EquipmentAssetManager;

public class ServerRendererContext extends EntityRendererProvider.Context {

    public ServerRendererContext(ServerEntityRenderDispatcher renderDispatcher, ServerItemModelResolver modelResolver, ServerEntityModelSet entityModelSet) {
        super(
            renderDispatcher,
            // A mob that carries a block - a moobloom and its flower - asks this for the model to draw.
            // Left empty it was not ignored but fatal: the renderer reaches for it while working out
            // its render state and throws, losing the whole mob rather than just the flower. Something
            // that answers and does nothing keeps the mob
            new ServerBlockModelResolver(),
            modelResolver,
            null,
            null,
            entityModelSet,
            // The constructor builds equipment layers around this manager. A null manager lets the
            // renderer construct successfully but crashes later when a mob has wings or equipment.
            // An empty manager truthfully renders no client-only equipment and lets the base model
            // continue instead of losing the whole entity.
            new EquipmentAssetManager(),
            null,
            null,
            null
        );
    }
}
