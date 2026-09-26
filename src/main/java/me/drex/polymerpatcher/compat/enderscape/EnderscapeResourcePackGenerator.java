package me.drex.polymerpatcher.compat.enderscape;

import com.mojang.math.Transformation;
import eu.pb4.polymer.resourcepack.api.PackResource;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.CompositeItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.ConditionItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.EmptyItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.ItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.SelectItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.SpecialItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.bool.CustomModelDataFlagProperty;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.select.ComponentSelectProperty;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.select.CustomModelDataStringProperty;
import eu.pb4.polymer.resourcepack.extras.api.format.item.special.EndCubeSpecialModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.penumbra.enderscape.Enderscape;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/** Adds the Enderscape resources whose client-side predicates have no vanilla equivalent. */
final class EnderscapeResourcePackGenerator {
    private EnderscapeResourcePackGenerator() {
    }

    static void setup() {
        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(EnderscapeResourcePackGenerator::build);
    }

    private static void build(ResourcePackBuilder builder) {
        installItemModelConverter(builder);

        // The active End Haven core combines its ordinary shell with the vanilla end-gateway special
        // renderer, reproducing the animated centre without an Enderscape client.
        builder.addData("assets/enderscape/items/-/block/end_haven_core_active.json",
            new ItemAsset(new CompositeItemModel(List.of(
                new SpecialItemModel(Enderscape.id("block/end_haven_core_active"),
                    new EndCubeSpecialModel(EndCubeSpecialModel.Type.GATEWAY),
                    Optional.of(new Transformation(new Matrix4f().translate(0.05f, 0.05f, 0.05f).scale(0.9f)))),
                new BasicItemModel(Enderscape.id("block/end_haven_core_active"))
            ))));
    }

    private static void installItemModelConverter(ResourcePackBuilder builder) {
        builder.addResourceConverter((path, resource) -> {
            if (path.equals("assets/enderscape/items/magnia_attractor.json")) {
                ItemAsset asset = ItemAsset.fromJson(resource.asString());
                ItemModel.Replacer[] replacer = {null};
                replacer[0] = (parent, model) -> {
                    if (model instanceof ConditionItemModel condition
                        && condition.property() instanceof EnabledBooleanProperty) {
                        return new ConditionItemModel(new CustomModelDataFlagProperty(0),
                            replacer[0].modifyDeep(model, condition.onTrue()),
                            replacer[0].modifyDeep(model, condition.onFalse()));
                    }
                    return model;
                };
                return PackResource.fromAsset(new ItemAsset(
                    replacer[0].modifyDeep(EmptyItemModel.INSTANCE, asset.model()), asset.properties()));
            }

            if (path.equals("assets/enderscape/items/mirror.json")) {
                ItemAsset asset = ItemAsset.fromJson(resource.asString());
                ItemModel.Replacer[] replacer = {null};
                replacer[0] = (parent, model) -> {
                    if (model instanceof SelectItemModel<?, ?> select
                        && select.switchValue().property() instanceof EnderscapeDyeColorProperty) {
                        @SuppressWarnings("unchecked")
                        SelectItemModel<?, DyeColor> typed = (SelectItemModel<?, DyeColor>) select;
                        return new SelectItemModel<>(new SelectItemModel.Switch<>(
                            new ComponentSelectProperty<>(DataComponents.DYE),
                            typed.switchValue().cases().stream()
                                .map(entry -> new SelectItemModel.Case<>(entry.values(),
                                    replacer[0].modifyDeep(model, entry.model())))
                                .toList()),
                            typed.fallback().map(value -> replacer[0].modifyDeep(model, value)),
                            typed.transformation());
                    }
                    return model;
                };
                return PackResource.fromAsset(new ItemAsset(
                    replacer[0].modifyDeep(EmptyItemModel.INSTANCE, asset.model()), asset.properties()));
            }

            if (path.equals("assets/enderscape/items/rubble_shield.json")) {
                ItemAsset asset = ItemAsset.fromJson(resource.asString());
                ItemModel.Replacer[] replacer = {null};
                replacer[0] = (parent, model) -> {
                    if (model instanceof SelectItemModel<?, ?> select
                        && select.switchValue().property() instanceof EnderscapeRubbleShieldVariantProperty) {
                        @SuppressWarnings("unchecked")
                        SelectItemModel<?, Identifier> typed = (SelectItemModel<?, Identifier>) select;
                        return new SelectItemModel<>(new SelectItemModel.Switch<>(
                            new CustomModelDataStringProperty(0),
                            typed.switchValue().cases().stream()
                                .map(entry -> new SelectItemModel.Case<>(
                                    entry.values().stream().map(Identifier::toString).toList(),
                                    replacer[0].modifyDeep(model, entry.model())))
                                .toList()),
                            typed.fallback().map(value -> replacer[0].modifyDeep(model, value)),
                            typed.transformation());
                    }
                    return model;
                };
                return PackResource.fromAsset(new ItemAsset(
                    replacer[0].modifyDeep(EmptyItemModel.INSTANCE, asset.model()), asset.properties()));
            }

            return resource;
        });
    }
}
