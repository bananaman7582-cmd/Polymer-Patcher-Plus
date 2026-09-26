package me.drex.polymerpatcher.mixin.citadel;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets the submarine be drawn on a server, where there is no client to ask.
 * <p>
 * Its renderer begins by reading {@code Minecraft.getInstance().player} - which client code may always do,
 * and which on a dedicated server throws before a single vertex is placed. The renderer is run here to see
 * what it draws, so that one line ended the run, and every submarine in the world was invisible.
 * <p>
 * Everything after it is arithmetic a server can do perfectly well: the pose, the hull, the lights. The
 * player is used for one thing only - skipping the hull for whoever is sitting inside it in first person -
 * and no player matches no passenger, which is the right answer here, since nothing drawn this way is
 * drawn for the person inside.
 * <p>
 * Both patches are written against the game's own types rather than the mod's, so this compiles without
 * the mod present and is left out entirely when it is not installed.
 */
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.render.entity.SubmarineRenderer", remap = false)
public abstract class AlexsCavesSubmarineMixin {

    @WrapOperation(
        method = "renderSubmarine",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;",
            opcode = Opcodes.GETFIELD
        ),
        require = 0
    )
    private static @Nullable LocalPlayer polymer_patcher$noOneIsSittingInIt(
        @Nullable Minecraft client, Operation<LocalPlayer> original
    ) {
        return client == null ? null : original.call(client);
    }

    /**
     * The same question asked another way, in the method that decides whether the hull is hidden. Nobody
     * is riding anything from here, so nobody is riding it in first person either.
     */
    @WrapOperation(
        method = "isFirstPersonFloodlightsMode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;isPassengerOfSameVehicle(Lnet/minecraft/world/entity/Entity;)Z"
        ),
        require = 0
    )
    private static boolean polymer_patcher$nobodyIsRidingIt(
        @Nullable Entity camera, Entity vehicle, Operation<Boolean> original
    ) {
        return camera != null && original.call(camera, vehicle);
    }

    @WrapOperation(
        method = "isFirstPersonFloodlightsMode",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;"),
        require = 0
    )
    private static @Nullable Entity polymer_patcher$noCamera(@Nullable Minecraft client, Operation<Entity> original) {
        return client == null ? null : original.call(client);
    }
}
