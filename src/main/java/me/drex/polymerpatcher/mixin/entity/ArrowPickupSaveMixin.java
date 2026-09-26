package me.drex.polymerpatcher.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets an arrow that cannot be picked up be written down.
 * <p>
 * An arrow saves the item it would be picked up as, and the game refuses to write an empty stack: a count
 * has to be at least one and the item may not be air. A mod is free to give an arrow no such item - Alex's
 * Caves' dark arrow, fired by the dreadbow, has none - and every attempt to write one of those out failed.
 * <p>
 * That is not only a line in the log. The writing is not done once when the world is saved: anything that
 * asks what an entity's data looks like does it, and an advancement that tests the data of whatever a
 * player hits does it for every hit. A dreadbow volley is dozens of arrows hitting things dozens of times a
 * second, and each one built a report of its own failure and wrote it to disk. Eight and a half thousand of
 * them went by in a single evening, in bursts of a thousand a second, and the server stalled for seconds at
 * a time while it kept up with its own complaining.
 * <p>
 * Nothing is lost by leaving the field out. The reader already treats a missing item as "the arrow's usual
 * one", which is exactly what an arrow with no pickup item wants to say.
 */
@Mixin(AbstractArrow.class)
public class ArrowPickupSaveMixin {

    @WrapOperation(
        method = "addAdditionalSaveData",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/ValueOutput;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V")
    )
    private void polymerPatcher$leaveOutAnEmptyPickupItem(ValueOutput output, String key, Codec<Object> codec, Object value, Operation<Void> original) {
        if (value instanceof ItemStack stack && stack.isEmpty()) {
            return;
        }
        original.call(output, key, codec, value);
    }
}
