package me.drex.polymerpatcher.entity.geckolib;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads entity models built with GeckoLib, the other model system mods reach for instead of the
 * game's.
 * <p>
 * Where Citadel builds its models in Java and hands them to the renderer, GeckoLib keeps them in
 * {@code .geo.json} files and bakes them during the client's resource reload - a reload a dedicated
 * server never performs, so a GeckoLib mob on a server has a renderer, an animation controller and no
 * model at all. What it does have is the mod's own jar, and the file is in it. So the file is read,
 * handed to GeckoLib's own loader to bake, and put back into GeckoLib's cache under the id the
 * renderer will ask for - after which the rest of GeckoLib works as though a client had loaded it,
 * animation controllers included.
 * <p>
 * <b>Everything here is reflection, deliberately</b>, for the same reason the Citadel bridge is: the
 * mod is optional, must not be a build dependency, and the package it lives under is not guaranteed -
 * a mod that shades GeckoLib moves every class. So the root package is read off the renderer's own
 * interface and everything else is found relative to it.
 * <p>
 * A model that cannot be found, read or baked leaves the entity without one, which is the same as
 * before this existed: the mob draws as its vanilla stand-in.
 */
public final class GeckoLibModel {

    /** The interface every GeckoLib renderer implements, by name only, since the package can differ. */
    private static final String RENDERER_INTERFACE = "GeoRenderer";

    /** Where a bone's live pose lives while a render pass is running; null outside one. */
    private static final String SNAPSHOT_FIELD = "frameSnapshot";

    /**
     * GeckoLib bakes cube corners into blocks while the game's own models keep them in pixels, and the
     * generated item models are written in pixels at a quarter scale - the same quarter the Citadel
     * path applies - so the two conversions fold into one number.
     */
    static final float BLOCKS_TO_MODEL_UNITS = 16.0F * 0.25F;

    /** Where the middle of an item model sits, in its own units. */
    static final float MODEL_ORIGIN = 8.0F;

    /**
     * Where GeckoLib keeps its model files, relative to a namespace's assets, and what they are named.
     * More than one is tried because the layout changed between GeckoLib versions and mods carry files
     * written against whichever they were built for.
     */
    private static final String[] MODEL_PATHS = {
        "geckolib/models/%s.geo.json",
        "geckolib/models/%s.json",
        "geckolib/%s.geo.json",
        "geo/%s.geo.json",
        "geo/%s.json",
    };

    private static volatile Reflection reflection;
    private static volatile boolean reflectionFailed;

    /** Renderer classes are stable; searching their declared methods for every mob pose is not. */
    private static final ClassValue<List<Method>> RENDER_STATE_METHODS = new ClassValue<>() {
        @Override
        protected List<Method> computeValue(Class<?> rendererClass) {
            List<Method> methods = new ArrayList<>();
            for (Class<?> type = rendererClass; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (!method.getName().equals("createRenderState") || parameters.length != 2
                        || parameters[1] != float.class) continue;
                    methods.add(method);
                }
            }
            return List.copyOf(methods);
        }
    };

    private static final ClassValue<Field[]> SCALE_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> rendererClass) {
            for (Class<?> type = rendererClass; type != null && type != Object.class; type = type.getSuperclass()) {
                try {
                    Field width = type.getDeclaredField("scaleWidth");
                    Field height = type.getDeclaredField("scaleHeight");
                    if (width.trySetAccessible() && height.trySetAccessible()) {
                        return new Field[]{width, height};
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Another superclass may own the scale fields.
                }
            }
            return new Field[0];
        }
    };

    /** Model ids already baked and handed to GeckoLib, so a shared model is only read once. */
    private static final Map<Identifier, Boolean> BAKED = new HashMap<>();

    /** Models already given an empty animation file, so the stand-in is made once and not per tick. */
    private static final Map<Identifier, Boolean> ANIMATION_STAND_INS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether every mod's animation files have been handed to GeckoLib, so it only happens once. */
    private static volatile boolean animationsRegistered;

    /**
     * GeckoLib's stripPrefixAndSuffix, kept here so the ids its animation cache is keyed by can be
     * reproduced without another reflected method to break on. The two patterns are its own, exactly:
     * a leading {@code geckolib/} plus an optional {@code animations/} or {@code models/}, and a
     * trailing {@code .geo}, {@code .animation} or {@code .animations} plus {@code .json}.
     */
    private static final Pattern ANIMATION_PATH_PREFIX = Pattern.compile("^(geckolib/)((animations/)|(models/))?");
    private static final Pattern ANIMATION_PATH_SUFFIX = Pattern.compile("((\\.geo)|((\\.animation)s?))?(\\.json)$");

    private GeckoLibModel() {
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics: whether GeckoLib's animation pipeline is actually producing a moving pose
    // ---------------------------------------------------------------------------------------------

    /** Renderer classes whose pipeline has already been probed, so the probe runs once per type. */
    private static final Set<String> PIPELINE_PROBED = ConcurrentHashMap.newKeySet();

    /** Per renderer class: the aggregate pose hash of its previous frame, to spot any motion. */
    private static final Map<String, Long> LAST_POSE_HASH = new ConcurrentHashMap<>();

    /** Per renderer class: whether its pose has ever been seen to change since the first frame. */
    private static final Map<String, Boolean> POSE_SEEN_TO_MOVE = new ConcurrentHashMap<>();

    /** Per renderer class: the last wall-clock time a pose change was reported, to throttle the log. */
    private static final Map<String, Long> LAST_POSE_LOG = new ConcurrentHashMap<>();

    /**
     * Debug probe: prints, once per renderer class, whether GeckoLib's own state is carrying a
     * manager with registered controllers and a non-empty controller-state array. Both are what have
     * to be there for {@code applyAnimationControllers} to animate anything, and both are read through
     * GeckoLib the same way the render pass reads them, so a log line here is ground truth for why a
     * mob stands still.
     */
    public static void diagnosePipelineOnce(@NotNull Object renderer, @NotNull Object state) {
        String key = renderer.getClass().getName();
        if (!PIPELINE_PROBED.add(key)) {
            return;
        }

        try {
            Class<?> geoRenderState = null;
            for (Class<?> type = state.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) {
                    if (iface.getSimpleName().equals("GeoRenderState")) {
                        geoRenderState = iface;
                        break;
                    }
                }
                if (geoRenderState != null) {
                    break;
                }
            }
            if (geoRenderState == null) {
                PolymerPatcher.LOGGER.debug("[geckodiag] {}: state {} is not a GeoRenderState",
                    key, state.getClass().getName());
                return;
            }

            String name = geoRenderState.getName();
            String root = name.substring(0, name.length() - ".renderer.base.GeoRenderState".length());
            ClassLoader loader = geoRenderState.getClassLoader();
            Class<?> tickets = Class.forName(root + ".constant.DataTickets", false, loader);
            Class<?> dataTicket = Class.forName(root + ".constant.dataticket.DataTicket", false, loader);
            Method getGeckolibData = geoRenderState.getMethod("getGeckolibData", dataTicket);

            Object manager = getGeckolibData.invoke(state, tickets.getField("ANIMATABLE_MANAGER").get(null));
            Object states = getGeckolibData.invoke(state, tickets.getField("ANIMATION_CONTROLLER_STATES").get(null));

            long controllers = -1;
            if (manager != null) {
                Object map = manager.getClass().getMethod("getAnimationControllers").invoke(manager);
                controllers = map instanceof Map<?, ?> controllersMap ? controllersMap.size() : -2;
            }
            int statesLength = states == null ? -1 : Array.getLength(states);

            PolymerPatcher.LOGGER.debug("[geckodiag] {}: state={} manager={} controllers={} controllerStates={}",
                key,
                state.getClass().getSimpleName(),
                manager == null ? "null" : manager.getClass().getSimpleName(),
                controllers,
                statesLength);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("[geckodiag] {}: probe failed", key, e);
        }
    }

    /**
     * Debug probe: feeds the aggregate pose hash of one frame through, and reports the first time it
     * is seen to differ from the previous frame - the one thing that proves a moving animation - then
     * throttles further change reports to one per second per renderer class.
     */
    public static void notePose(String key, long poseHash) {
        Long last = LAST_POSE_HASH.put(key, poseHash);
        if (last == null) {
            PolymerPatcher.LOGGER.debug("[geckodiag] {}: pose frame 1 hash={}", key, poseHash);
            return;
        }
        if (last.equals(poseHash)) {
            return;
        }
        if (POSE_SEEN_TO_MOVE.putIfAbsent(key, Boolean.TRUE) == null) {
            PolymerPatcher.LOGGER.debug("[geckodiag] {}: pose CHANGED - animation is progressing", key);
        }
        long now = System.currentTimeMillis();
        Long lastLog = LAST_POSE_LOG.get(key);
        if (lastLog == null || now - lastLog > 1000) {
            LAST_POSE_LOG.put(key, now);
            PolymerPatcher.LOGGER.debug("[geckodiag] {}: pose still moving (hash={})", key, poseHash);
        }
    }

    /**
     * Whether this renderer draws its entity with GeckoLib.
     */
    public static boolean isGeoRenderer(@Nullable Object renderer) {
        return renderer != null && findGeoRendererInterface(renderer.getClass()) != null;
    }

    @Nullable
    private static Class<?> findGeoRendererInterface(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> candidate : current.getInterfaces()) {
                Class<?> found = matchInterface(candidate);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    @Nullable
    private static Class<?> matchInterface(Class<?> type) {
        if (type.getSimpleName().equals(RENDERER_INTERFACE)) {
            return type;
        }
        for (Class<?> parent : type.getInterfaces()) {
            Class<?> found = matchInterface(parent);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Everything reached by name, resolved once off the renderer's own class loader.
     * <p>
     * The root package is whatever sits above {@code renderer.base.GeoRenderer}, so a shaded copy is
     * found exactly where an unshaded one is.
     */
    private record Reflection(
        Method getGeoModel,
        Method getModelResource,
        Method getTextureResource,
        Method getTextureLocation,
        Method getBakedModel,
        Method topLevelBones,
        Method boneChildren,
        Field boneCubes,
        Field boneSnapshot,
        Method snapshotHidden,
        Method snapshotChildrenHidden,
        Method prepMatrixForBone,
        Method cubeQuads,
        Method cubeRotation,
        Method cubeTranslateToPivot,
        Method cubeRotate,
        Method cubeTranslateAwayFromPivot,
        Method quadVertices,
        Method quadDirection,
        Method quadNormal,
        Method vertexPosX,
        Method vertexPosY,
        Method vertexPosZ,
        Method vertexTexU,
        Method vertexTexV,
        Method renderPassCreate,
        Method renderPassCaptureModelPose,
        Method renderPassRenderPosed,
        Method rendererPreRenderPass,
        Method rendererScaleModel,
        Method rendererAdjustPose,
        Class<?> renderStateImpl,
        Class<?> cameraState,
        Method bakeModelFile,
        Object gsonLoader,
        Field bakedModelCacheField,
        Method bakedModelCacheMap,
        Field bakedAnimationCacheField,
        Method bakedAnimationCacheMap,
        Method bakeAnimationsFile,
        Method createMathParser
    ) {
    }

    /**
     * Resolved once and read from every bone of every GeckoLib mob on every tick, so the settled case
     * is a field read and the lock is only reached while it is still being worked out.
     */
    @Nullable
    private static Reflection reflection(@NotNull Object renderer) {
        Reflection resolved = reflection;
        return resolved != null || reflectionFailed ? resolved : resolve(renderer);
    }

    @Nullable
    private static synchronized Reflection resolve(@NotNull Object renderer) {
        if (reflection != null || reflectionFailed) {
            return reflection;
        }

        try {
            Class<?> rendererInterface = findGeoRendererInterface(renderer.getClass());
            String suffix = ".renderer.base." + RENDERER_INTERFACE;
            if (rendererInterface == null || !rendererInterface.getName().endsWith(suffix)) {
                reflectionFailed = true;
                return null;
            }

            // ...renderer.base.GeoRenderer -> ...
            String name = rendererInterface.getName();
            String root = name.substring(0, name.length() - suffix.length());
            ClassLoader loader = rendererInterface.getClassLoader();

            Class<?> geoModel = Class.forName(root + ".model.GeoModel", false, loader);
            Class<?> renderState = Class.forName(root + ".renderer.base.GeoRenderState", false, loader);
            Class<?> renderStateImpl = Class.forName(root + ".renderer.base.GeoRenderState$Impl", false, loader);
            Class<?> bakedGeoModel = Class.forName(root + ".cache.model.BakedGeoModel", false, loader);
            Class<?> bone = Class.forName(root + ".cache.model.GeoBone", false, loader);
            Class<?> cuboidBone = Class.forName(root + ".cache.model.cuboid.CuboidGeoBone", false, loader);
            Class<?> cube = Class.forName(root + ".cache.model.cuboid.GeoCube", false, loader);
            Class<?> quad = Class.forName(root + ".cache.model.GeoQuad", false, loader);
            Class<?> vertex = Class.forName(root + ".cache.model.GeoVertex", false, loader);
            Class<?> snapshot = Class.forName(root + ".animation.state.BoneSnapshot", false, loader);
            Class<?> renderUtil = Class.forName(root + ".util.RenderUtil", false, loader);
            Class<?> renderPassInfo = Class.forName(root + ".renderer.base.RenderPassInfo", false, loader);
            Class<?> cameraState = Class.forName("net.minecraft.client.renderer.state.level.CameraRenderState", false, loader);
            Class<?> collector = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector", false, loader);
            Class<?> gsonLoaderClass = Class.forName(root + ".loading.loader.GeckoLibGsonLoader", false, loader);
            Class<?> resources = Class.forName(root + ".cache.GeckoLibResources", false, loader);
            Class<?> modelCache = Class.forName(root + ".cache.BakedModelCache", false, loader);

            Field cacheField = null;
            for (Field field : resources.getDeclaredFields()) {
                if (field.getType() == modelCache && field.trySetAccessible()) {
                    cacheField = field;
                    break;
                }
            }
            if (cacheField == null) {
                throw new NoSuchFieldException("no baked model cache on " + resources.getName());
            }

            Class<?> animationCache = Class.forName(root + ".cache.BakedAnimationCache", false, loader);
            Field animCacheField = null;
            for (Field field : resources.getDeclaredFields()) {
                if (field.getType() == animationCache && field.trySetAccessible()) {
                    animCacheField = field;
                    break;
                }
            }
            if (animCacheField == null) {
                throw new NoSuchFieldException("no baked animation cache on " + resources.getName());
            }

            Class<?> mathParser = Class.forName(root + ".loading.math.MathParser", false, loader);
            Method createParser = mathParser.getMethod("create");
            Method bakeAnims = gsonLoaderClass.getMethod("bakeGeckoLibAnimationsFile",
                Identifier.class, JsonObject.class, mathParser);

            reflection = new Reflection(
                method(rendererInterface, "getGeoModel"),
                method(geoModel, "getModelResource", renderState),
                method(geoModel, "getTextureResource", renderState),
                method(rendererInterface, "getTextureLocation", renderState),
                method(geoModel, "getBakedModel", Identifier.class),
                method(bakedGeoModel, "topLevelBones"),
                method(bone, "children"),
                field(cuboidBone, "cubes"),
                field(bone, SNAPSHOT_FIELD),
                method(snapshot, "isHidden"),
                method(snapshot, "areChildrenHidden"),
                method(renderUtil, "prepMatrixForBone", PoseStack.class, bone),
                method(cube, "quads"),
                method(cube, "rotation"),
                method(cube, "translateToPivotPoint", PoseStack.class),
                method(cube, "rotate", PoseStack.class),
                method(cube, "translateAwayFromPivotPoint", PoseStack.class),
                method(quad, "vertices"),
                method(quad, "direction"),
                method(quad, "normalVec"),
                method(vertex, "posX"),
                method(vertex, "posY"),
                method(vertex, "posZ"),
                method(vertex, "texU"),
                method(vertex, "texV"),
                method(renderPassInfo, "create", rendererInterface, renderState, PoseStack.class, cameraState, boolean.class),
                method(renderPassInfo, "captureModelRenderPose"),
                method(renderPassInfo, "renderPosed", Runnable.class),
                method(rendererInterface, "preRenderPass", renderPassInfo, collector),
                method(rendererInterface, "scaleModelForRender", renderPassInfo, float.class, float.class),
                method(rendererInterface, "adjustRenderPose", renderPassInfo),
                renderStateImpl,
                cameraState,
                method(gsonLoaderClass, "bakeGeckoLibModelFile", Identifier.class, JsonObject.class),
                gsonLoaderClass.getDeclaredConstructor().newInstance(),
                cacheField,
                method(modelCache, "cache"),
                animCacheField,
                method(animationCache, "cache"),
                bakeAnims,
                createParser
            );
        } catch (Throwable e) {
            reflectionFailed = true;
            PolymerPatcher.LOGGER.warn("GeckoLib is installed but could not be read; its entities will not render", e);
        }

        return reflection;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getField(name);
        field.setAccessible(true);
        return field;
    }

    // ---------------------------------------------------------------------------------------------
    // Set-up, once per entity type
    // ---------------------------------------------------------------------------------------------

    /**
     * The model this renderer draws, baked and registered with GeckoLib, or null when there is none to
     * be had.
     */
    @Nullable
    public static Baked load(@NotNull Object renderer) {
        Reflection r = reflection(renderer);
        if (r == null) {
            return null;
        }

        patchAnimations(r);

        try {
            Object geoModel = r.getGeoModel().invoke(renderer);
            if (geoModel == null) {
                return null;
            }

            Object state = r.renderStateImpl().getDeclaredConstructor().newInstance();
            Identifier modelId = (Identifier) r.getModelResource().invoke(geoModel, state);
            Identifier textureId = (Identifier) r.getTextureResource().invoke(geoModel, state);
            if (modelId == null) {
                return null;
            }

            if (!bake(r, modelId)) {
                return null;
            }

            Object baked = r.getBakedModel().invoke(geoModel, modelId);
            if (baked == null) {
                return null;
            }

            return new Baked(baked, modelId, strip(textureId));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read the GeckoLib model off {}", renderer.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Reads the model file out of whichever mod carries it, bakes it, and puts it where GeckoLib will
     * look. Returns whether a model is there afterwards - including when a previous call already put it
     * there, since several entities routinely share one.
     */
    private static boolean bake(@NotNull Reflection r, @NotNull Identifier modelId) {
        Boolean previous = BAKED.get(modelId);
        if (previous != null) {
            return previous;
        }

        boolean result = false;
        try {
            JsonObject json = read(modelId);
            if (json != null) {
                Object baked = r.bakeModelFile().invoke(r.gsonLoader(), modelId, json);
                if (baked != null) {
                    put(r, modelId, baked);
                    // Before the first tick asks for animations this model has none of
                    quietMissingAnimations(r, modelId);
                    result = true;
                }
            } else {
                PolymerPatcher.LOGGER.warn("No GeckoLib model file found for {}", modelId);
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Failed to bake the GeckoLib model {}", modelId, e);
        }

        BAKED.put(modelId, result);
        return result;
    }

    /**
     * Puts an empty animation file where GeckoLib will look for this model's, when no mod ships one.
     * <p>
     * GeckoLib's animation cache reports a miss by logging an error, and it does not remember having
     * done so - so a mob whose mod ships a model but no animation of its own asks again on every tick
     * of every one of them, and the answer is an error every time. Crop Critters does exactly this:
     * its wheat and potato critters have a {@code .geo.json} and no {@code .animation.json}, because
     * they are meant to borrow another's. That was 1781 lines in a single log, around a hundred and
     * forty a second, written synchronously on the server thread - which is not merely untidy, it is
     * the thread the game ticks on.
     * <p>
     * An empty file is enough to stop it. The cache only complains when it finds nothing at all, so a
     * present-but-empty entry answers the question without a word, and the mob poses exactly as it did
     * before: the lookup returned nothing either way.
     */
    private static void quietMissingAnimations(@NotNull Reflection r, @NotNull Identifier modelId) {
        if (ANIMATION_STAND_INS.putIfAbsent(modelId, Boolean.TRUE) != null) {
            return;
        }

        try {
            Object cache = r.bakedAnimationCacheField().get(null);
            @SuppressWarnings("unchecked")
            Map<Identifier, Object> map = (Map<Identifier, Object>) r.bakedAnimationCacheMap().invoke(cache);
            if (map.containsKey(modelId)) {
                return;
            }

            // A sibling's animations rather than an empty file. An empty one stopped the "no such
            // animation file" error and immediately earned a new one a level down - GeckoLib then
            // could not find 'misc.idle' inside it, once per tick per mob, which is the same spam
            // wearing a different hat. A mod that ships a model with no animation of its own means
            // for it to borrow another's: Crop Critters has four animation files for a dozen
            // critters, all of them variations on basic_critter. Lending the nearest sibling's
            // animations makes the mob move as intended and asks GeckoLib nothing it cannot answer.
            Object standIn = siblingAnimations(map, modelId);
            if (standIn == null) {
                String loaderName = r.bakeAnimationsFile().getDeclaringClass().getName();
                String root = loaderName.substring(0, loaderName.indexOf(".loading."));
                Class<?> bakedAnimations = Class.forName(root + ".cache.animation.BakedAnimations", false,
                    r.gsonLoader().getClass().getClassLoader());
                standIn = bakedAnimations.getDeclaredConstructor(Map.class).newInstance(Map.of());
            }

            putAnimations(r, Map.of(modelId, standIn));
            PolymerPatcher.LOGGER.debug("{} has no GeckoLib animation file of its own; a sibling's stands in", modelId);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not stand in for the missing GeckoLib animations of {}", modelId, e);
        }
    }

    /**
     * The animations of the nearest thing to this model that has some.
     * <p>
     * Nearest means the same namespace and the same folder, preferring a name that reads like a
     * general case - {@code basic_critter} over {@code pumpkin_critter} - because that is what a mod
     * with one animation file and many models is doing. Ordered by name so the choice is the same on
     * every start rather than whatever the map happened to iterate first.
     */
    @Nullable
    private static Object siblingAnimations(@NotNull Map<Identifier, Object> animations, @NotNull Identifier modelId) {
        String folder = modelId.getPath().contains("/")
            ? modelId.getPath().substring(0, modelId.getPath().lastIndexOf('/') + 1)
            : "";

        Identifier best = null;
        for (Identifier candidate : new java.util.TreeSet<>(
            java.util.Comparator.comparing(Identifier::toString)) {{ addAll(animations.keySet()); }}) {
            if (!candidate.getNamespace().equals(modelId.getNamespace()) || !candidate.getPath().startsWith(folder)) {
                continue;
            }
            boolean general = candidate.getPath().contains("basic") || candidate.getPath().contains("default");
            if (best == null || (general && !(best.getPath().contains("basic") || best.getPath().contains("default")))) {
                best = candidate;
            }
        }
        return best == null ? null : animations.get(best);
    }

    @Nullable
    private static JsonObject read(@NotNull Identifier modelId) throws Exception {
        for (String pattern : MODEL_PATHS) {
            String path = pattern.formatted(modelId.getPath());
            IoSupplier<InputStream> supplier = ResourceHelper.getAsset(modelId.getNamespace(), path);
            if (supplier == null) {
                continue;
            }

            try (InputStream stream = supplier.get()) {
                return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }

        return null;
    }

    /**
     * Adds one baked model to GeckoLib's cache.
     * <p>
     * The cache is a record holding a map that nothing was ever meant to add to after a reload, so the
     * map is replaced with a growable copy the first time and written to directly from then on. Every
     * later lookup - GeckoLib's own included - goes through it, which is the point: the renderer asks
     * for its model the way it always would and gets one.
     */
    @SuppressWarnings("unchecked")
    private static void put(@NotNull Reflection r, @NotNull Identifier modelId, @NotNull Object baked) throws Exception {
        Object cache = r.bakedModelCacheField().get(null);
        Map<Identifier, Object> map = (Map<Identifier, Object>) r.bakedModelCacheMap().invoke(cache);

        try {
            map.put(modelId, baked);
            return;
        } catch (UnsupportedOperationException e) {
            // Immutable, as it is straight after a reload - swap in a copy that can be written to
        }

        Map<Identifier, Object> copy = new HashMap<>(map);
        copy.put(modelId, baked);
        r.bakedModelCacheField().set(null, cache.getClass().getDeclaredConstructor(Map.class).newInstance(copy));
    }

    /**
     * Reads every mod's {@code geckolib/animations/**} files, bakes them with GeckoLib's own loader,
     * and hands the results to its animation cache.
     * <p>
     * The animation cache is a client-run reload's other half, so it is missing on a dedicated server
     * for exactly the reason the model cache was: GeckoLib never runs its resource listener, and the
     * cache stays at the immutable empty map it started as. A mob whose model has been baked this way
     * therefore stands in its file's rest pose and never moves - its animation controller looks in a
     * cache that has nothing in it.
     * <p>
     * The cache is keyed by the id a model's {@code getAnimationResource} will ask for, which is the
     * resource path with GeckoLib's prefixes and suffixes taken off - so every file is baked under the
     * fully qualified resource id, exactly as GeckoLib's own reload bakes them, and put in under the
     * stripped id, exactly as that reload keys them.
     */
    private static void patchAnimations(@NotNull Reflection r) {
        if (animationsRegistered) return;
        synchronized (GeckoLibModel.class) {
            if (animationsRegistered) return;
            animationsRegistered = true;
        }

        try {
            Object parser = r.createMathParser().invoke(null);
            Map<Identifier, Object> animations = new HashMap<>();
            for (var asset : ResourceHelper.GLOBAL_ASSETS.locateFiles("geckolib/animations")) {
                Identifier id = asset.getFirst();
                IoSupplier<InputStream> supplier = asset.getSecond();
                try (InputStream stream = supplier.get()) {
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                    Object baked = r.bakeAnimationsFile().invoke(r.gsonLoader(), id, json, parser);
                    if (baked != null) {
                        animations.put(stripAnimationPath(id), baked);
                    }
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.warn("Failed to bake the GeckoLib animation {}", id, e);
                }
            }
            if (!animations.isEmpty()) {
                putAnimations(r, animations);
                PolymerPatcher.LOGGER.info("Baked and registered {} GeckoLib animation file(s)", animations.size());
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not register GeckoLib animations", e);
        }
    }

    /**
     * The id the animation cache is keyed by, which is the resource id with the parts every animation
     * id shares taken off - the mirror of {@link #strip} for textures, and of GeckoLib's own
     * {@code stripPrefixAndSuffix}.
     */
    @NotNull
    private static Identifier stripAnimationPath(@NotNull Identifier id) {
        String path = id.getPath();
        Matcher prefix = ANIMATION_PATH_PREFIX.matcher(path);
        if (prefix.find()) {
            path = path.substring(prefix.end());
        }
        Matcher suffix = ANIMATION_PATH_SUFFIX.matcher(path);
        if (suffix.find()) {
            path = path.substring(0, suffix.start());
        }
        return path.length() == id.getPath().length() ? id : id.withPath(path);
    }

    /**
     * Adds baked animations to GeckoLib's cache, swapping in a growable copy when - as on a dedicated
     * server - the map is the immutable empty one a no-op reload left behind. The same swap the model
     * cache needed, for the same reason.
     */
    @SuppressWarnings("unchecked")
    private static void putAnimations(@NotNull Reflection r, @NotNull Map<Identifier, Object> animations) throws Exception {
        Object cache = r.bakedAnimationCacheField().get(null);
        Map<Identifier, Object> map = (Map<Identifier, Object>) r.bakedAnimationCacheMap().invoke(cache);

        try {
            map.putAll(animations);
            return;
        } catch (UnsupportedOperationException e) {
            // Immutable, as it is on a server before a reload ever ran - swap in a copy that can be written to
        }

        Map<Identifier, Object> copy = new HashMap<>(map);
        copy.putAll(animations);
        r.bakedAnimationCacheField().set(null, cache.getClass().getDeclaredConstructor(Map.class).newInstance(copy));
    }

    /**
     * A texture identifier as the generated models are filed under, which is the resource path with
     * the parts every texture shares taken off.
     */
    @NotNull
    public static Identifier strip(@Nullable Identifier texture) {
        if (texture == null) {
            return PolymerPatcher.id("geckolib/missing");
        }
        return texture.withPath(texture.getPath().replace("textures/", "").replace(".png", ""));
    }

    /**
     * A model that has been baked and registered, with the texture it is drawn on.
     */
    public record Baked(Object handle, Identifier modelId, Identifier texture) {
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the model
    // ---------------------------------------------------------------------------------------------

    @NotNull
    public static List<Object> topLevelBones(@NotNull Object renderer, @NotNull Object baked) {
        return array(reflection(renderer), r -> r.topLevelBones().invoke(baked));
    }

    @NotNull
    public static List<Object> childBones(@NotNull Object renderer, @NotNull Object bone) {
        return array(reflection(renderer), r -> r.boneChildren().invoke(bone));
    }

    /**
     * The boxes a bone carries. Only a cuboid bone has any; the others hold nothing but children.
     */
    @NotNull
    public static List<Object> cubesOf(@NotNull Object renderer, @NotNull Object bone) {
        Reflection r = reflection(renderer);
        if (r == null || !r.boneCubes().getDeclaringClass().isInstance(bone)) {
            return List.of();
        }
        return array(r, x -> x.boneCubes().get(bone));
    }

    @NotNull
    public static List<Object> quadsOf(@NotNull Object renderer, @NotNull Object cube) {
        return array(reflection(renderer), r -> r.cubeQuads().invoke(cube));
    }

    @NotNull
    public static List<Object> verticesOf(@NotNull Object renderer, @NotNull Object quad) {
        return array(reflection(renderer), r -> r.quadVertices().invoke(quad));
    }

    /**
     * Which way a face points, in the model's own space.
     */
    @Nullable
    public static Vector3fc normalOf(@NotNull Object renderer, @NotNull Object quad) {
        return call(reflection(renderer), r -> (Vector3fc) r.quadNormal().invoke(quad), null);
    }

    @NotNull
    public static Vec3 rotationOf(@NotNull Object renderer, @NotNull Object cube) {
        return call(reflection(renderer), r -> (Vec3) r.cubeRotation().invoke(cube), Vec3.ZERO);
    }

    public static float[] vertexOf(@NotNull Object renderer, @NotNull Object vertex) {
        Reflection r = reflection(renderer);
        if (r == null) {
            return new float[5];
        }

        try {
            return new float[]{
                (float) r.vertexPosX().invoke(vertex),
                (float) r.vertexPosY().invoke(vertex),
                (float) r.vertexPosZ().invoke(vertex),
                (float) r.vertexTexU().invoke(vertex),
                (float) r.vertexTexV().invoke(vertex),
            };
        } catch (Throwable e) {
            return new float[5];
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Posing
    // ---------------------------------------------------------------------------------------------

    /**
     * Puts the stack where GeckoLib would put it before drawing this bone - its pivot, its rotation,
     * and whatever the animation has done to it this frame.
     */
    public static void prepMatrixForBone(@NotNull Object renderer, @NotNull PoseStack poseStack, @NotNull Object bone) {
        Reflection r = reflection(renderer);
        if (r == null) return;

        try {
            r.prepMatrixForBone().invoke(null, poseStack, bone);
        } catch (Throwable e) {
            // The bone stays where its parent left it, which is wrong but drawn
        }
    }

    /**
     * The box's own pivot and rotation, which are fixed by the model file rather than animated - but
     * still have to be applied here, because a generated item model can only hold a box square to its
     * own axes.
     */
    public static void applyCubeTransform(@NotNull Object renderer, @NotNull PoseStack poseStack, @NotNull Object cube) {
        Reflection r = reflection(renderer);
        if (r == null) return;

        try {
            r.cubeTranslateToPivot().invoke(cube, poseStack);
            r.cubeRotate().invoke(cube, poseStack);
            r.cubeTranslateAwayFromPivot().invoke(cube, poseStack);
        } catch (Throwable e) {
            // As above: drawn unrotated rather than not drawn
        }
    }

    public static boolean isHidden(@NotNull Object renderer, @NotNull Object bone) {
        return snapshotFlag(renderer, bone, true);
    }

    public static boolean areChildrenHidden(@NotNull Object renderer, @NotNull Object bone) {
        return snapshotFlag(renderer, bone, false);
    }

    private static boolean snapshotFlag(@NotNull Object renderer, @NotNull Object bone, boolean self) {
        Reflection r = reflection(renderer);
        if (r == null) return false;

        try {
            Object snapshot = r.boneSnapshot().get(bone);
            // No snapshot means no render pass is running, and outside one nothing is hidden
            if (snapshot == null) return false;
            return (boolean) (self ? r.snapshotHidden() : r.snapshotChildrenHidden()).invoke(snapshot);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Runs GeckoLib's render pass far enough for the bones to be posed, then hands control back with
     * the pose in place.
     * <p>
     * This is the same sequence {@code performRenderPass} follows, stopping where it would start
     * turning bones into vertices - the one thing a server has no use for. {@code renderPosed} is what
     * applies the animation, so the walk has to happen inside it: outside, every bone is back in the
     * pose the model file gave it.
     */
    public static boolean renderPosed(@NotNull Object renderer, @NotNull Object renderState, @NotNull PoseStack poseStack,
                                      float scaleWidth, float scaleHeight, @NotNull Runnable posed) {
        Reflection r = reflection(renderer);
        if (r == null) return false;

        try {
            // Given a real camera rather than a null one. Nothing here needs a camera to be anywhere in
            // particular, but a renderer that reads one and finds nothing there fails the whole pass,
            // and a blank camera costs an allocation against that
            Object camera = null;
            try {
                camera = r.cameraState().getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
                // A camera that will not be built is no worse than the null that used to be passed
            }

            Object info = r.renderPassCreate().invoke(null, renderer, renderState, poseStack, camera, true);

            // Optional: a renderer may add layers or bone updaters here, and one that will not run
            // without a client costs its extras rather than the whole model
            try {
                r.rendererPreRenderPass().invoke(renderer, info, null);
            } catch (Throwable ignored) {
            }

            r.rendererScaleModel().invoke(renderer, info, scaleWidth, scaleHeight);
            r.rendererAdjustPose().invoke(renderer, info);
            r.renderPassCaptureModelPose().invoke(info);
            r.renderPassRenderPosed().invoke(info, posed);
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Handed on rather than swallowed. A pass that fails here fails every tick, and the mob is
            // simply never drawn; saying so only at debug left every GeckoLib mob invisible with
            // nothing anywhere to say why. The caller reports it once per entity type
            throw new RuntimeException("GeckoLib render pass failed for " + renderer.getClass().getName(), e.getCause());
        } catch (Throwable e) {
            throw new RuntimeException("GeckoLib render pass failed for " + renderer.getClass().getName(), e);
        }
    }

    /**
     * How much wider and taller than its model a renderer draws its mob, which is a thing a mod sets
     * on the renderer rather than in the model file. One and one when it says nothing.
     */
    public static float[] scale(@NotNull Object renderer) {
        float[] scale = {1.0F, 1.0F};
        Field[] fields = SCALE_FIELDS.get(renderer.getClass());
        if (fields.length == 2) {
            try {
                scale[0] = fields[0].getFloat(renderer);
                scale[1] = fields[1].getFloat(renderer);
            } catch (IllegalAccessException | RuntimeException e) {
                // The default scale is safer than dropping the whole entity render pass.
            }
        }
        return scale;
    }

    /**
     * Builds the render state for a GeckoLib renderer, which the ordinary way cannot.
     * <p>
     * { createRenderState()} on a Geo renderer returns null - literally, it is overridden to do
     * so - because GeckoLib builds its state from the animatable rather than from nothing. Calling the
     * usual pair of create-then-extract therefore hands extract a null to fill in, and every GeckoLib
     * mob fails on its first tick. The two-argument form makes and fills it in one go.
     */
    @Nullable
    public static Object createRenderState(@NotNull Object renderer, @NotNull Object entity, float partialTick) {
        for (Method method : RENDER_STATE_METHODS.get(renderer.getClass())) {
            if (!method.getParameterTypes()[0].isInstance(entity)) continue;

            // Whatever this throws is handed on rather than swallowed. Falling through to "no such
            // method" on a method that plainly exists sent the search for this bug in entirely the
            // wrong direction - the method was found every time, and failed when it was called.
            try {
                // Only request access after checking the entity parameter. Another overload may
                // belong to a renderer's superclass and never be called for this entity at all.
                if (!method.canAccess(renderer)) {
                    method.setAccessible(true);
                }
                Object state = method.invoke(renderer, entity, partialTick);
                if (state != null) {
                    return state;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new RuntimeException("GeckoLib's " + method + " failed while building a render state", e.getCause());
            } catch (Throwable t) {
                throw new RuntimeException("Could not call GeckoLib's " + method + " to build a render state", t);
            }
        }

        return null;
    }

    @Nullable
    public static Identifier textureLocation(@NotNull Object renderer, @NotNull Object renderState) {
        return call(reflection(renderer), r -> (Identifier) r.getTextureLocation().invoke(renderer, renderState), null);
    }

    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Reader<T> {
        T read(Reflection reflection) throws Exception;
    }

    @NotNull
    private static List<Object> array(@Nullable Reflection r, @NotNull Reader<Object> reader) {
        if (r == null) return List.of();

        try {
            Object array = reader.read(r);
            if (array == null) return List.of();

            List<Object> values = new ArrayList<>(Array.getLength(array));
            for (int i = 0; i < Array.getLength(array); i++) {
                Object value = Array.get(array, i);
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        } catch (Throwable e) {
            return List.of();
        }
    }

    private static <T> T call(@Nullable Reflection r, @NotNull Reader<T> reader, T fallback) {
        if (r == null) return fallback;

        try {
            T value = reader.read(r);
            return value != null ? value : fallback;
        } catch (Throwable e) {
            return fallback;
        }
    }
}
