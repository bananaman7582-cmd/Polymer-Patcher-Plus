package me.drex.polymerpatcher.client;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the game client, for the moments a model insists on talking to one.
 * <p>
 * A third of Alex's Mobs' models - 45 of them - ask {@code Minecraft.getInstance()} for the partial
 * tick in the middle of {@code setupAnim}. On a server that call returns null and the model throws
 * before it has posed a single part, every tick, forever. The mob then sits in whatever pose it was
 * last left in: it never animates, never turns, and if it threw the very first time, it never appears
 * at all. That one null is behind most of what looks like several different bugs.
 * <p>
 * So an instance is made without running the constructor - which would try to open a window - and
 * handed out in its place. Nothing is initialised on it: what the models want is the delta tracker,
 * and that is answered separately with a tracker that reports no partial tick, which is the truthful
 * answer on a server that renders between whole ticks anyway.
 * <p>
 * <b>It is only ever handed out while this mod is posing a model.</b> Plenty of code, this mod's
 * dependencies included, uses a null {@code getInstance()} to mean "not on a client", and that has to
 * keep being true everywhere else.
 */
public final class ServerMinecraft {

    private static volatile @Nullable Minecraft instance;
    private static volatile boolean unavailable;

    /** Set while {@link #allocate()} is running, so anything it touches cannot ask for the client. */
    private static boolean allocating;

    private ServerMinecraft() {
    }

    /**
     * Whether a model is being posed right now, and so whether the stand-in should be visible.
     */
    public static boolean rendering() {
        return ServerSubmitNodeCollector.ACTIVE_ENTITY.get() != null
            || me.drex.polymerpatcher.item.HeldItemProbe.probing()
            || BUILDING.get();
    }

    /**
     * Whether a renderer is being built right now.
     * <p>
     * Posing a model is not the only moment a renderer wants the game client: several ask for it in
     * their <em>constructor</em>, to get hold of the thing that draws a held item or a mob riding
     * another. Alex's Caves has seven that do - the gingerbread man, the gummy bear, the licowitch, the
     * sea pig and the three deep ones - and each of them failed to build at all, which is a mob nobody
     * can see rather than a mob missing a detail.
     */
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);

    /** Building a renderer can fail in any way at all, which the caller is the one to handle. */
    @FunctionalInterface
    public interface Build<T> {
        T get() throws Throwable;
    }

    /**
     * Builds something that may ask for the game client while it is being built.
     */
    public static <T> T whileBuilding(Build<T> build) throws Throwable {
        boolean was = BUILDING.get();
        BUILDING.set(true);
        try {
            return build.get();
        } finally {
            BUILDING.set(was);
        }
    }

    /**
     * The stand-in if one has already been made, without making one. Used where the answer only
     * matters for telling our own object apart from a real client.
     */
    @Nullable
    public static Minecraft peek() {
        return instance;
    }

    /**
     * The stand-in, or null when one could not be made - in which case the models that need it fail
     * exactly as they did before, which is the behaviour this replaces rather than risks.
     */
    @Nullable
    public static Minecraft get() {
        Minecraft existing = instance;
        if (existing != null || unavailable) {
            return existing;
        }

        synchronized (ServerMinecraft.class) {
            // Re-entered from inside allocate itself: building the camera asks the game for its
            // client, which lands back here before there is one to hand out. The lock is the same
            // thread's, so it lets that call straight through - answering null is what stops it
            // recursing until the stack runs out, and the call already in progress still finishes
            if (allocating) {
                return null;
            }

            if (instance == null && !unavailable) {
                allocating = true;
                try {
                    instance = allocate();
                    PolymerPatcher.LOGGER.info("Made a stand-in game client, for models that ask for one while posing");
                } catch (Throwable t) {
                    unavailable = true;
                    PolymerPatcher.LOGGER.warn("Could not make a stand-in game client; models that ask for one will not render", t);
                } finally {
                    allocating = false;
                }
            }
        }

        return instance;
    }

    /**
     * Builds the object without calling any constructor, the way deserialisation does. Running
     * {@link Minecraft}'s own constructor on a server would try to open a window long before it got
     * anywhere useful.
     */
    /** The dispatcher the mod built, handed over so the stand-in can carry it. */
    private static volatile  net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher;

    /**
     * Hands over the render dispatcher, so a model that reaches through the client for one finds it.
     * <p>
     * Alex's Mobs looks up the in-hand renderer this way to draw what a mob is carrying.
     */
    public static void useDispatcher(net.minecraft.client.renderer.entity.EntityRenderDispatcher value) {
        dispatcher = value;
    }

    private static Minecraft allocate() throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Minecraft client = (Minecraft) allocateInstance.invoke(unsafe, Minecraft.class);

        // Some models reach past the delta tracker for the camera - Alex's Mobs does it while drawing
        // the shine on a few of its mobs - so a renderer holding a camera at the origin is put there
        // too. Where it is does not matter: whatever is computed from it is thrown away with the rest
        // of the vertex data
        GameRenderer renderer = (GameRenderer) allocateInstance.invoke(unsafe, GameRenderer.class);
        // Built without its constructor as well: Camera's asks the game for its client, which would
        // come straight back here while this very method is still running
        Camera camera = (Camera) allocateInstance.invoke(unsafe, Camera.class);
        fillCamera(unsafe, camera);
        put(unsafe, renderer, GameRenderer.class.getDeclaredField("mainCamera"), camera);
        put(unsafe, client, Minecraft.class.getDeclaredField("gameRenderer"), renderer);

        // A few entity renderers draw a block as an accessory. The Underminer, for example, asks the
        // client model manager for the block it is currently mining. The headless client has no
        // resource reload and therefore no real baked block models, but leaving the manager null
        // aborts the entire mob render after its interaction hitbox has already been sent. To a player
        // that is an invisible entity intercepting clicks meant for the block behind it.
        //
        // An empty block model is the honest server-side substitute: the held/breaking block is
        // omitted, while the mob itself continues through the renderer and remains visible.
        ModelManager modelManager = (ModelManager) allocateInstance.invoke(unsafe, ModelManager.class);
        BlockStateModel emptyBlockModel = new BlockStateModel() {
            @Override
            public void collectParts(net.minecraft.util.RandomSource random, List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts) {
            }

            @Override
            public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
                return null;
            }

            @Override
            public int materialFlags() {
                return 0;
            }
        };
        BlockStateModelSet blockModels = new BlockStateModelSet(Collections.emptyMap(), emptyBlockModel);
        put(unsafe, modelManager, ModelManager.class.getDeclaredField("blockStateModelSet"), blockModels);
        put(unsafe, client, Minecraft.class.getDeclaredField("modelManager"), modelManager);

        // Renderers do not all reach the item renderer through the entity dispatcher. Alex's Caves'
        // Desolate Dagger asks Minecraft for its ItemModelResolver directly and uses it to resolve the
        // daggerRenderStack before drawing it tinted. The stand-in used to leave this field null, so
        // that renderer stopped before ItemStackRenderState could hand the captured stack to the
        // display element. Give both entry points the same capture-only resolver.
        var itemModelResolver = new me.drex.polymerpatcher.entity.render.ServerItemModelResolver();
        put(unsafe, client, Minecraft.class.getDeclaredField("itemModelResolver"), itemModelResolver);

        // The dispatcher, and through it the renderer that draws a held item. Both are only reached by
        // way of the client, so a model asking for them found nothing and the item went undrawn
        var value = dispatcher;
        if (value != null) {
            try {
                put(unsafe, value, net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("itemInHandRenderer"),
                    new net.minecraft.client.renderer.ItemInHandRenderer(client, value, itemModelResolver));
                put(unsafe, client, Minecraft.class.getDeclaredField("entityRenderDispatcher"), value);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not give the stand-in client a render dispatcher", e);
            }
        }

        return client;
    }

    /**
     * Gives the camera the values its constructor would have given it.
     * <p>
     * Skipping the constructor leaves every one of these null, and a renderer that reads one gets a
     * null where it expects a number - Alex's Mobs asks the camera for its rotation while drawing the
     * shine on a mob, which failed that mob's whole model on every tick. What the values are does not
     * matter, only that they are there: everything computed from them is thrown away with the rest of
     * the vertex data. A field this does not know about is skipped rather than fatal, so a future
     * version renaming one costs the shine and nothing else.
     */
    private static void fillCamera(Object unsafe, Camera camera) {
        Map<String, Object> defaults = Map.of(
            "rotation", new Quaternionf(),
            "forwards", new Vector3f(0.0F, 0.0F, 1.0F),
            "panoramicForwards", new Vector3f(0.0F, 0.0F, 1.0F),
            "up", new Vector3f(0.0F, 1.0F, 0.0F),
            "left", new Vector3f(1.0F, 0.0F, 0.0F),
            "position", Vec3.ZERO,
            "blockPosition", new BlockPos.MutableBlockPos()
        );

        defaults.forEach((name, value) -> {
            try {
                put(unsafe, camera, Camera.class.getDeclaredField(name), value);
            } catch (Exception e) {
                PolymerPatcher.LOGGER.debug("Could not fill camera field {}", name, e);
            }
        });
    }

    /**
     * Writes a field the language would not let us write - these are final, and the objects holding
     * them were never constructed, so there is nothing to disturb by filling them in now.
     */
    private static void put(Object unsafe, Object target, Field field, Object value) throws Exception {
        Method offset = unsafe.getClass().getMethod("objectFieldOffset", Field.class);
        Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
        putObject.invoke(unsafe, target, offset.invoke(unsafe, field), value);
    }
}
