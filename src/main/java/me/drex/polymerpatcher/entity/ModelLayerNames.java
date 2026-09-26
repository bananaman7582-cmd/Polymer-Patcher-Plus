package me.drex.polymerpatcher.entity;

import net.minecraft.client.model.geom.ModelLayerLocation;

import java.util.Locale;

/**
 * Names the folder a model layer's parts are filed under.
 * <p>
 * A {@link ModelLayerLocation} is a pair - an identifier saying which model, and a string saying which
 * layer of it - and only the pair is unique. Filing by the string alone put every layer called "main"
 * in one place, and a mob with more than one of them overwrote its own parts: Variants &amp; Ventures'
 * zombies bake a body layer and a set of armour layers, all called "main", so whichever was written
 * last replaced the others part for part. The mobs came out wearing pieces of their own armour models
 * where their heads and arms should have been.
 * <p>
 * So the identifier goes into the name as well. The same mistake, made in the Citadel path by naming
 * models after their class, is what left the cave centipede without a head.
 */
public final class ModelLayerNames {

    private ModelLayerNames() {
    }

    /**
     * A path segment standing for this layer, safe to put in a model identifier.
     */
    public static String of(ModelLayerLocation modelLayer) {
        String name = modelLayer.model().getNamespace() + "_" + modelLayer.model().getPath() + "_" + modelLayer.layer();
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return cleaned.isEmpty() ? "layer" : cleaned;
    }
}
