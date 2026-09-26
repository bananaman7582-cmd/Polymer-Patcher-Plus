package me.drex.polymerpatcher.compat.enderscape;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.select.SelectProperty;
import net.minecraft.resources.Identifier;

/** Decoder for Enderscape's rubble-shield variant item-model predicate. */
public record EnderscapeRubbleShieldVariantProperty() implements SelectProperty<Identifier> {
    public static final Type<EnderscapeRubbleShieldVariantProperty, Identifier> TYPE =
        new Type<>(MapCodec.unit(EnderscapeRubbleShieldVariantProperty::new), Identifier.CODEC);

    @Override
    public Type<? extends SelectProperty<Identifier>, Identifier> type() {
        return TYPE;
    }
}
