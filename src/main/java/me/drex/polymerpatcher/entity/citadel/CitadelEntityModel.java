package me.drex.polymerpatcher.entity.citadel;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.render.RenderSetups;
import org.jetbrains.annotations.Nullable;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import net.minecraft.util.LightCoordsUtil;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import me.drex.polymerpatcher.mixin.client.LivingEntityRendererAccessor;
import me.drex.polymerpatcher.mixin.client.render.RenderSetupAccessor;
import me.drex.polymerpatcher.mixin.client.render.RenderTypeAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Stands in for a mob whose model Citadel built, which the ordinary path cannot draw.
 * <p>
 * A vanilla renderer hands its model to the collector and lets it do the drawing, which is where
 * {@link me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector} picks each part up. A Citadel
 * renderer does not: it records its own vertices into a buffer and submits them already finished, so
 * by the time anything reaches the collector the parts have been flattened into a single soup of
 * triangles with nothing left to say which part was which.
 * <p>
 * So the model is posed the same way the renderer would pose it, and then walked directly. Only the
 * transform is rebuilt here; the animation itself is still the mob's own, run by its own model.
 * <p>
 * One thing is left behind: a model that overrides {@code renderToBuffer} to wrap its parts in an
 * extra transform - Alex's Mobs shrinks a few of its babies that way - is walked from its parts
 * instead, so those mobs come out at their adult size.
 */
public class CitadelEntityModel<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>> extends SimpleEntityModel<Entity, RenderState, Model> {
    private final CitadelModelInstance<Entity, RenderState, Model> model;

    public CitadelEntityModel(Entity entity, CitadelModelInstance<Entity, RenderState, Model> model) {
        super(entity, model.renderer());
        this.model = model;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void render(PoseStack poseStack) {
        RenderState renderState = renderer.createRenderState();
        renderer.extractRenderState(entity, renderState, 0);
        ServerRenderStates.applyRotations(entity, renderState);
        walk.apply(renderState);

        if (!(renderer instanceof LivingEntityRenderer livingEntityRenderer)
            || !(renderState instanceof LivingEntityRenderState livingRenderState)) {
            return;
        }

        LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) livingEntityRenderer;

        // The same steps LivingEntityRenderer#submit takes before it hands a model over, which is
        // where a vanilla model would have picked its stack up from
        float scale = livingRenderState.scale;
        poseStack.scale(scale, scale, scale);
        ServerRenderStates.rotate(livingRenderState, poseStack, livingRenderState.bodyRot,
            () -> accessor.invokeSetupRotations(livingRenderState, poseStack, livingRenderState.bodyRot, scale));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        applyScale(livingEntityRenderer, livingRenderState, poseStack, accessor);
        poseStack.translate(0.0F, -1.501F, 0.0F);

        // Asked for once and used for both posing and drawing. A renderer that keeps several models
        // picks between them here, and posing one while walking another is what left mobs in their
        // bind pose wearing the wrong model's parts
        Object currentModel = livingEntityRenderer.getModel();
        ((net.minecraft.client.model.EntityModel) currentModel).setupAnim(renderState);

        CitadelModels.Resolved resolved = CitadelModels.get(currentModel);
        List<CitadelPart> roots = resolved != null ? resolved.roots() : model.roots();
        String layer = resolved != null ? resolved.layer() : model.layer();

        Identifier resolvedTexture = model.texture();
        boolean resolvedTranslucent = false;
        try {
            boolean bodyVisible = accessor.invokeIsBodyVisible(livingRenderState);
            RenderType renderType = accessor.invokeGetRenderType(
                livingRenderState,
                bodyVisible,
                !bodyVisible && !livingRenderState.isInvisibleToPlayer,
                livingRenderState.appearsGlowing()
            );

            // No render type at all means the mob is not being drawn this tick, which the trailing
            // sweep in onTick turns into every one of its elements going away.
            if (renderType == null) {
                activeElementIndex = 0;
                return;
            }

            RenderSetup renderSetup = ((RenderTypeAccessor) renderType).getState();
            RenderPipeline pipeline = ((RenderSetupAccessor) (Object) renderSetup).polymer_patcher$pipeline();
            resolvedTranslucent = RenderSetups.isTranslucent(pipeline);
            resolvedTexture = texture(renderSetup);
        } catch (ClassCastException ignored) {
            // Some mods create a server-side entity with the right EntityType but a sibling Java
            // class. Alex's Mobs does this for centipede tails: its renderer bridge casts that body to
            // EntityCentipedeTail solely while choosing a texture. A real client reconstructs a Tail;
            // this server renderer sees the Body. The dumped model already carries the same static
            // texture, so falling back to it draws the tail instead of abandoning it every tick.
        }

        int overlayCoords = LivingEntityRenderer.getOverlayCoords(livingRenderState, accessor.invokeGetWhiteOverlayProgress(livingRenderState));
        boolean redOverlay = (overlayCoords >> 16) == OverlayTexture.RED_OVERLAY_V;

        Identifier texture = resolvedTexture;
        boolean translucent = resolvedTranslucent;

        activeElementIndex = 0;
        for (CitadelPart root : roots) {
            root.visit(poseStack, (part, matrix) -> updateItemDisplayElement(
                createItemStack(model.modelPath(texture, layer, part.id), redOverlay, translucent), matrix
            ));
        }

        drawWhateverElseTheRendererDraws(renderState, currentModel, texture, redOverlay, translucent);

        Set<Identifier> layerTextures = submitLayers(livingEntityRenderer, livingRenderState, poseStack,
            texture, redOverlay, translucent);
        submitNamedOuterFallback(roots, layer, poseStack, texture,
            redOverlay, translucent, layerTextures);
    }
    /**
     * Draws whatever the renderer draws by hand, beside the model it was posed through.
     * <p>
     * A Citadel renderer is reproduced here rather than run, because its pose can be worked out from
     * the same steps the game takes. That covers the model it is holding and nothing else - and some
     * renderers draw more than that, straight into a buffer, in the middle of their own render method.
     * A murmur's neck and the head still on it, a raycat's glow, a gummy bear's innards: all drawn that
     * way, and all invisible for it.
     * <p>
     * So the renderer is also run, once, purely to see what else it draws. Everything it submits the
     * ordinary way is ignored - that has already been drawn above - and the model it was posed through
     * is skipped, so only the extras are taken, each at the pose the renderer gave it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void drawWhateverElseTheRendererDraws(
        RenderState renderState, Object alreadyDrawn, Identifier texture, boolean redOverlay, boolean translucent
    ) {
        if (!CitadelDraw.canCatch()) {
            return;
        }

        // Its own stack, started where the caller's began, because the renderer builds its pose from
        // scratch and the one above has already been turned and scaled
        PoseStack poseStack = new PoseStack();
        poseStack.translate(0.0F, -entity.getDimensions(entity.getPose()).height() / 2, 0.0F);

        ServerSubmitNodeCollector<Entity, RenderState, Model> collector = new ServerSubmitNodeCollector<>(this);
        collector.quiet = true;

        CitadelDraw.Sink previous = CitadelDraw.ACTIVE.get();
        CitadelDraw.ACTIVE.set(new CitadelDraw.Sink() {
            @Override
            public void drawn(Object drawn, PoseStack pose) {
                drawn(drawn, pose, null);
            }

            @Override
            public void drawn(Object drawn, PoseStack pose, RenderType renderType) {
                if (drawn == alreadyDrawn) {
                    return;
                }
                // What the renderer bound for this one, where it bound anything a model was written
                // under: a gummy bear's innards are not the colour of the bear, and the chest sitting
                // on a boat is not the boat
                Identifier wearing = me.drex.polymerpatcher.entity.render.RenderCaptureRules.outerTexture(
                    entity, boundTexture(renderType, texture));
                CitadelModels.Resolved extra = CitadelModels.resolve(drawn);
                String layer = extra.layer();
                for (CitadelPart root : extra.roots()) {
                    root.visit(pose, (part, matrix) -> updateItemDisplayElement(
                        createItemStack(model.modelPath(wearing, layer, part.id), redOverlay, translucent), matrix
                    ));
                }
            }

            @Override
            public void drewBlock(net.minecraft.world.level.block.state.BlockState state, PoseStack pose) {
                updateBlock(state, pose.last().pose());
            }
        });

        try {
            renderer.submit(renderState, poseStack, collector, DEFAULT_CAMERA_STATE);
        } catch (Throwable e) {
            // Costs the extras, not the mob, which has already been drawn by the time this runs
            PolymerPatcher.LOGGER.debug("Could not look for anything {} draws by hand", entity.getType(), e);
        } finally {
            if (previous == null) {
                CitadelDraw.ACTIVE.remove();
            } else {
                CitadelDraw.ACTIVE.set(previous);
            }
        }
    }



    /** Looked up once per renderer class; this is asked on every tick of every mob it draws. */
    private static final Map<Class<?>, java.lang.reflect.Method> LEGACY_SCALE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Stands for "this renderer has no older scaling method", since a map will not hold null. */
    private static final java.lang.reflect.Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Scales the mob, and lets the renderer decide which of its models it is about to draw.
     * <p>
     * Alex's Mobs leaves the modern scaling method empty and does the work in its own, which takes the
     * mob itself. That is not only about size: a catfish chooses <i>which of its three models</i> to
     * draw in there, from how big it is. Calling only the empty one meant the choice was never made, so
     * a large catfish was drawn with the small model's shape while being handed the large one's
     * texture - and the two are laid out differently, 64 pixels against 128, so it arrived scrambled
     * rather than simply missing.
     * <p>
     * A renderer that declares the older form is asked through that one; anything else is scaled the
     * usual way, so nothing is ever scaled twice.
     */
    private void applyScale(LivingEntityRenderer renderer, LivingEntityRenderState renderState, PoseStack poseStack, LivingEntityRendererAccessor accessor) {
        java.lang.reflect.Method legacy = LEGACY_SCALE.computeIfAbsent(renderer.getClass(), this::findLegacyScale);

        if (legacy != NONE) {
            try {
                legacy.invoke(renderer, entity, poseStack, 0.0F);
                return;
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not scale {} through its own method", renderer.getClass().getName(), e);
            }
        }

        accessor.invokeScale(renderState, poseStack);
    }

    private java.lang.reflect.Method findLegacyScale(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals("scale") || parameters.length != 3) continue;
                if (parameters[1] != PoseStack.class || parameters[2] != float.class) continue;
                if (!parameters[0].isInstance(entity)) continue;

                method.setAccessible(true);
                return method;
            }
        }

        return NONE;
    }

    /**
     * Draws the passes the renderer adds on top of its model - the item a raccoon is carrying, a mob's
     * eyes, anything else hung off it.
     * <p>
     * The ordinary path gets these for free, because the renderer draws them itself once it has handed
     * its model over. This one never asks the renderer to draw at all - it walks the model instead, for
     * the reasons in this class's own description - so nothing was ever asking the extra passes to
     * happen and mobs came out carrying nothing.
     * <p>
     * Each is asked separately. A pass that reaches for something a server has not got should cost that
     * one pass and leave the mob, and the rest of its passes, alone.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Set<Identifier> submitLayers(LivingEntityRenderer renderer, LivingEntityRenderState renderState,
                                         PoseStack poseStack, Identifier fallbackTexture,
                                         boolean redOverlay, boolean translucent) {
        Set<Identifier> capturedTextures = new HashSet<>();
        List<?> layers = ((LivingEntityRendererAccessor) renderer).polymer_patcher$layers();
        if (layers == null || layers.isEmpty()) {
            return capturedTextures;
        }

        ServerSubmitNodeCollector collector = new ServerSubmitNodeCollector<>(this);
        for (Object layer : layers) {
            CitadelDraw.Sink previous = CitadelDraw.ACTIVE.get();
            CitadelDraw.ACTIVE.set(new CitadelDraw.Sink() {
                @Override
                public void drawn(Object drawn, PoseStack pose) {
                    drawn(drawn, pose, null);
                }

                @Override
                public void drawn(Object drawn, PoseStack pose, @Nullable RenderType renderType) {
                    CitadelModels.Resolved extra = CitadelModels.resolve(drawn);
                    if (extra.roots().isEmpty()) {
                        return;
                    }
                    Identifier ordinary = boundTexture(renderType, fallbackTexture);
                    capturedTextures.add(ordinary);
                    Identifier wearing = me.drex.polymerpatcher.entity.render.RenderCaptureRules.outerTexture(
                        entity, ordinary);
                    for (CitadelPart root : extra.roots()) {
                        root.visit(pose, (part, matrix) -> updateItemDisplayElement(
                            createItemStack(model.modelPath(wearing, extra.layer(), part.id),
                                redOverlay, translucent), matrix
                        ));
                    }
                }

                @Override
                public void drewBlock(net.minecraft.world.level.block.state.BlockState state, PoseStack pose) {
                    updateBlock(state, pose.last().pose());
                }
            });
            try {
                ((net.minecraft.client.renderer.entity.layers.RenderLayer) layer)
                    .submit(poseStack, collector, LightCoordsUtil.pack(15, 15), renderState, renderState.yRot, renderState.xRot);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Failed to draw {} for {}", layer.getClass().getName(), entity.getType(), e);
            } finally {
                CitadelDraw.forgetBuffers();
                if (previous == null) {
                    CitadelDraw.ACTIVE.remove();
                } else {
                    CitadelDraw.ACTIVE.set(previous);
                }
            }
        }
        return capturedTextures;
    }

    /**
     * Last-resort second skin for slime-like Citadel mobs.
     *
     * <p>Several legacy compatibility renderers draw the same posed model once more using a texture
     * named {@code *_outer} or {@code *_outside}. Their adapter layer can consume that pass before the
     * server-side Citadel hook sees it, leaving a solid inner cube. The naming describes a rendering
     * capability rather than an entity id, so this covers Mimicubes, caramel/ferrous slimes and future
     * mods using the same convention. A texture successfully captured above is never repeated.</p>
     */
    private void submitNamedOuterFallback(List<CitadelPart> roots, String layer, PoseStack poseStack,
                                          Identifier baseTexture, boolean redOverlay, boolean translucent,
                                          Set<Identifier> capturedTextures) {
        Identifier entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (Identifier candidate : me.drex.polymerpatcher.entity.AnimatedEntities.writtenTextures(entityId)) {
            String name = candidate.getPath().substring(candidate.getPath().lastIndexOf('/') + 1)
                .toLowerCase(java.util.Locale.ROOT);
            if (candidate.equals(baseTexture) || capturedTextures.contains(candidate)
                || !(name.endsWith("_outer") || name.endsWith("_outside"))) {
                continue;
            }

            Identifier wearing = me.drex.polymerpatcher.entity.render.RenderCaptureRules.outerTexture(
                entity, candidate);

            for (CitadelPart root : roots) {
                root.visit(poseStack, (part, matrix) -> updateItemDisplayElement(
                    createItemStack(model.modelPath(wearing, layer, part.id), redOverlay, true), matrix
                ));
            }
        }
    }

    /**
     * The texture the mob is actually wearing this tick, named the way the generated models are filed.
     */
    private Identifier texture(RenderSetup renderSetup) {
        Map<String, RenderSetup.TextureBinding> textures = ((RenderSetupAccessor) (Object) renderSetup).polymer_patcher$textures();
        Identifier texture = RenderSetups.mainTexture(textures);
        return texture != null ? texture : model.texture();
    }
}
