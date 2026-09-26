package me.drex.polymerpatcher.compat.enderscape;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.resourcepack.extras.api.format.item.property.bool.BooleanProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.penumbra.enderscape.item.ItemStackContext;
import net.penumbra.enderscape.item.component.Enabled;
import net.penumbra.enderscape.item.component.FueledTool;
import org.jetbrains.annotations.Nullable;

/** Decoder for Enderscape's client-only enabled item-model predicate. */
public record EnabledBooleanProperty() implements BooleanProperty {
    public static final MapCodec<EnabledBooleanProperty> MAP_CODEC = MapCodec.unit(new EnabledBooleanProperty());

    public static boolean test(ItemStack stack, @Nullable Level level, @Nullable LivingEntity living) {
        return Enabled.get(stack) && FueledTool.fuelExceedsCost(new ItemStackContext(stack, level, living));
    }

    @Override
    public MapCodec<? extends BooleanProperty> codec() {
        return CODEC;
    }
}
