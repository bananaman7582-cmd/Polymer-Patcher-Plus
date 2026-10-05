package me.drex.polymerpatcher.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.IntSet;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.DistantTerrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Sends Voxy World Gen V2's distant terrain with the blocks each player is shown up close. See
 * {@link DistantTerrain}.
 * <p>
 * It writes each section's block palette with this server's own numbers, once per chunk, with no player in
 * mind - so Polymer, which swaps a modded block for its carrier as a section is written to a player, never
 * gets the chance. Here every modded block is swapped before the palette is written, and the palette is
 * written as if it were a chunk packet to the player it is for.
 */
@Pseudo
@Mixin(targets = "com.ethan.voxyworldgenv2.network.NetworkHandler", remap = false)
public abstract class VoxyWorldGenNetworkMixin {
    private static final String BUILD_SECTIONS =
        "Lcom/ethan/voxyworldgenv2/network/NetworkHandler;buildSections(Lnet/minecraft/world/level/chunk/LevelChunk;Lit/unimi/dsi/fastutil/ints/IntSet;)Ljava/util/List;";

    private static volatile Method polymerPatcher$buildSections;

    /** Every block palette it writes goes out with the modded blocks swapped first. Biome palettes pass by. */
    @WrapOperation(method = "buildSections", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer;write(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private static void polymerPatcher$writeAsShown(PalettedContainer<?> container, FriendlyByteBuf buf, Operation<Void> original) {
        original.call(DistantTerrain.shownAs(container, "Voxy World Gen V2"), buf);
    }

    /** One player asked: written for that player. */
    @WrapOperation(method = "sendLODData", at = @At(value = "INVOKE", target = BUILD_SECTIONS))
    private static List<?> polymerPatcher$buildForPlayer(LevelChunk chunk, IntSet onlySectionYs, Operation<List<?>> original,
                                                         @Local(argsOnly = true) ServerPlayer player) {
        return DistantTerrain.buildFor(player, () -> original.call(chunk, onlySectionYs));
    }

    /**
     * Sent to everyone in range: built once more for each of them, because what a palette's numbers mean is
     * decided per client - a player whose client has more blocks is written with wider numbers.
     */
    @WrapOperation(method = "broadcastLODData(Lnet/minecraft/world/level/chunk/LevelChunk;Lit/unimi/dsi/fastutil/ints/IntSet;)V",
        at = @At(value = "INVOKE",
            target = "Lcom/ethan/voxyworldgenv2/network/NetworkHandler;sendAsync(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/ChunkPos;ILjava/util/List;Ljava/util/List;)V"))
    private static void polymerPatcher$buildForEachPlayer(ResourceKey<?> dimension, ChunkPos pos, int minY, List<?> sections,
                                                          List<ServerPlayer> recipients, Operation<Void> original,
                                                          @Local(argsOnly = true) LevelChunk chunk,
                                                          @Local(argsOnly = true) IntSet onlySectionYs) {
        Method build = polymerPatcher$buildSections();
        if (!DistantTerrain.enabled() || build == null) {
            original.call(dimension, pos, minY, sections, recipients);
            return;
        }
        for (ServerPlayer player : recipients) {
            List<?> theirs;
            try {
                theirs = DistantTerrain.buildFor(player, () -> {
                    try {
                        return (List<?>) build.invoke(null, chunk, onlySectionYs);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not build Voxy World Gen V2's terrain for {}; sending it as it was",
                    player.getGameProfile().name(), e);
                theirs = sections;
            }
            if (theirs != null && !theirs.isEmpty()) {
                original.call(dimension, pos, minY, theirs, List.of(player));
            }
        }
    }

    private static Method polymerPatcher$buildSections() {
        Method method = polymerPatcher$buildSections;
        if (method == null) {
            try {
                method = Class.forName("com.ethan.voxyworldgenv2.network.NetworkHandler")
                    .getDeclaredMethod("buildSections", LevelChunk.class, IntSet.class);
                method.setAccessible(true);
                polymerPatcher$buildSections = method;
            } catch (Throwable e) {
                return null;
            }
        }
        return method;
    }
}
