package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.drex.polymerpatcher.item.RecipeBookContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Decides what goes into a player's recipe book, for a player who has not got the server's mods.
 * <p>
 * See {@link RecipeBookContents} for why: a client decides what it can craft by counting its own
 * inventory against the ingredients, and for such a player both are stand-ins, so it decides it can
 * craft things it cannot. A mod's own recipes are dropped, and the rest are asked for only the part of
 * each ingredient the client can recognise.
 * <p>
 * Caught here, where the book is filled, rather than at the packet - the recipe's own name is still in
 * hand at this point, and by the time it is a packet it is only a number.
 */
@Mixin(ServerRecipeBook.class)
public class RecipeBookMixin {

    @WrapOperation(
        method = {"sendInitialRecipeBook", "addRecipes"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/stats/ServerRecipeBook$DisplayResolver;displaysForRecipe(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V"
        )
    )
    private void polymerPatcher$onlyWhatTheyCouldUse(ServerRecipeBook.DisplayResolver resolver,
                                                     ResourceKey<Recipe<?>> recipe,
                                                     Consumer<RecipeDisplayEntry> into,
                                                     Operation<Void> original,
                                                     @Local(argsOnly = true) ServerPlayer player) {
        if (!RecipeBookContents.worthShowing(player, recipe)) {
            return;
        }

        Consumer<RecipeDisplayEntry> judged = entry -> into.accept(RecipeBookContents.asTheyCanJudgeIt(player, recipe, entry));
        original.call(resolver, recipe, judged);
    }
}
