package me.drex.polymerpatcher.mixin.sync;

import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Turns the item on an advancement into one the player's game has heard of.
 * <p>
 * Every advancement carries an item to show beside it, and a modded one names an item a vanilla client
 * has no entry for. Everywhere else this mod hands such an item over as a vanilla stand-in wearing the
 * modded model, and the player sees the right thing - but an advancement's item does not travel as an
 * ordinary item stack. It is an {@code ItemStackTemplate}, a shape introduced in 26.2 that Polymer does
 * not translate, so this one item slipped past every conversion and reached the client naming something
 * it could not look up. The result is an advancement with no icon on it.
 * <p>
 * So it is converted here, in the one place it is written out. The item is unpacked into an ordinary
 * stack, put through the same conversion as every other item, and packed back up.
 * <p>
 * The title and description are written through the same method by the same call, so what arrives here
 * is checked rather than assumed - anything that is not an item is passed straight through, which also
 * means this keeps working if those three are ever written in a different order.
 */
@Mixin(DisplayInfo.class)
public class DisplayInfoMixin {

    @ModifyArg(
        method = "serializeToNetwork",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"),
        index = 1
    )
    private Object polymer_patcher$translateIcon(Object value, @Local(argsOnly = true) RegistryFriendlyByteBuf buf) {
        if (!(value instanceof ItemStackTemplate template)) {
            return value;
        }

        try {
            ItemStack stack = new ItemStack(template.item(), template.count(), template.components());
            ItemStack translated = PolymerItemUtils.getPolymerItemStack(stack, PacketContext.get(), buf.registryAccess());

            // An item that needed no translating is handed back as it was, rather than rebuilt
            return translated == stack || translated.isEmpty() ? value : ItemStackTemplate.fromStack(translated);
        } catch (Throwable e) {
            // An icon that will not translate is better sent as it was than not sent at all
            return value;
        }
    }
}
