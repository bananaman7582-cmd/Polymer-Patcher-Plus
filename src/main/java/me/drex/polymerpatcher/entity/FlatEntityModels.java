package me.drex.polymerpatcher.entity;

import eu.pb4.polymer.resourcepack.extras.api.format.atlas.AtlasAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelTransformation;
import com.mojang.math.Quadrant;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import eu.pb4.factorytools.api.util.LazyItemStack;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * A single flat square wearing an entity's picture, for the things that are drawn as exactly that.
 * <p>
 * Not everything a mod adds is a model. Alex's Caves' gumball is a renderer that builds four vertices by
 * hand and pushes them straight into a buffer: no model, no parts, no item, nothing any of the paths in
 * this mod watch for. It registers, it runs, it draws nothing anybody else can see, and a gumball flies
 * across the cavity as empty air.
 * <p>
 * What such a renderer does have is a picture - it says so itself, through {@code getTextureLocation} -
 * and a flat square facing the viewer is what it was drawing with it. So one is written for each of them
 * and used only where the renderer's own drawing came to nothing, which leaves everything that draws
 * properly exactly as it was.
 */
public final class FlatEntityModels {

    private FlatEntityModels() {
    }

    /**
     * The pictures a flat square is wanted for and the stack that wears each one.
     * <p>
     * The stack is asked for here, while the renderers are being set up, and not at the moment a gumball
     * first flies past. An item display shows an item, an item names a definition, and the definition for
     * one of these is only written into the pack for a model that was asked for before the pack was built -
     * so asking later got the square into the pack with nothing to point at it, and a gumball arrived as a
     * white square. Asking now gets both.
     */
    private static final Map<Identifier, LazyItemStack> WANTED = new ConcurrentHashMap<>();

    public static void want(@Nullable Collection<Identifier> textures) {
        if (textures == null) {
            return;
        }

        for (Identifier texture : textures) {
            WANTED.computeIfAbsent(sprite(texture), wanted -> ItemDisplayElementUtil.getModel(modelFor(wanted)));
        }
    }

    /** The stack that wears this picture as a flat square, or null where none was written. */
    public static @Nullable ItemStack stackFor(@Nullable Identifier texture) {
        LazyItemStack stack = texture == null ? null : WANTED.get(sprite(texture));
        return stack == null ? null : stack.get().copy();
    }

    /**
     * The same picture named the way a model file names one.
     * <p>
     * A renderer names a file - {@code textures/entity/gumball/gumball_3.png} - and everything on this
     * side of the mod names a sprite - {@code entity/gumball/gumball_3}. They are the same picture, and
     * one asked for by the wrong name is simply not found: a gumball comes in eleven colours, the
     * renderer was asked which one this was and answered in the first form, nothing matched, and every
     * gumball fell back on the one default picture. Which is why they were all the same colour.
     */
    private static Identifier sprite(Identifier texture) {
        String path = texture.getPath();
        if (path.startsWith(TEXTURES)) {
            path = path.substring(TEXTURES.length());
        }
        if (path.endsWith(PNG)) {
            path = path.substring(0, path.length() - PNG.length());
        }
        return path.equals(texture.getPath()) ? texture
            : Identifier.fromNamespaceAndPath(texture.getNamespace(), path);
    }

    private static final String TEXTURES = "textures/";
    private static final String PNG = ".png";

    /** The model written for this picture, whether or not one was. */
    public static Identifier modelFor(Identifier texture) {
        return PolymerPatcher.id("entity_flat/" + texture.getNamespace() + "/" + texture.getPath().replace('/', '_'));
    }

    /** Whether a flat square was written for this picture, by either of its names. */
    public static boolean has(@Nullable Identifier texture) {
        return texture != null && WANTED.containsKey(sprite(texture));
    }

    /**
     * Writes one square per picture: a plane through the middle of the block, drawn on both sides so it
     * is there from behind as well, at the size the game draws a whole block.
     */
    public static void generateAssets(BiConsumer<String, byte[]> writer, AtlasAsset.Builder atlas) {
        if (WANTED.isEmpty()) {
            return;
        }

        for (Identifier texture : WANTED.keySet()) {
            atlas.single(texture);

            ModelAsset.Builder model = ModelAsset.builder();
            model.texture("txt", texture);
            model.textureReference("particle", "txt");

            ModelElement.Builder element = ModelElement.builder(new Vec3(0, 0, 8), new Vec3(16, 16, 8));
            element.face(Direction.NORTH, 0, 0, 16, 16, "#txt", Direction.NORTH, Quadrant.R0, 0);
            element.face(Direction.SOUTH, 16, 0, 0, 16, "#txt", Direction.SOUTH, Quadrant.R0, 0);
            model.element(element.build());

            // The same transform the part models carry, so a square and a model of the same thing are
            // the same size in the world
            model.transformation(ItemDisplayContext.FIXED,
                new ModelTransformation(new Vec3(0, 180, 0), Vec3.ZERO, new Vec3(1, 1, 1)));

            Identifier modelId = modelFor(texture);
            writer.accept("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json",
                model.build().toBytes());

            // A display is handed an item, and an item finds its model only through a definition of its
            // own. Polymer writes those for a mod's own namespace and not for this one, so without this
            // there is a square in the pack that nothing points at - which is what a gumball was: there,
            // the right size, and blank white. The same trick the fluid surfaces needed; see FluidModels
            writer.accept("assets/" + modelId.getNamespace() + "/items/-/" + modelId.getPath() + ".json",
                new eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset(
                    new eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel(modelId,
                        java.util.List.of(new eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource(0xFFFFFF))),
                    eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset.Properties.DEFAULT).toBytes());
        }

        PolymerPatcher.LOGGER.info("Wrote {} flat square(s) for entities their own renderer draws as one",
            WANTED.size());
    }
}
