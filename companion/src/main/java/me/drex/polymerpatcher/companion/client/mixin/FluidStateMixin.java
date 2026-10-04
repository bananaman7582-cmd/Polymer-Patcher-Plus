package me.drex.polymerpatcher.companion.client.mixin;

import me.drex.polymerpatcher.companion.client.ProxyFluid;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Swimming in a modded fluid the server treats as water.
 * <p>
 * The server gives a client without the mod water movement inside a modded fluid, and expects every
 * player in it to move that way. A client with its own copy of the fluid has to as well, or it walks
 * along the bottom while the server has it floating, and the two keep correcting each other.
 * <p>
 * A fluid state answers tag questions through a default method it inherits, so it is given its own
 * answer here, which is the inherited one except for these fluids and the water tag.
 */
@Mixin(FluidState.class)
public abstract class FluidStateMixin {

    @Shadow
    public abstract Fluid getType();

    @Shadow
    public abstract Holder<Fluid> typeHolder();

    public boolean is(TagKey<Fluid> tag) {
        if (tag == FluidTags.WATER && getType() instanceof ProxyFluid fluid && fluid.swimsLikeWater()) {
            return true;
        }
        return typeHolder().is(tag);
    }
}
