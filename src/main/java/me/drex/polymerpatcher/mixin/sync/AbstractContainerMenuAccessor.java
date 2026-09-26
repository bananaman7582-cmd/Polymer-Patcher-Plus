package me.drex.polymerpatcher.mixin.sync;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Lets the automatic UI safety check distinguish a plain slot grid from a stateful machine. */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {
    @Accessor("dataSlots")
    List<DataSlot> polymerPatcher$dataSlots();
}
