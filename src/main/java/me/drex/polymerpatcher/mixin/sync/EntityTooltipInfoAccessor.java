package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@link EntityTooltipInfoMixin} put a different entity on a tooltip once it has been built. */
@Mixin(HoverEvent.EntityTooltipInfo.class)
public interface EntityTooltipInfoAccessor {

    @Mutable
    @Accessor("type")
    void polymer_patcher$setType(EntityType<?> type);
}
