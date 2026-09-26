package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Finds the models Alex's Caves draws its armour with, which nothing else can see.
 * <p>
 * Ordinary armour is a picture laid over the player's own body and needs no model at all - that is what
 * {@link me.drex.polymerpatcher.resources.EquipmentFallbacks} restores. Some of this mod's armour is not
 * ordinary: the cloak of darkness has a cape and two tails, the hazmat suit a breathing mask, and those
 * are real geometry hanging off a model of its own. A flat picture makes them visible but shapeless.
 * <p>
 * The models are built the way the mod builds them - each class carries a static
 * {@code createArmorLayer} that returns the shape, exactly so a client can bake it - and are handed to
 * {@link me.drex.polymerpatcher.entity.armor.ArmorModels}, which already knows how to wear a humanoid
 * model on a player who cannot draw it themselves.
 * <p>
 * The mod does not register these through anything a server can enumerate, so they are found by name: the
 * first word of the equipment file the item asks for is the first word of the model class. That is the
 * mod's own convention, and a piece whose class is not there is left to the flat picture.
 */
public final class AlexsCavesArmorModels {

    private AlexsCavesArmorModels() {
    }

    private static final String NAMESPACE = "alexscaves";

    /** Where the mod keeps the armour models, and what it calls them. */
    private static final String MODEL_PACKAGE = "com.github.alexmodguy.alexscaves.client.model.layered.";
    private static final String MODEL_SUFFIX = "ArmorModel";

    /** The shape a client bakes for the outer layer; the mod passes the same when it builds its own. */
    private static final float NO_DEFORMATION = 0.0F;

    /**
     * Offers each piece of this mod's armour the model its own client would draw, where there is one.
     *
     * @param add how to register one: the item, a name for its shape, the shape, and its picture
     */
    public static void discover(BiConsumer<Piece, Shape> add) {
        Map<String, Shape> byMaterial = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null || !itemId.getNamespace().equals(NAMESPACE)) {
                continue;
            }

            Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
            if (equippable == null || equippable.assetId().isEmpty()) {
                continue;
            }

            String material = firstWord(equippable.assetId().get().identifier().getPath());
            if (!byMaterial.containsKey(material)) {
                Shape shape = shapeOf(material);
                byMaterial.put(material, shape);
                if (shape != null) {
                    found.add(material);
                }
            }

            Shape shape = byMaterial.get(material);
            if (shape != null) {
                add.accept(new Piece(item, material, equippable.assetId().get().identifier().getPath(), itemId.getPath(), equippable.slot()), shape);
            }
        }

        if (!found.isEmpty()) {
            PolymerPatcher.LOGGER.info("Built {} Alex's Caves armour model(s) that only its own client would draw: {}",
                found.size(), found);
        }
    }

    /**
     * One piece of armour and what it is made of, which is what decides both its shape and its picture.
     */
    public record Piece(Item item, String material, String asset, String itemPath, EquipmentSlot slot) {

        /**
         * The picture this piece wears.
         * <p>
         * Armour has been kept in two files since long before this mod: the first covers head, body and
         * feet, the second the legs, because leggings are drawn on a layer of their own. A material with
         * only one file uses it for everything.
         * <p>
         * What the files are called is the mod's business and it is not consistent - the equipment file
         * says {@code darkness} while the picture is {@code darkness_armor}, and {@code rainbounce} wears
         * {@code rainbounce_boots}. So the likely names are tried in turn, ending with the item's own,
         * which is what names a piece that is the only one of its material.
         */
        public Identifier texture(java.util.function.Predicate<Identifier> exists) {
            List<String> names = List.of(asset, asset + "_armor", material + "_armor", itemPath);

            if (slot == EquipmentSlot.LEGS) {
                for (String name : names) {
                    Identifier legs = file(name + "_1");
                    if (exists.test(legs)) {
                        return legs;
                    }
                }
            }

            for (String name : names) {
                for (String suffix : List.of("_0", "")) {
                    Identifier candidate = file(name + suffix);
                    if (exists.test(candidate)) {
                        return candidate;
                    }
                }
            }

            // Nothing matched; the caller reports it by the name it looked for first
            return file(asset + "_0");
        }

        private static Identifier file(String name) {
            return Identifier.fromNamespaceAndPath(NAMESPACE, "textures/armor/" + name + ".png");
        }

        /** A name for this shape, so the models written for it do not collide with another material's. */
        public ModelLayerLocation layer() {
            return new ModelLayerLocation(Identifier.fromNamespaceAndPath(NAMESPACE, material + "_armor"), "armor");
        }
    }

    /** The shape of one material: the baked parts, and the mod own model wearing them. */
    public record Shape(ModelPart root,  HumanoidModel<HumanoidRenderState> model) {
    }

    /**
     * Builds the mod own model for this material, or null where it has none.
     */
    private static  Shape shapeOf(String material) {
        String className = MODEL_PACKAGE + Character.toUpperCase(material.charAt(0))
            + material.substring(1).toLowerCase(Locale.ROOT) + MODEL_SUFFIX;

        // Client-only, so it has to be brought in by hand the way every renderer here is
        Class<?> type = me.drex.polymerpatcher.dump.ClientOnlyClasses.loadQuietly(className);
        if (type == null) {
            return null;
        }

        try {
            Method createArmorLayer = type.getMethod("createArmorLayer", CubeDeformation.class);
            Object definition = createArmorLayer.invoke(null, new CubeDeformation(NO_DEFORMATION));
            if (!(definition instanceof LayerDefinition layer)) {
                return null;
            }

            ModelPart root = layer.bakeRoot();

            // The mod's own model rather than a plain humanoid body: it is what knows where a cape, a pair
            // of tails or a breathing mask belongs. A plain body has no idea those parts exist and leaves
            // them wherever the model file happened to put them - which is a cloak hanging off the wearer's
            // side rather than down their back
            return new Shape(root, buildModel(type, root, material));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not build the Alex's Caves armour model for {}", material, e);
            return null;
        }
    }

    /**
     * The mod's own model wearing these parts, or null where it will not be built.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable HumanoidModel<HumanoidRenderState> buildModel(Class<?> type, ModelPart root, String material) {
        try {
            Object built = type.getConstructor(ModelPart.class).newInstance(root);
            if (built instanceof HumanoidModel<?> humanoid) {
                return (HumanoidModel<HumanoidRenderState>) humanoid;
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not build the Alex's Caves armour model class for {}; "
                + "its parts will be worn on a plain body instead", material, e);
        }
        return null;
    }

    private static String firstWord(String name) {
        int underscore = name.indexOf('_');
        return underscore <= 0 ? name : name.substring(0, underscore);
    }
}
