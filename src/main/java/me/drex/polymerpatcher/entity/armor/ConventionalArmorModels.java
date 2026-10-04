package me.drex.polymerpatcher.entity.armor;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Armour that a mod draws from a model class of its own, one class per piece.
 * <p>
 * This is the second shape the problem takes. Alex's Caves keeps one model per material and is handled in
 * {@link me.drex.polymerpatcher.compat.alexscaves.AlexsCavesArmorModels}; Alex's Mobs keeps one per item -
 * {@code ModelMooseHeadgear}, {@code ModelSombrero}, {@code ModelSpikedTurtleShell} - and registers each
 * through Fabric's armour renderer, which is client code and therefore not there to be asked on a server.
 * Eleven of its twelve pieces were arriving as a flat picture on a player: antlers, a turtle shell, a pair
 * of wings and a hat, all painted onto a shirt.
 * <p>
 * Nothing here knows anything about either mod beyond where it keeps its classes and what it calls them.
 * A mod that follows the same convention - a {@code HumanoidModel} with a static layer definition and a
 * constructor taking the baked root - is added by putting its package in {@link #SOURCES}.
 */
public final class ConventionalArmorModels {

    private ConventionalArmorModels() {
    }

    /** A mod that keeps one armour model class per piece, and where to look for it. */
    public record Source(String namespace, String modelPackage, List<String> prefixes) {
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    /** Adds another mod which follows the one-model-class-per-item convention. */
    public static void registerSource(Source source) {
        SOURCES.add(source);
    }

    /** The shape a client bakes for an outer layer, which is what these models are built for. */
    private static final float NO_DEFORMATION = 0.0F;

    /** One piece of armour, and where its shape and its picture are to be found. */
    public record Piece(Item item, String itemPath, String namespace) {

        public Identifier texture() {
            return Identifier.fromNamespaceAndPath(namespace, "textures/armor/" + itemPath + ".png");
        }

        /** A name for this shape, so the models written for it do not collide with another piece's. */
        public ModelLayerLocation layer() {
            return new ModelLayerLocation(Identifier.fromNamespaceAndPath(namespace, itemPath + "_armor"), "armor");
        }
    }

    /** The shape of one piece: the baked parts, and the mod's own model wearing them. */
    public record Shape(ModelPart root, @Nullable HumanoidModel<HumanoidRenderState> model) {
    }

    /**
     * Offers each piece the model its own client would draw, where there is one.
     */
    public static void discover(BiConsumer<Piece, Shape> add) {
        List<String> found = new ArrayList<>();

        for (Source source : SOURCES) {
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
                if (itemId == null || !itemId.getNamespace().equals(source.namespace())) {
                    continue;
                }

                Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
                if (equippable == null || item.components().has(DataComponents.GLIDER)) {
                    continue;
                }

                Piece piece = new Piece(item, itemId.getPath(), source.namespace());
                // A picture that is not there would be written into the atlas pointing at nothing, which
                // costs every other sprite in that atlas rather than only this one
                if (ResourceHelper.getAsset(piece.texture().getNamespace(), piece.texture().getPath()) == null) {
                    continue;
                }

                Shape shape = shapeOf(source, itemId.getPath());
                if (shape != null) {
                    add.accept(piece, shape);
                    found.add(itemId.getPath());
                }
            }
        }

        if (!found.isEmpty()) {
            PolymerPatcher.LOGGER.info("Built {} armour model(s) kept one to a piece, which only their own client would draw: {}",
                found.size(), found);
        }
    }

    /**
     * Builds the mod's own model for this piece, or null where it has none.
     */
    private static @Nullable Shape shapeOf(Source source, String itemPath) {
        for (String className : classNames(source, itemPath)) {
            // Client-only, so it has to be brought in by hand the way every renderer here is
            Class<?> type = me.drex.polymerpatcher.dump.ClientOnlyClasses.loadQuietly(className);
            if (type == null || !HumanoidModel.class.isAssignableFrom(type)) {
                continue;
            }

            try {
                LayerDefinition layer = layerOf(type);
                if (layer == null) {
                    continue;
                }

                ModelPart root = layer.bakeRoot();
                return new Shape(root, buildModel(type, root, itemPath));
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not build the armour model {} for {}", className, itemPath, e);
            }
        }
        return null;
    }

    /**
     * The names this mod might have given the model for this piece.
     * <p>
     * The ordinary one is the piece's own name - {@code moose_headgear} is {@code ModelMooseHeadgear} - and
     * the last word on its own catches the one piece named after something else: the tarantula hawk elytra
     * is drawn by {@code ModelAMElytra}, because that is a model of an elytra rather than of a wasp.
     */
    private static List<String> classNames(Source source, String itemPath) {
        List<String> names = new ArrayList<>();
        for (String prefix : source.prefixes()) {
            names.add(source.modelPackage() + prefix + camel(itemPath));
            String lastWord = itemPath.substring(itemPath.lastIndexOf('_') + 1);
            if (!lastWord.equals(itemPath)) {
                names.add(source.modelPackage() + prefix + camel(lastWord));
            }
        }
        return names;
    }

    private static String camel(String path) {
        StringBuilder camel = new StringBuilder();
        for (String word : path.split("_")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return camel.toString();
    }

    /**
     * The layer definition this model class builds for itself, under whichever name it gives that method.
     */
    private static @Nullable LayerDefinition layerOf(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (!LayerDefinition.class.equals(method.getReturnType())
                || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            if (method.getParameterCount() == 0) {
                return (LayerDefinition) method.invoke(null);
            }
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == CubeDeformation.class) {
                return (LayerDefinition) method.invoke(null, new CubeDeformation(NO_DEFORMATION));
            }
        }
        return null;
    }

    /**
     * The mod's own model wearing these parts, or null where it will not be built - in which case the parts
     * are worn on a plain body, which puts anything that is not part of one wherever the model file left it.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable HumanoidModel<HumanoidRenderState> buildModel(Class<?> type, ModelPart root, String itemPath) {
        try {
            Object built = type.getConstructor(ModelPart.class).newInstance(root);
            if (built instanceof HumanoidModel<?> humanoid) {
                return (HumanoidModel<HumanoidRenderState>) humanoid;
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not build the armour model class for {}; "
                + "its parts will be worn on a plain body instead", itemPath, e);
        }
        return null;
    }
}
