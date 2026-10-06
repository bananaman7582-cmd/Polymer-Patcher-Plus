package me.drex.polymerpatcher.entity;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.entity.citadel.CitadelEntityModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModelInstance;
import me.drex.polymerpatcher.entity.citadel.ForeignCitadelModel;
import me.drex.polymerpatcher.entity.citadel.CitadelModels;
import me.drex.polymerpatcher.entity.citadel.CitadelPart;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibBone;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibEntityModel;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibModel;
import me.drex.polymerpatcher.entity.geckolib.GeckoLibModelInstance;
import me.drex.polymerpatcher.entity.render.ServerEntityModelSet;
import me.drex.polymerpatcher.entity.render.ServerEntityRenderDispatcher;
import me.drex.polymerpatcher.entity.render.ServerItemModelResolver;
import me.drex.polymerpatcher.entity.render.ServerRendererContext;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import me.drex.polymerpatcher.entity.plain.PlainModel;
import java.util.stream.Collectors;

public class AnimatedEntities {

    public static final List<PolyModelInstance<?, ?, ?>> POLY_MODELS = new ArrayList<>();
    public static final List<CitadelModelInstance<?, ?, ?>> CITADEL_MODELS = new ArrayList<>();
    public static final List<GeckoLibModelInstance<?, ?, ?>> GECKOLIB_MODELS = new ArrayList<>();
    protected static final IdentityHashMap<EntityType<?>, Function<? extends Entity, ? extends SimpleEntityModel<?, ?, ?>>> ENTITY_FACTORIES = new IdentityHashMap<>();

    public static <Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> void registerEntity(
        EntityType<Entity> type,
        EntityRenderer<Entity, RenderState> renderer, ModelLayerLocation modelLayer, List<ModelPart> allParts, Identifier texture
    ) {
        PolyModelInstance<Entity, RenderState, Model> defaultModel = PolyModelInstance.create(renderer, modelLayer, allParts, texture);
        POLY_MODELS.add(defaultModel);
        ENTITY_FACTORIES.put(type, entity -> new SimpleEntityModel<>((Entity) entity, defaultModel));
    }

    /**
     * Whether a drawing was built for this type, which is the first thing to know about an entity nobody
     * can see. Used by {@code /pp inspect}.
     */
    public static boolean hasDrawing(EntityType<?> type) {
        return ENTITY_FACTORIES.containsKey(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerEntities(RenderRegistry registry) {
        Map<ModelLayerLocation, ModelPart> bakedModels = new LinkedHashMap<>();

        registry.modelLayers.forEach((modelLayerLocation, layerDefinition) -> {
            // A layer that will not bake costs its own entity its model and nothing else; baking them
            // in one unguarded pass would have cost every entity theirs
            try {
                bakedModels.put(modelLayerLocation, layerDefinition.bakeRoot());
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Failed to bake model layer {}", modelLayerLocation, e);
            }
        });
        me.drex.polymerpatcher.entity.armor.ArmorModels.discover(bakedModels, registry.armorData);

        var entityModelSet = new ServerEntityModelSet(bakedModels);
        var renderDispatcher = new ServerEntityRenderDispatcher();
        me.drex.polymerpatcher.client.ServerMinecraft.useDispatcher(renderDispatcher);
        var modelResolver = new ServerItemModelResolver();

        ServerRendererContext context = new ServerRendererContext(renderDispatcher, modelResolver, entityModelSet);

        Tally tally = new Tally();

        registry.entityData.forEach((renderInfo) -> {
            tally.seen++;
            Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(renderInfo.type());
            Class<? extends EntityRenderer> entityRendererClass = renderInfo.entityRenderer();
            if (entityRendererClass == null) {
                tally.skip("with no renderer recorded in the dump", BuiltInRegistries.ENTITY_TYPE.getKey(renderInfo.type()));
                PolymerPatcher.LOGGER.warn("Failed to find entity renderer for {}", renderInfo.type());
                return;
            }

            // A dumped layer is not necessarily the entity's body. Render layers can bake their own
            // vanilla model (a helmet is the common one), and Mimicube records armor_stand#helmet even
            // though its actual body is a Citadel model built in the renderer constructor. Treating the
            // mere presence of that auxiliary layer as the body registered an empty armor stand helmet
            // and made the mob invisible. Inspect what the renderer really holds before choosing the
            // vanilla layer path; this applies to every Citadel/Gecko renderer, not one entity id.
            if (!renderInfo.modelLayers().isEmpty()) {
                try {
                    EntityRenderer probe = createEntityRenderer(entityId, entityRendererClass, context,
                        renderInfo.modelLayers().iterator().next(), false);
                    Object held = probe instanceof LivingEntityRenderer livingRenderer
                        ? livingRenderer.getModel() : null;
                    if (probe != null && (GeckoLibModel.isGeoRenderer(probe) || CitadelModel.isCitadelModel(held))) {
                        registerForeignEntity(renderInfo, entityId, probe, tally);
                        return;
                    }
                } catch (Throwable e) {
                    // The normal guarded path below will report a constructor that genuinely cannot build.
                    PolymerPatcher.LOGGER.debug("Could not inspect the renderer model for {}", entityId, e);
                }
            }
            // Nothing baked a layer for this entity. Either it has no model at all, or its model was
            // not built the way the game builds them - which is what a Citadel or GeckoLib mob looks
            // like from here
            if (renderInfo.modelLayers().isEmpty()) {
                // Guarded the same way the layer path below is: one entity that cannot be read costs
                // that entity its model, where letting it out of here costs every entity after it too
                try {
                    registerForeignEntity(renderInfo, entityId, entityRendererClass, context, tally);
                } catch (Throwable e) {
                    tally.skip("that threw while being set up", entityId);
                    PolymerPatcher.LOGGER.warn("Failed to set up rendering for {}", entityId, e);
                }
                return;
            }
            // One entity failing is one entity drawn as its vanilla stand-in; letting it out of here
            // would have been every entity after it, too
            try {
                int registered = 0;
                for (ModelLayerLocation modelLayer : renderInfo.modelLayers()) {
                    EntityRenderer entityRenderer = createEntityRenderer(entityId, entityRendererClass, context, modelLayer, true);

                    if (entityRenderer != null && !texturesFor(entityId, renderInfo).isEmpty()) {
                        for (Identifier texture : texturesFor(entityId, renderInfo)) {
                            ModelPart modelPart = bakedModels.get(modelLayer);
                            // Absent when its layer would not bake, which is already warned about above
                            if (modelPart == null) continue;
                            AnimatedEntities.registerEntity(renderInfo.type(), entityRenderer, modelLayer, modelPart.getAllParts(), texture);
                            tally.layers++;
                            registered++;
                        }
                    }
                }

                // A renderer with neither a model layer nor a texture of its own still draws something:
                // a thrown egg, a snowball, a mod's own projectile are all an item held in the air, and
                // the renderer submits that item rather than a model. Those entities used to fall out of
                // this loop without ever being registered - the loop simply never ran - and rendered as
                // nothing at all
                if (registered == 0) {
                    EntityRenderer itemRenderer = createEntityRenderer(entityId, entityRendererClass, context, null, false);
                    if (itemRenderer != null) {
                        AnimatedEntities.registerEntity(renderInfo.type(), itemRenderer, null, java.util.Collections.emptyList(), null);
                        tally.layers++;
                    }
                }
            } catch (Throwable e) {
                tally.skip("that threw while being set up", entityId);
                PolymerPatcher.LOGGER.warn("Failed to set up rendering for {}", entityId, e);
            }
        });

        tally.report();
    }

    /**
     * What became of every entity in the dump, so a server that renders nothing says why once rather
     * than saying nothing at all.
     * <p>
     * Most of the individual reasons are only worth a debug line - an entity with no model is the
     * common case, not a fault - but "none of them worked" is worth knowing at a glance, and until
     * this existed the only way to find out was to turn debug logging on and read a few thousand lines.
     */
    private static final class Tally {
        /** How many of each skipped group to name. Enough to recognise a pattern, short enough to read. */
        private static final int EXAMPLES = 6;
        /** How many can be named outright before the line stops being worth reading. */
        private static final int FULLY_NAMED = 40;

        int seen;
        int layers;
        int citadel;
        int geckolib;

        // Insertion-ordered so the reasons read in the order they are reached
        private final Map<String, List<Identifier>> skipped = new LinkedHashMap<>();

        void skip(String reason, Identifier entityId) {
            skipped.computeIfAbsent(reason, key -> new ArrayList<>()).add(entityId);
        }

        void report() {
            PolymerPatcher.LOGGER.info(
                "Modded entity models: {} from model layers, {} from Citadel, {} from GeckoLib, out of {} entities in the dump",
                layers, citadel, geckolib, seen);

            if (layers + citadel + geckolib == 0 && seen > 0) {
                PolymerPatcher.LOGGER.warn("No modded entity got a model, so every one of them will render as its vanilla stand-in.");
            }

            skipped.forEach((reason, ids) -> {
                // Named in full while the list is short enough to read. It used to be trimmed to a
                // handful with the rest only at debug, which meant that working out why one particular
                // mob was invisible needed a restart with debug logging on - and on a live server that
                // is a restart nobody wants to spend. A couple of dozen names cost one line.
                if (ids.size() <= FULLY_NAMED) {
                    PolymerPatcher.LOGGER.info("  {} {}: {}", ids.size(), reason,
                        ids.stream().map(Identifier::toString).collect(Collectors.joining(", ")));
                    return;
                }

                String examples = ids.stream().limit(EXAMPLES).map(Identifier::toString).collect(Collectors.joining(", "));
                PolymerPatcher.LOGGER.info("  {} {}: {}, and {} more", ids.size(), reason, examples, ids.size() - EXAMPLES);
                PolymerPatcher.LOGGER.debug("  all {} {}: {}", ids.size(), reason,
                    ids.stream().map(Identifier::toString).collect(Collectors.joining(", ")));
            });
        }
    }

    /**
     * Registers a mob whose model was built by something other than the game - Citadel, which Alex's
     * Mobs and its siblings carry a copy of, or GeckoLib - which the layer-based path above cannot see.
     * <p>
     * Neither kind is a layer baked from a definition, so nothing was captured for them at dump time
     * and there is no baked part to hand around. The renderer is built anyway and asked what it draws
     * with, and whichever of the two it turns out to be, the shape is read straight off it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    /** Textures already worked out for an entity, so the search is done once rather than per call. */
    private static final Map<Identifier, Set<Identifier>> TEXTURES_BY_ENTITY = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every picture a model was written under for this entity.
     * <p>
     * A renderer that draws a second model of its own can bind a second picture with it, and that one is
     * only safe to use if a model was written wearing it. Anything else names a file the pack does not
     * have, which a client draws as nothing at all.
     */
    public static Set<Identifier> writtenTextures(@Nullable Identifier entityId) {
        Set<Identifier> textures = entityId == null ? null : TEXTURES_BY_ENTITY.get(entityId);
        return textures == null ? Set.of() : textures;
    }

    /**
     * Every texture worth writing a model under for this entity.
     * <p>
     * The dump's own record first, widened to the skins sitting beside it. Where the dump recorded
     * none - which happens whenever a renderer names its texture from somewhere the dump was not
     * watching - the mod's assets are searched by the entity's own name instead, because an entity
     * with no texture is not drawn at all.
     */
    /**
     * The texture a renderer is holding, where the dump recorded none and the name matched nothing.
     * <p>
     * A renderer that draws a single thing generally keeps its texture in a field of its own, and that
     * field is right in the cases where guessing from the entity's name is wrong - which is exactly
     * the set of entities that reach this point. Only taken when there is one candidate: a renderer
     * holding several is choosing between them for reasons this cannot see, and picking one at random
     * would be worse than admitting the texture is unknown.
     */
    /**
     * A texture named the way the rest of this mod names them.
     * <p>
     * A renderer holds the file it is going to bind - {@code textures/entity/tendon_whip_claw.png} -
     * while everything downstream of here works in the shortened form the dump and the pack use,
     * {@code entity/tendon_whip_claw}. Handing the file name straight on produced a model written to
     * {@code models/textures/entity/tendon_whip_claw.png/...}, which is a real path pointing at a
     * texture that is not there: the whip drew its shape and had nothing to put on it.
     */
    public static Identifier spriteForm(Identifier texture) {
        String path = texture.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        return Identifier.fromNamespaceAndPath(texture.getNamespace(), path);
    }

    @Nullable
    private static Set<Identifier> texturesKeptBy(EntityRenderer<?, ?> renderer) {
        Set<Identifier> found = new LinkedHashSet<>();
        for (Class<?> type = renderer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Identifier.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    // Works for a static field and an instance one alike; the argument is ignored for statics
                    if (field.get(renderer) instanceof Identifier texture) {
                        // All of them, not one. A renderer holding several is holding one per thing it
                        // can draw - the dinosaur spirit keeps a picture for each of the three dinosaurs
                        // it can be the ghost of - and which of them this one wants is decided at the
                        // moment it draws, by the picture it binds for it. Giving up here because there
                        // was more than one left that entity with no models written at all
                        found.add(spriteForm(texture));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field that will not be read is one candidate fewer, not a failure
                }
            }
        }
        return found;
    }

    /**
     * Every skin this entity can be drawn with, the one that stands in for it last.
     * <p>
     * Both callers write a model per texture and keep whichever they wrote last, so the order here is
     * what decides which skin the mob is actually seen in. Left to the order the dump happened to
     * record, that was a coin toss - a ferrouslime came out as its eyes layer alone, a small blob with
     * no slime around it. Putting the main texture last means the coin toss lands the right way up
     * without either caller having to know about it.
     */
    private static Set<Identifier> texturesFor(Identifier entityId, RenderRegistry.RenderInfo renderInfo) {
        return TEXTURES_BY_ENTITY.computeIfAbsent(entityId, id -> {
            Set<Identifier> recorded = renderInfo.textures();
            Set<Identifier> all = recorded.isEmpty() ? EntityTextures.guess(id) : EntityTextures.expand(id, recorded);
            all = me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat.addEventTextures(id, all);

            // A player held up with no skin of their own is answered with one of the game's stand-ins,
            // chosen by hashing the uuid - a coin toss between eighteen. The dump recorded whichever
            // one it landed on, so the other seventeen had no model written and every uuid that landed
            // on one of them was drawn as a cube. Every stand-in gets a model now.
            all = DefaultSkins.expand(all);

            Identifier primary = EntityTextures.primary(id, all);
            if (primary == null || all.size() < 2) {
                return all;
            }

            Set<Identifier> ordered = new LinkedHashSet<>(all);
            ordered.remove(primary);
            ordered.add(primary);
            return ordered;
        });
    }

    private static void registerForeignEntity(RenderRegistry.RenderInfo renderInfo, Identifier entityId, Class<? extends EntityRenderer> entityRendererClass, EntityRendererProvider.Context context, Tally tally) {
        // Quietly for most of them: an entity that reaches here with no texture recorded has no model to
        // begin with - a thrown item, a projectile drawn as its item - and a failed constructor says
        // nothing about it worth reading. One that does have a texture was meant to be seen, and a renderer
        // that will not build for it is exactly why somebody is standing in front of nothing
        boolean meantToBeSeen = !texturesFor(entityId, renderInfo).isEmpty();
        EntityRenderer entityRenderer;
        try {
            entityRenderer = createEntityRenderer(entityId, entityRendererClass, context, null, meantToBeSeen);
        } catch (Throwable e) {
            // A renderer built for a client can reach for one while being constructed, which on a
            // server is not there to be reached for
            tally.skip("whose renderer would not build", entityId);
            // The reason is the whole of what anyone needs here and there are never many of these, so
            // it is said outright rather than kept behind debug logging. The trace stays at debug
            PolymerPatcher.LOGGER.info("Could not build the renderer for {}: {}", entityId, describe(e));
            PolymerPatcher.LOGGER.debug("Could not build the renderer for {}", entityId, e);
            return;
        }

        // Null rather than thrown: createEntityRenderer hands back nothing when it finds no
        // constructor it knows how to call
        if (entityRenderer == null) {
            tally.skip("whose renderer would not build", entityId);
            PolymerPatcher.LOGGER.debug("Found no constructor this can call on the renderer for {}", entityId);
            return;
        }

        registerForeignEntity(renderInfo, entityId, entityRenderer, tally);
    }

    /** Registers a renderer that has already been built and identified as a foreign-model renderer. */
    private static void registerForeignEntity(RenderRegistry.RenderInfo renderInfo, Identifier entityId,
                                              EntityRenderer entityRenderer, Tally tally) {

        // GeckoLib first: it names its own texture, so it works whether or not the dump found one
        if (GeckoLibModel.isGeoRenderer(entityRenderer)) {
            registerGeckoLibEntity(renderInfo, entityId, entityRenderer, tally);
            return;
        }

        // Whatever the dump saw, plus whatever the renderer is holding. A renderer keeps a picture per
        // thing it can draw, and a dump only ever recorded the ones somebody happened to be looking at:
        // the dinosaur spirit can be the ghost of three different dinosaurs and was seen as one of them,
        // so the other two had no picture written and came out wearing the wrong one
        Set<Identifier> kept = texturesKeptBy(entityRenderer);
        if (!kept.isEmpty() && !texturesFor(entityId, renderInfo).isEmpty()) {
            Set<Identifier> all = new LinkedHashSet<>(texturesFor(entityId, renderInfo));
            if (all.addAll(kept)) {
                TEXTURES_BY_ENTITY.put(entityId, all);
                PolymerPatcher.LOGGER.debug("{} can also be drawn with {}, which its renderer was holding",
                    entityId, kept);
            }
        }

        if (texturesFor(entityId, renderInfo).isEmpty()) {
            // Nothing recorded and nothing that matches the entity's own name - but a renderer that
            // draws one thing usually keeps the texture for it in a field, and is right where the
            // guessing is wrong. A tendon whip's segments are drawn from tendon_whip_claw.png, which
            // no amount of looking for tendon_segment.png was ever going to find.
            if (kept.isEmpty()) {
                // Having no texture and no model of its own does not mean an entity draws nothing. A thrown
                // egg, a snowball, Alex's Caves' gumballs, ice cream scoops and guano are an item spinning in
                // the air: the renderer submits the item itself, which needs no texture named anywhere and no
                // model written to the pack. Twenty of them were turned away here for want of a texture they
                // never needed, and arrived in the world as nothing at all.
                registerCitadelEntity(renderInfo, entityId, entityRenderer, tally);
                return;
            }
            TEXTURES_BY_ENTITY.put(entityId, kept);
            PolymerPatcher.LOGGER.debug("{} will be drawn with {}, which its renderer was holding", entityId, kept);
        }
        registerCitadelEntity(renderInfo, entityId, entityRenderer, tally);
    }

    /**
     * Registers a mob GeckoLib draws, once its model has been read out of the mod's own jar and baked.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerGeckoLibEntity(RenderRegistry.RenderInfo renderInfo, Identifier entityId, EntityRenderer entityRenderer, Tally tally) {
        GeckoLibModel.Baked baked = GeckoLibModel.load(entityRenderer);
        if (baked == null) {
            tally.skip("whose GeckoLib model could not be read", entityId);
            return;
        }

        List<GeckoLibBone> roots = GeckoLibBone.resolve(entityRenderer, baked.handle());
        if (roots.isEmpty()) {
            tally.skip("whose GeckoLib model had no readable bones", entityId);
            PolymerPatcher.LOGGER.warn("Found no readable bones on GeckoLib model {} for {}", baked.modelId(), entityId);
            return;
        }

        GeckoLibModelInstance instance = GeckoLibModelInstance.create(entityRenderer, baked, roots);
        GECKOLIB_MODELS.add(instance);

        // Models are filed under the texture the mob is wearing, and GeckoLib gives two different
        // answers for that: the model file names one, and the renderer names another - Crop Critters
        // keeps its textures a directory deeper than its model file claims, and swaps between a tame
        // and an untamed one besides. Generating for only the model file's answer meant the renderer
        // asked at every tick for a model that had never been written, and a model that is not there is
        // drawn as a full purple and black cube - which is why they were untextured blocks half sunk
        // into the floor rather than critters. So every texture the dump saw this mob wearing gets one
        for (Identifier texture : texturesFor(entityId, renderInfo)) {
            if (!texture.equals(baked.texture())) {
                GECKOLIB_MODELS.add(instance.withTexture(texture));
            }
        }

        tally.geckolib++;
        ENTITY_FACTORIES.put(renderInfo.type(), entity -> new GeckoLibEntityModel<>(entity, instance));
        PolymerPatcher.LOGGER.debug("Registered GeckoLib model {} for {}", baked.modelId(), entityId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerCitadelEntity(RenderRegistry.RenderInfo renderInfo, Identifier entityId, EntityRenderer entityRenderer, Tally tally) {
        // A living renderer keeps the model it is drawing with and is built the way the game builds
        // them, so its pose can be reproduced. Anything else - a thrown spear, an anchor, a submarine -
        // keeps its model in a field of its own and poses it however it likes, and is drawn by letting
        // the renderer itself do it
        boolean living = entityRenderer instanceof LivingEntityRenderer;
        Object model = living ? ((LivingEntityRenderer) entityRenderer).getModel() : null;

        if (living && CitadelModel.isCitadelModel(model)) {
            CitadelModel.warmAnimator(model);
        }
        if (living && !CitadelModel.isCitadelModel(model)) {
            // A model built by hand in the renderer's constructor rather than from a registered layer:
            // MCreator's generated renderers are the usual case, and they recorded no layer at dump time,
            // so the layer path above never sees them. The parts are read straight off the model the
            // renderer is holding, filed under a layer named after the entity, and registered exactly as
            // a baked one is - which keeps the animation, the posing and the texture choice that path
            // already has, instead of a second hand-rolled posing path that never ran setupAnim.
            if (model instanceof EntityModel<?> plainModel) {
                try {
                    List<ModelPart> parts = PlainModel.roots(plainModel);
                    if (PlainModel.hasGeometry(parts) && !texturesFor(entityId, renderInfo).isEmpty()) {
                        ModelLayerLocation layer = new ModelLayerLocation(entityId, "main");
                        for (Identifier texture : texturesFor(entityId, renderInfo)) {
                            registerEntity(renderInfo.type(), entityRenderer, layer, parts, texture);
                        }
                        tally.layers++;
                        PolymerPatcher.LOGGER.debug("Read {} plain model part(s) for {} from {}",
                            parts.size(), entityId, model.getClass().getName());
                        return;
                    }
                } catch (Throwable t) {
                    PolymerPatcher.LOGGER.debug("Failed to read plain EntityModel for {}", entityId, t);
                }
            }
            tally.skip("whose model is not one this can read", entityId);
            PolymerPatcher.LOGGER.debug("{} draws {} with {}, which is not a model this can read",
                entityRenderer.getClass().getName(), entityId, model == null ? "nothing" : model.getClass().getName());
            return;
        }

        // Every model the renderer keeps, not only the one it happens to be holding: a mob whose
        // renderer picks between several would otherwise be drawn with whichever was captured here,
        // whatever it was actually posed through
        CitadelModelInstance instance = null;
        int drawable = 0;

        for (Object candidate : CitadelModels.candidates(entityRenderer, model)) {
            me.drex.polymerpatcher.item.CitadelItemModels.noteModelPackage(candidate);
            CitadelModels.Resolved resolved = CitadelModels.resolve(candidate);
            if (resolved.roots().isEmpty()) {
                PolymerPatcher.LOGGER.debug("Found no readable parts on Citadel model {} for {}", candidate.getClass().getName(), entityId);
                continue;
            }
            drawable++;

            // One instance per texture, so every variant a mob can wear gets its models written, and
            // the last stands in for the mob - which it ends up drawn with is decided per tick
            for (Identifier texture : texturesFor(entityId, renderInfo)) {
                CitadelModelInstance written = CitadelModelInstance.create(entityRenderer, resolved, texture);
                CITADEL_MODELS.add(written);
                // Dinosaur spirits are drawn through Alex's Caves' red-ghost shader. An unmodded
                // client has no such shader, so write the same geometry once more beneath a baked
                // orange/translucent texture; ForeignCitadelModel selects it when it sees that layer.
                for (Identifier extra : me.drex.polymerpatcher.entity.render.RenderCaptureRules
                    .extraTextures(entityId.toString(), texture)) {
                    CITADEL_MODELS.add(CitadelModelInstance.create(entityRenderer, resolved, extra));
                }
                // The model the renderer is holding now is the sensible default for the fallback path
                if (candidate == model || instance == null) {
                    instance = written;
                }
            }
        }

        if (instance == null) {
            // A renderer that keeps no model of its own is not therefore a renderer that draws
            // nothing. A nuclear bomb is a block with a colour over it; a teletor's weapon is the item
            // it carries; both are drawn straight, with no model anywhere for this to have found. They
            // were dropped here for want of one and arrived in the world as nothing at all.
            //
            // Blocks and items need nothing written to the pack - the client has both already - so the
            // renderer can simply be run and whatever it reaches for taken.
            if (!living && me.drex.polymerpatcher.entity.citadel.CitadelDraw.canCatch()) {
                // Some of these draw nothing anything here can see - a gumball is four vertices built by
                // hand - so a flat square wearing the renderer's own picture is written for them, and used
                // only when a tick of the renderer produces nothing at all
                java.util.Set<Identifier> flat = texturesFor(entityId, renderInfo);
                FlatEntityModels.want(flat);
                Identifier alone = flat.isEmpty() ? null : flat.iterator().next();
                ENTITY_FACTORIES.put(renderInfo.type(), entity -> new ForeignCitadelModel<>(entity, entityRenderer, alone));
                tally.citadel++;
                PolymerPatcher.LOGGER.debug("{} keeps no Citadel model for {}; it will be run for the blocks and items it draws",
                    entityRenderer.getClass().getName(), entityId);
                return;
            }

            tally.skip("whose Citadel model had no readable parts", entityId);
            PolymerPatcher.LOGGER.debug("Found no readable parts on any Citadel model {} keeps for {}",
                entityRenderer.getClass().getName(), entityId);
            return;
        }
        tally.citadel++;

        java.util.Map<String, Identifier> layerTextures = borrowedModels(entityId, entityRenderer);
        CitadelModelInstance defaultModel = instance;
        ENTITY_FACTORIES.put(renderInfo.type(), living
            ? entity -> new CitadelEntityModel<>(entity, defaultModel)
            : entity -> new ForeignCitadelModel<>(entity, defaultModel, layerTextures));
        PolymerPatcher.LOGGER.debug("Registered {} Citadel model(s) for {}, drawn {}",
            drawable, entityId, living ? "by posing them here" : "by running the renderer");

    }

    /** A model one renderer draws that is kept in another renderer's class, and the texture bound for it. */
    private record BorrowedModel(String holder, String field, Identifier texture) {
    }

    /**
     * Models a renderer draws without keeping them, by the entity that draws them.
     * <p>
     * A tendon whip is strung together out of the murmur's neck: its renderer reaches into the murmur head
     * renderer for that model and binds the murmur's skin for it. Nothing in the tendon renderer's own
     * fields says so, so the neck was never written to the pack and every segment pointed at a model that
     * did not exist - which a client draws as a stretched purple and black cube.
     */
    private static final java.util.Map<String, java.util.List<BorrowedModel>> BORROWED_MODELS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Registers a model an entity renderer borrows from another class instead of retaining itself. */
    public static void registerBorrowedModel(String entityId, String holder, String field,
                                             Identifier texture) {
        BORROWED_MODELS.compute(entityId, (ignored, existing) -> {
            java.util.List<BorrowedModel> models = new java.util.ArrayList<>(
                existing == null ? java.util.List.of() : existing);
            models.add(new BorrowedModel(holder, field, texture));
            return java.util.List.copyOf(models);
        });
    }

    /**
     * Writes the borrowed models for this entity to the pack, and says which texture each is filed under.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.Map<String, Identifier> borrowedModels(Identifier entityId, EntityRenderer entityRenderer) {
        java.util.List<BorrowedModel> borrowed = BORROWED_MODELS.get(entityId.toString());
        if (borrowed == null) {
            return java.util.Map.of();
        }

        java.util.Map<String, Identifier> layerTextures = new java.util.HashMap<>();
        for (BorrowedModel entry : borrowed) {
            try {
                Class<?> holder = Class.forName(entry.holder(), true, entityRenderer.getClass().getClassLoader());
                java.lang.reflect.Field field = holder.getDeclaredField(entry.field());
                field.setAccessible(true);
                Object model = field.get(null);
                if (model == null) {
                    continue;
                }
                CitadelModels.Resolved resolved = CitadelModels.resolve(model);
                if (resolved.roots().isEmpty()) {
                    continue;
                }
                CITADEL_MODELS.add(CitadelModelInstance.create(entityRenderer, resolved, entry.texture()));
                layerTextures.put(resolved.layer(), entry.texture());
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.info("Could not read the {} model that {} draws with: {}", entry.field(), entityId, describe(e));
            }
        }
        return java.util.Map.copyOf(layerTextures);
    }

    /**
     * Builds a renderer, defining whatever client-only classes it reaches for along the way.
     * <p>
     * Loading the renderer class itself is only half of it. Its constructor builds a model, and that
     * model rests on a base class the loader refuses on a server just as firmly - Citadel's
     * {@code AdvancedEntityModel} is the one every Alex's Mobs renderer stands on. Each attempt only
     * ever reports the first class it was refused, so the refusal is read out of the failure, that one
     * class is defined, and the constructor is run again; a renderer resting on a chain of them
     * unwinds one round at a time.
     */
    @SuppressWarnings("rawtypes")
    private static EntityRenderer createEntityRenderer(Identifier entityId, Class<? extends EntityRenderer> entityRendererClass, EntityRendererProvider.Context context, ModelLayerLocation modelLayer, boolean warn) {
        Throwable failure = null;

        for (int round = 0; round < ClientOnlyClasses.maxRounds(); round++) {
            try {
                // A renderer that asks for the game client while being built is answered with the
                // stand-in, the same as one that asks while drawing; see ServerMinecraft
                return me.drex.polymerpatcher.client.ServerMinecraft.whileBuilding(
                    () -> construct(entityRendererClass, context, modelLayer, entityId));
            } catch (Throwable t) {
                failure = t;
                // Anything else is a real failure, and running the constructor again would only
                // produce it a second time
                if (!ClientOnlyClasses.defineRefused(t)) break;
            }
        }

        if (warn) {
            // Said in one line rather than as a stack. A renderer that will not build fails the same way
            // for every entity that shares it, so five mobs used to mean five screenfuls of the same
            // trace scrolling the real start-up messages away. The reason is what is worth reading; the
            // trace is still there at debug level for when it is not enough
            PolymerPatcher.LOGGER.warn("No renderer for {} (via {}): {}", entityId, modelLayer, describe(failure));
            PolymerPatcher.LOGGER.debug("Failed to create entity renderer constructor for {}", entityId, failure);
        } else {
            PolymerPatcher.LOGGER.debug("Could not build the renderer for {}", entityId, failure);
        }
        return null;
    }

    /** The innermost thing that actually went wrong, as one line. */
    private static String describe(Throwable failure) {
        if (failure == null) {
            return "no reason given";
        }

        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    }

    /**
     * One attempt, taking whichever constructor shape the renderer offers.
     * <p>
     * The two the game itself uses are tried first. Beyond those a mod is free to ask for anything, and
     * some do - Crop Critters hands its renderer the entity's own id and a flag, and builds its model
     * out of them - so as a last resort any constructor starting with the render context is called with
     * the best value this knows for each remaining parameter. An id is the entity's own, since that is
     * what a renderer asking for one almost always wants; a model layer is the one being set up; a
     * number or flag is left at nothing.
     * <p>
     * Guessing is only reached once the exact shapes have failed, and a wrong guess throws in the
     * constructor and leaves the entity where it already was - without a model.
     */
    @SuppressWarnings("rawtypes")
    private static EntityRenderer construct(Class<? extends EntityRenderer> entityRendererClass, EntityRendererProvider.Context context, ModelLayerLocation modelLayer, Identifier entityId) throws Throwable {
        Constructor<? extends EntityRenderer> declaredConstructor;
        Throwable first;

        try {
            declaredConstructor = entityRendererClass.getDeclaredConstructor(EntityRendererProvider.Context.class);
            declaredConstructor.setAccessible(true);
            return declaredConstructor.newInstance(context);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException e) {
            first = e;
        }

        try {
            declaredConstructor = entityRendererClass.getDeclaredConstructor(EntityRendererProvider.Context.class, ModelLayerLocation.class);
            declaredConstructor.setAccessible(true);
            return declaredConstructor.newInstance(context, modelLayer);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException e) {
            if (ClientOnlyClasses.namesRefusedClass(e)) {
                throw e;
            }
            // Whichever attempt names a refused class is the one worth reporting, since that is the
            // one the caller can act on - the others are usually just "no such constructor"
            if (ClientOnlyClasses.namesRefusedClass(first)) {
                throw first;
            }
        }

        Constructor<?> guessed = shortestContextConstructor(entityRendererClass);
        if (guessed == null) {
            throw first;
        }

        Class<?>[] parameters = guessed.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        arguments[0] = context;
        for (int i = 1; i < parameters.length; i++) {
            arguments[i] = guessArgument(parameters[i], entityId, modelLayer);
        }

        PolymerPatcher.LOGGER.debug("Building the renderer for {} through {}, whose extra arguments are guessed", entityId, guessed);
        guessed.setAccessible(true);
        return (EntityRenderer) guessed.newInstance(arguments);
    }

    /**
     * The constructor taking the render context and the fewest other things, or null when the renderer
     * has none - fewest, because every extra parameter is one more thing being guessed at.
     */
    @Nullable
    private static Constructor<?> shortestContextConstructor(Class<?> entityRendererClass) {
        Constructor<?> best = null;

        for (Constructor<?> candidate : entityRendererClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length < 2 || parameters[0] != EntityRendererProvider.Context.class) {
                continue;
            }
            if (best == null || parameters.length < best.getParameterCount()) {
                best = candidate;
            }
        }

        return best;
    }

    @Nullable
    private static Object guessArgument(Class<?> type, Identifier entityId, @Nullable ModelLayerLocation modelLayer) {
        if (type == Identifier.class) return entityId;
        if (type == ModelLayerLocation.class) return modelLayer;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        // A reference the renderer will either not use or fail on, which is the same outcome as not
        // having tried at all
        return null;
    }
}
