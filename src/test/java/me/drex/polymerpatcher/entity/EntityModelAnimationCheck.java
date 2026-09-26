package me.drex.polymerpatcher.entity;

import me.drex.polymerpatcher.entity.geckolib.GeckoLibModel;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** Executable checks for the shared Java-model stride and GeckoLib renderer bridge. */
public final class EntityModelAnimationCheck {
    private EntityModelAnimationCheck() {
    }

    public static void main(String[] args) {
        ServerRenderStates.Walk walk = new ServerRenderStates.Walk();
        walk.observe(0, 0, 0);
        // A distant entity may be posed only once after several game ticks. Its stride must still
        // include each tick's motion, rather than just one capped sample of the whole interval.
        walk.observe(0.2, 0, 0);
        walk.observe(0.4, 0, 0);
        walk.observe(0.6, 0, 0);
        LivingEntityRenderState state = new LivingEntityRenderState();
        walk.apply(state);
        near("tick speed", state.walkAnimationSpeed, 0.8F);
        near("accumulated stride", state.walkAnimationPos, 2.4F);

        // A renderer that already supplied animation data must keep it.
        state.walkAnimationSpeed = 0.3F;
        state.walkAnimationPos = 17.0F;
        walk.apply(state);
        near("renderer speed", state.walkAnimationSpeed, 0.3F);
        near("renderer stride", state.walkAnimationPos, 17.0F);

        walk.observe(100, 0, 0);
        LivingEntityRenderState afterTeleport = new LivingEntityRenderState();
        walk.apply(afterTeleport);
        near("teleport speed cap", afterTeleport.walkAnimationSpeed, 1.0F);

        TestRenderer renderer = new TestRenderer();
        equal("specific renderer state", GeckoLibModel.createRenderState(renderer, "mob", 0.5F), "mob:0.5");
        equal("inherited renderer state", GeckoLibModel.createRenderState(renderer, 12, 0.25F), "base:0.25");
        ((BaseRenderer) renderer).scaleWidth = 1.25F;
        near("cached dynamic scale", GeckoLibModel.scale(renderer)[0], 1.25F);
        ((BaseRenderer) renderer).scaleWidth = 1.5F;
        near("updated dynamic scale", GeckoLibModel.scale(renderer)[0], 1.5F);
        System.out.println("Entity model animation checks passed");
    }

    private static void near(String name, float actual, float expected) {
        if (Math.abs(actual - expected) > 0.0001F) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void equal(String name, Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static class BaseRenderer {
        private float scaleWidth = 1.0F;
        private float scaleHeight = 1.0F;

        private Object createRenderState(Object entity, float partialTick) {
            return "base:" + partialTick;
        }
    }

    private static final class TestRenderer extends BaseRenderer {
        private Object createRenderState(String entity, float partialTick) {
            return entity + ":" + partialTick;
        }
    }
}
