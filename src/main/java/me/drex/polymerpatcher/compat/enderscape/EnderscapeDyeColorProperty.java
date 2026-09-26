package me.drex.polymerpatcher.compat.enderscape;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.select.SelectProperty;
import net.minecraft.world.item.DyeColor;

/** Decoder for Enderscape's mirror colour item-model predicate. */
public record EnderscapeDyeColorProperty() implements SelectProperty<DyeColor> {
    public static final Type<EnderscapeDyeColorProperty, DyeColor> TYPE =
        new Type<>(MapCodec.unit(EnderscapeDyeColorProperty::new), DyeColor.CODEC);

    @Override
    public Type<? extends SelectProperty<DyeColor>, DyeColor> type() {
        return TYPE;
    }
}
