package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.item.StackIdentitySanitizers;

import java.util.List;

/** Volatile Alex's Caves renderer timers which are not part of an item's visible identity. */
public final class AlexsCavesStackIdentity {
    private static boolean initialized;

    private AlexsCavesStackIdentity() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        StackIdentitySanitizers.register((stack, id, data) -> {
            if (!id.getNamespace().equals("alexscaves")) {
                return;
            }
            List<String> volatileFields = switch (id.getPath()) {
                case "shot_gum" -> List.of("PrevShootTime", "ShootTime", "PrevCrankAngle",
                    "CrankAngle", "Shooting", "Gumballs");
                case "raygun" -> List.of("PrevUseTime", "UseTime", "ChargeUsed", "PrevRayX",
                    "PrevRayY", "PrevRayZ", "RayX", "RayY", "RayZ");
                default -> List.of();
            };
            volatileFields.forEach(data::remove);
        });
    }
}
