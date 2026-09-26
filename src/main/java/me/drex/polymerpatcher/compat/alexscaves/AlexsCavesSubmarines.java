package me.drex.polymerpatcher.compat.alexscaves;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Alex's Caves-specific decisions needed while translating the submarine renderer. */
public final class AlexsCavesSubmarines {

    private static final Identifier SUBMARINE = Identifier.fromNamespaceAndPath("alexscaves", "submarine");

    private AlexsCavesSubmarines() {
    }

    /**
     * Whether this draw is the submarine's invisible water-mask volume rather than visible geometry.
     * <p>
     * The native renderer draws one large box through a no-texture shader. It writes only to the water
     * mask so water is not rendered across the cockpit; it is not a glass or hull texture. A vanilla
     * client has no equivalent shader pass. Treating the box as an ordinary Citadel part gave it the
     * submarine's fallback texture instead, surrounding a passenger's camera with solid orange walls
     * and covering the open windows from outside.
     */
    public static boolean isInvisibleWaterMask(Entity entity, @Nullable RenderType renderType) {
        if (renderType == null || !SUBMARINE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
            return false;
        }

        // ACRenderTypes names the RenderType "submarine_mask". Matching its public diagnostic name
        // avoids linking this otherwise generic server mod against Alex's Caves' client-only class.
        return renderType.toString().toLowerCase(Locale.ROOT).contains("submarine_mask");
    }

    /**
     * The submarine overrides positionRider rather than getPassengerRidingPosition, so the generic
     * vehicle query still returns its origin. Mirror its actual cockpit formula here and feed that
     * offset to the invisible vanilla ride carrier.
     */
    public static @Nullable Vec3 rideOffset(Entity vehicle, Entity passenger) {
        if (!SUBMARINE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()))) {
            return null;
        }

        float pitchFactor = -vehicle.getXRot() / 40.0F;
        Vec3 cockpit = new Vec3(0.0, -0.2, 0.8 + pitchFactor)
            .xRot((float) Math.toRadians(vehicle.getXRot()))
            .yRot((float) Math.toRadians(-vehicle.getYRot()));
        return new Vec3(cockpit.x, vehicle.getBbHeight() * 0.5 + cockpit.y, cockpit.z);
    }

}
