package me.drex.polymerpatcher.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import me.drex.polymerpatcher.entity.citadel.CitadelDraw;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a mod's item renderer what it does with an item, by running it and watching.
 * <p>
 * A mod that draws its own items does the work in Java: turn it over, move it a block up, make it smaller,
 * bend it further the longer it is held. None of that is written down anywhere a server can read, so until
 * now it was read by hand out of the mod's bytecode, one item at a time, and written into a table - fifteen
 * items' worth, every one of them able to go quietly wrong when the mod is updated.
 * <p>
 * It does not have to be read. The renderer is right there and will say what it does if it is run: given a
 * pose stack that starts from nothing and a buffer that keeps nothing, the pose it has built by the moment
 * it goes to draw the model <em>is</em> the answer, exactly, for that item in that context. That is the same
 * trick the entity side has used all along - see {@link CitadelDraw} - pointed at items instead of mobs.
 * <p>
 * Nothing here knows any item. What it knows is where one mod keeps its item renderer, and even that is a
 * line in a table rather than anything in the logic.
 */
public final class HeldItemProbe {

    private HeldItemProbe() {
    }

    /** Light at its brightest, the way a client draws an item in a hand. */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * Whether a renderer is being run right now to see what it does.
     * <p>
     * Read by the stand-in game client, which is only handed out while this mod is deliberately
     * running somebody else's drawing code - and this is one of those moments. The renderers reach
     * for the client immediately: Alex's Caves asks it for the world on the first line of the method
     * and for the partial tick on the second, and without a stand-in neither item ever gets past them.
     */
    public static boolean probing() {
        return PROBING.get();
    }

    private static final ThreadLocal<Boolean> PROBING = ThreadLocal.withInitial(() -> false);

    /**
     * What a renderer drew, and the pose it had built by the time it drew it.
     *
     * @param progress how far through a motion the model was told it was, at the furthest - one meaning
     *                 the motion is finished, and nought meaning nothing was said
     */
    public record Drawn(Object model, Matrix4f pose, float progress) {
    }

    /** Where a mod keeps the renderer that draws its own items. */
    private static final Map<String, String> RENDERERS = new ConcurrentHashMap<>();

    /** The interface that mod's renderer wants to be handed for its buffers. */
    private static final Map<String, String> BUFFER_SOURCES = new ConcurrentHashMap<>();

    /** Registers where one mod exposes its custom item renderer and buffer interface. */
    public static void registerRenderer(String namespace, String rendererClass, String bufferSourceClass) {
        RENDERERS.put(namespace, rendererClass);
        BUFFER_SOURCES.put(namespace, bufferSourceClass);
        BUILT.remove(namespace);
    }

    /** Built once per mod; a renderer is stateless enough to be asked about every item in turn. */
    private static final Map<String, Object> BUILT = new ConcurrentHashMap<>();

    /** Stands for "this mod has no renderer this can run", since a map will not hold null. */
    private static final Object NONE = new Object();

    /**
     * Whether anything can be asked of this mod at all, which is worth knowing before building a stack.
     */
    public static boolean canProbe(String namespace) {
        return rendererFor(namespace) != null && CitadelDraw.canCatch();
    }

    /**
     * The things about the item itself that its renderer read while drawing it.
     * <p>
     * Whatever moves the item in the hand is in here, along with whatever else the renderer happened to
     * look at. Which of them is the motion is not decided here - that is decided by setting each one in
     * turn and seeing which one moves the model, in {@link #drawnWith}.
     */
    public static Set<String> dataKeys(String namespace, ItemStack stack, ItemDisplayContext context) {
        // Given an item carrying nothing, a mod that keeps its own numbers on items does not look for
        // them at all - it sees there is nothing there and stops, and asks for no key this could hear.
        // An empty holder is enough to make it ask, and reads the same nought either way
        ItemStack carrying = stack.copy();
        carrying.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return ItemDataWatch.around(() -> probe(namespace, carrying, context));
    }

    /**
     * The item as its renderer draws it with one of its own numbers set to a value - which, for the
     * number that carries a motion, is that motion this many ticks in.
     */
    public static @Nullable Drawn drawnWith(String namespace, ItemStack stack, ItemDisplayContext context,
                                            String key, int value) {
        ItemStack held = stack.copy();
        CompoundTag tag = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(key, value);
        held.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return probe(namespace, held, context);
    }

    /**
     * What this item's own renderer does to the pose before it draws, in this context - or null where it
     * cannot be asked, draws nothing, or throws on the way.
     */
    public static @Nullable Drawn probe(String namespace, ItemStack stack, ItemDisplayContext context) {
        Object renderer = rendererFor(namespace);
        if (renderer == null || !CitadelDraw.canCatch()) {
            return null;
        }

        Object buffers = buffersFor(namespace);
        if (buffers == null) {
            return null;
        }

        Object[] caught = {null, null};
        CitadelDraw.Sink previous = CitadelDraw.ACTIVE.get();
        CitadelDraw.ACTIVE.set((model, poseStack) -> {
            // The first one it draws is the item itself; anything after is a part of the scene around it,
            // an arrow on a string or a second pass over the same shape
            if (caught[0] == null) {
                caught[0] = model;
                caught[1] = new Matrix4f(poseStack.last().pose());
            }
        });

        PROBING.set(true);
        CitadelDraw.forgetProgress();
        try {
            RENDER.get(namespace).invoke(renderer, stack, context, new PoseStack(), buffers,
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } catch (Throwable e) {
            // A renderer may still reach for something a server does not have, stand-in or no stand-in.
            // Said once per mod, with the item that hit it: everything else keeps whatever was written
            // down for it by hand, which is exactly where it was before any of this
            report(namespace, stack, e);
            return null;
        } finally {
            PROBING.set(false);
            CitadelDraw.forgetBuffers();
            if (previous == null) {
                CitadelDraw.ACTIVE.remove();
            } else {
                CitadelDraw.ACTIVE.set(previous);
            }
        }

        return caught[0] == null ? null : new Drawn(caught[0], (Matrix4f) caught[1], CitadelDraw.progressSeen());
    }

    private static final Map<String, Method> RENDER = new ConcurrentHashMap<>();

    private static @Nullable Object rendererFor(String namespace) {
        Object renderer = BUILT.computeIfAbsent(namespace, mod -> {
            String className = RENDERERS.get(mod);
            if (className == null) {
                return NONE;
            }

            try {
                Class<?> type = ClientOnlyClasses.loadQuietly(className);
                Class<?> buffers = type == null ? null : ClientOnlyClasses.loadQuietly(BUFFER_SOURCES.get(mod));
                if (type == null || buffers == null) {
                    return NONE;
                }

                Method render = type.getMethod("renderByItem", ItemStack.class, ItemDisplayContext.class,
                    PoseStack.class, buffers, int.class, int.class);
                Object built = type.getConstructor().newInstance();
                RENDER.put(mod, render);
                return built;
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not build {}'s item renderer to ask what it draws", mod, e);
                return NONE;
            }
        });

        return renderer == NONE ? null : renderer;
    }

    /**
     * Something shaped like the buffers the renderer expects, that keeps nothing.
     * <p>
     * The drawing itself is not wanted and never happens: the patch that catches a model being drawn
     * cancels it. This exists only so the renderer has something to hand its vertices to on the way there.
     */
    private static @Nullable Object buffersFor(String namespace) {
        Object buffers = BUFFERS.computeIfAbsent(namespace, mod -> {
            String className = BUFFER_SOURCES.get(mod);
            Class<?> type = className == null ? null : ClientOnlyClasses.loadQuietly(className);
            if (type == null) {
                return NONE;
            }

            VertexConsumer nowhere = (VertexConsumer) Proxy.newProxyInstance(
                HeldItemProbe.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, THROWS_NOTHING);
            return Proxy.newProxyInstance(HeldItemProbe.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getReturnType() == VertexConsumer.class ? nowhere : defaultFor(method));
        });

        return buffers == NONE ? null : buffers;
    }

    private static final Map<String, Object> BUFFERS = new ConcurrentHashMap<>();

    /** A vertex consumer that takes everything and keeps none of it. */
    private static final InvocationHandler THROWS_NOTHING = (proxy, method, args) ->
        method.getReturnType().isInstance(proxy) ? proxy : defaultFor(method);

    private static @Nullable Object defaultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == void.class) {
            return null;
        }
        return null;
    }

    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static void report(String namespace, ItemStack stack, Throwable e) {
        if (REPORTED.add(namespace)) {
            PolymerPatcher.LOGGER.info("{}'s item renderer will not run here ({} on {}); its items keep "
                + "the transforms written down for them", namespace, e.getClass().getSimpleName(),
                stack.getItem());
            PolymerPatcher.LOGGER.debug("Could not run {}'s item renderer", namespace, e);
        }
    }
}
