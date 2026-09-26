package me.drex.polymerpatcher.compat.alexsmobs;

import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import me.drex.polymerpatcher.entity.FlatEntityModels;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Renderer facts unique to Alex's Mobs which the global capture engine cannot infer. */
final class AlexsMobsRenderRules {
    private static final Identifier VOID_PORTAL =
        Identifier.fromNamespaceAndPath("alexsmobs", "void_portal");
    private static boolean initialized;
    private static java.lang.reflect.Method getLifespan;
    private static java.lang.reflect.Method isShattered;

    private AlexsMobsRenderRules() {
    }

    static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        java.util.List<Identifier> portalFrames = new java.util.ArrayList<>();
        for (String directory : new String[] {"", "shattered/"}) {
            for (int frame = 0; frame < 10; frame++) {
                portalFrames.add(portalTexture(directory + "portal_grow_" + frame));
            }
            for (int frame = 0; frame < 3; frame++) {
                portalFrames.add(portalTexture(directory + "portal_idle_" + frame));
            }
        }
        FlatEntityModels.want(portalFrames);

        RenderCaptureRules.registerFlatTexture((entity, texture) -> {
            if (!VOID_PORTAL.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
                return texture;
            }

            boolean shattered = shattered(entity);
            String directory = shattered ? "shattered/" : "";
            int lifespan = lifespan(entity);
            // This is RenderVoidPortal's own frame selection. The opening is keyed to the first
            // twenty entity ticks; the same ten frames run backwards as lifespan counts down while
            // closing. Once open, the original three-frame idle cadence takes over.
            if (lifespan >= 0 && lifespan < 20) {
                return portalTexture(directory + "portal_grow_" +
                    net.minecraft.util.Mth.clamp((int) (lifespan * 0.5F), 0, 9));
            }
            if (entity.tickCount < 20) {
                return portalTexture(directory + "portal_grow_" +
                    net.minecraft.util.Mth.clamp((int) (entity.tickCount * 0.5F), 0, 9));
            }
            return portalTexture(directory + "portal_idle_" + Math.floorMod(entity.tickCount / 3, 3));
        });

        RenderCaptureRules.registerFlatTransform((entity, transform) -> {
            if (!VOID_PORTAL.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
                return;
            }

            // The source renderer draws -1..1 geometry and then scales it by two: four blocks across.
            // The global flat fallback is one block scaled to the entity hitbox instead, which is why
            // the portal appeared as a tiny postage stamp.
            float current = Math.max(entity.getDimensions(entity.getPose()).width(), 0.1F);
            transform.scale(4.0F / current);
        });
    }

    private static Identifier portalTexture(String frame) {
        return Identifier.fromNamespaceAndPath("alexsmobs", "entity/void_worm/portal/" + frame);
    }

    private static int lifespan(net.minecraft.world.entity.Entity entity) {
        try {
            if (getLifespan == null) {
                getLifespan = entity.getClass().getMethod("getLifespan");
            }
            return (Integer) getLifespan.invoke(entity);
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean shattered(net.minecraft.world.entity.Entity entity) {
        try {
            if (isShattered == null) {
                isShattered = entity.getClass().getMethod("isShattered");
            }
            return (Boolean) isShattered.invoke(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
