package me.drex.polymerpatcher.entity.citadel;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.world.item.ItemStack;
import me.drex.polymerpatcher.entity.SimpleEntityModel;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import me.drex.polymerpatcher.entity.render.ServerSubmitNodeCollector;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * Draws an entity whose renderer is not a living one, by letting that renderer draw it.
 * <p>
 * Most of what a mod adds is a mob, and a mob's renderer is built the way the game builds them - so
 * its pose can be reproduced step for step, which is what {@link CitadelEntityModel} does. A great
 * deal else is not a mob at all: a thrown spear, a worm's segment, an anchor on a chain, a submarine.
 * Those renderers answer to nothing in particular. Each turns and shifts its model however it likes,
 * and there are dozens of them.
 * <p>
 * Reproducing each by hand would be dozens of guesses, every one of them able to be subtly wrong and
 * none of them checkable from here. So none of it is guessed. The renderer is run exactly as it would
 * be, and the model is taken at the instant it would have been drawn - carrying whatever pose the
 * renderer had built by then, which is by definition the pose it meant.
 */
public class ForeignCitadelModel<Entity extends net.minecraft.world.entity.Entity, RenderState extends EntityRenderState, Model extends EntityModel<? super RenderState>>
    extends SimpleEntityModel<Entity, RenderState, Model> {

    private final @org.jetbrains.annotations.Nullable CitadelModelInstance<Entity, RenderState, Model> model;
    private final @org.jetbrains.annotations.Nullable Identifier texture;

    /**
     * The texture each model this renderer borrows from another is filed under, by its layer name. Anything
     * not listed is one of the renderer's own and wears {@link #texture}.
     */
    private final java.util.Map<String, Identifier> layerTextures;

    public ForeignCitadelModel(Entity entity, CitadelModelInstance<Entity, RenderState, Model> model) {
        this(entity, model, java.util.Map.of());
    }

    public ForeignCitadelModel(Entity entity, CitadelModelInstance<Entity, RenderState, Model> model, java.util.Map<String, Identifier> layerTextures) {
        super(entity, model.renderer());
        this.model = model;
        this.texture = model.texture();
        this.layerTextures = layerTextures;
        this.flat = null;
    }

    /** The picture to fall back on where a tick of the renderer draws nothing at all. */
    private final @org.jetbrains.annotations.Nullable Identifier flat;

    /**
     * For a renderer that keeps no Citadel model at all.
     * <p>
     * A good deal of what Alex's Caves throws around is drawn without a model of its own: a nuclear
     * bomb is a block with a colour over it, a teletor's weapon is the item it carries. Those were
     * dropped before they got here, because the search that runs first looks for a model and finds
     * none - so they arrived as nothing whatsoever.
     * <p>
     * There is still a renderer, and it still draws. Run it, and take the blocks and items it reaches
     * for; only its own models go unread, and it has none.
     */
    public ForeignCitadelModel(Entity entity, net.minecraft.client.renderer.entity.EntityRenderer<Entity, RenderState> renderer) {
        this(entity, renderer, null);
    }

    /**
     * The same, knowing which picture to fall back on where the renderer draws by hand and this sees
     * nothing at all of it.
     */
    public ForeignCitadelModel(Entity entity, net.minecraft.client.renderer.entity.EntityRenderer<Entity, RenderState> renderer,
                               @org.jetbrains.annotations.Nullable Identifier flat) {
        super(entity, renderer);
        this.model = null;
        this.texture = null;
        this.layerTextures = java.util.Map.of();
        this.flat = flat;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void render(PoseStack poseStack) {
        RenderState renderState = renderer.createRenderState();
        renderer.extractRenderState(entity, renderState, 0);
        ServerRenderStates.applyRotations(entity, renderState);

        ServerSubmitNodeCollector<Entity, RenderState, Model> nodeCollector = new ServerSubmitNodeCollector<>(this);
        activeElementIndex = 0;
        taken.clear();

        CitadelDraw.Sink previous = CitadelDraw.ACTIVE.get();
        CitadelDraw.ACTIVE.set(new CitadelDraw.Sink() {
            @Override
            public void drawn(Object drawn, PoseStack pose) {
                ForeignCitadelModel.this.drawn(drawn, pose, null);
            }

            @Override
            public void drawn(Object drawn, PoseStack pose, @org.jetbrains.annotations.Nullable net.minecraft.client.renderer.rendertype.RenderType renderType) {
                ForeignCitadelModel.this.drawn(drawn, pose, renderType);
            }

            @Override
            public void drewBox(Object box, PoseStack pose, @org.jetbrains.annotations.Nullable net.minecraft.client.renderer.rendertype.RenderType renderType) {
                ForeignCitadelModel.this.drewBox(box, pose, renderType);
            }

            @Override
            public void drewBlock(net.minecraft.world.level.block.state.BlockState state, PoseStack pose) {
                updateBlock(state, pose.last().pose());
            }

            @Override
            public void drewItem(net.minecraft.world.item.ItemStack stack, PoseStack pose) {
                stack = me.drex.polymerpatcher.entity.render.RenderCaptureRules.item(entity, stack);
                ItemDisplayElement element = updateItemDisplayElement(stack, pose.last().pose());

                // A thrown egg, a snowball, a scoop of ice cream: the renderer turns these to face the
                // camera, and it does that from the camera itself, which on a server is a fixed direction
                // pointing nowhere in particular. The client can do the turning instead - that is what a
                // centred billboard is - so it is asked to
                if (renderer instanceof net.minecraft.client.renderer.entity.ThrownItemRenderer) {
                    element.setBillboardMode(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
                }
            }
        });
        try {
            renderer.submit(renderState, poseStack, nodeCollector, DEFAULT_CAMERA_STATE);
        } catch (Throwable e) {
            // One entity that will not draw is one entity missing, where letting it out of here would
            // stop every element this holder was about to place
            PolymerPatcher.LOGGER.debug("Could not run the renderer for {}", entity.getType(), e);
        } finally {
            if (previous == null) {
                CitadelDraw.ACTIVE.remove();
            } else {
                CitadelDraw.ACTIVE.set(previous);
            }
        }

        drawAFlatSquareIfNothingElseWasDrawn();
    }

    /** Pieces already placed this pass, so that a piece and its own children are not placed twice. */
    private final java.util.Set<Object> taken =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * One piece of a model, drawn without the rest of it.
     * <p>
     * A renderer that wants a head and no body reaches past the model and draws the head's own box.
     * Alex's Caves' dinosaur spirit is exactly that - a dinosaur's neck and head, ripped out by the
     * extinction spear - and two of its three kinds are drawn this way, so they arrived as nothing.
     * <p>
     * A piece knows the model it belongs to, and that is enough: the model says where its pieces are
     * written and this one says which of them it is. Everything hanging from it is drawn by the piece
     * itself a moment later, so the whole branch is marked as done before those arrive.
     */
    private void drewBox(Object box, PoseStack poseStack, @org.jetbrains.annotations.Nullable net.minecraft.client.renderer.rendertype.RenderType renderType) {
        if (me.drex.polymerpatcher.entity.render.RenderCaptureRules.skipBox(entity, renderType)) {
            return;
        }

        if (model == null || !taken.add(box)) {
            return;
        }

        CitadelModels.Resolved resolved = CitadelModels.get(CitadelModels.modelOf(box));
        CitadelPart part = resolved == null ? null : partOf(resolved.roots(), box);
        if (part == null) {
            return;
        }

        markTaken(part);

        Identifier wearing = layerTextures.getOrDefault(resolved.layer(), texture);
        Identifier bound = boundTexture(renderType, wearing);
        final Identifier drawnTexture = me.drex.polymerpatcher.entity.render.RenderCaptureRules.outerTexture(entity,
            me.drex.polymerpatcher.entity.render.RenderCaptureRules.texture(renderType, bound));
        String layer = resolved.layer();
        part.visit(poseStack, (piece, matrix) -> updateItemDisplayElement(
            createItemStack(model.modelPath(drawnTexture, layer, piece.id), false, false), matrix
        ));
    }

    private static @org.jetbrains.annotations.Nullable CitadelPart partOf(java.util.List<CitadelPart> parts, Object box) {
        for (CitadelPart part : parts) {
            if (part.handle == box) {
                return part;
            }
            CitadelPart found = partOf(part.children, box);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void markTaken(CitadelPart part) {
        taken.add(part.handle);
        for (CitadelPart child : part.children) {
            markTaken(child);
        }
    }

    /**
     * A last resort for a renderer that draws by hand.
     * <p>
     * Everything this mod watches for - a model, a block, an item - has come and gone by here. A renderer
     * that pushed its own vertices straight into a buffer has left nothing behind, and this is the only
     * point at which that is known: after running it and finding nothing. What it draws is a square facing
     * the viewer wearing its own picture, so that is what is put there.
     */
    private void drawAFlatSquareIfNothingElseWasDrawn() {
        if (activeElementIndex > 0 || flat == null) {
            return;
        }

        // Which of its pictures this one is wearing, asked of the renderer itself - a gumball comes in
        // thirteen colours and the entity is the only thing that knows which
        Identifier wearing = askedFor();
        if (!me.drex.polymerpatcher.entity.FlatEntityModels.has(wearing)) {
            wearing = flat;
        }
        if (wearing != null) {
            wearing = me.drex.polymerpatcher.entity.render.RenderCaptureRules.flatTexture(entity, wearing);
        }
        if (!me.drex.polymerpatcher.entity.FlatEntityModels.has(wearing)) {
            return;
        }
        final Identifier worn = wearing;

        float size = Math.max(entity.getDimensions(entity.getPose()).width(), 0.1F);
        org.joml.Matrix4f matrix = new org.joml.Matrix4f()
            .translate(0, entity.getDimensions(entity.getPose()).height() / 2, 0)
            .scale(size);
        me.drex.polymerpatcher.entity.render.RenderCaptureRules.flatTransform(entity, matrix);

        ItemStack stack = me.drex.polymerpatcher.entity.FlatEntityModels.stackFor(worn);
        if (stack == null) {
            return;
        }

        ItemDisplayElement element = updateItemDisplayElement(stack, matrix);
        element.setItemDisplayContext(net.minecraft.world.item.ItemDisplayContext.FIXED);
        element.setBillboardMode(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
    }

    /**
     * The picture this renderer says <em>this</em> entity is wearing, where a model was written under
     * it, and null otherwise - which is every renderer that dresses all of its entities the same.
     */
    private @org.jetbrains.annotations.Nullable Identifier wornByThisOne() {
        Identifier asked = askedFor();
        if (asked == null) {
            return null;
        }

        Identifier sprite = me.drex.polymerpatcher.entity.AnimatedEntities.spriteForm(asked);
        return me.drex.polymerpatcher.entity.AnimatedEntities.writtenTextures(
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).contains(sprite)
            ? sprite : null;
    }

    /**
     * The picture this renderer says this entity is wearing, or null where it will not say.
     * <p>
     * Every renderer has to name its own texture somewhere, and the older shape of the method takes the
     * entity rather than a render state - which is the shape the mods that draw by hand still use.
     */
    private @org.jetbrains.annotations.Nullable Identifier askedFor() {
        java.lang.reflect.Method named = TEXTURE_METHODS.computeIfAbsent(renderer.getClass(), type -> {
            for (java.lang.reflect.Method method : type.getMethods()) {
                if (method.getName().equals("getTextureLocation") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(entity.getClass())
                    && Identifier.class.equals(method.getReturnType())) {
                    return method;
                }
            }
            return NO_TEXTURE_METHOD;
        });

        if (named == NO_TEXTURE_METHOD) {
            return null;
        }

        try {
            return named.invoke(renderer, entity) instanceof Identifier named2 ? named2 : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Method> TEXTURE_METHODS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Stands for "this renderer will not name a texture", since a map will not hold null. */
    private static final java.lang.reflect.Method NO_TEXTURE_METHOD;

    static {
        try {
            NO_TEXTURE_METHOD = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * One model, at the pose its renderer had built when it went to draw it.
     * <p>
     * Called as many times as the renderer draws - a renderer that draws two models sends two, and
     * both are kept, because the elements are numbered straight on from wherever the last one left.
     */
    private void drawn(Object drawn, PoseStack poseStack,
                       @org.jetbrains.annotations.Nullable net.minecraft.client.renderer.rendertype.RenderType renderType) {
        // Nothing of this renderer's own was written to the pack, so there is no model to point an
        // element at. What it draws of blocks and items still lands, which is the whole of what these
        // renderers draw
        if (model == null || texture == null) {
            return;
        }

        CitadelModels.Resolved resolved = CitadelModels.resolve(drawn);
        if (resolved.roots().isEmpty()) {
            return;
        }

        String layer = resolved.layer();
        // A model borrowed from another renderer is filed under the texture that renderer binds for it,
        // not this renderer's own - a tendon's neck wears the murmur's skin, not the claw's
        Identifier filed = layerTextures.getOrDefault(layer, texture);

        // And where the renderer dresses each one differently, it is asked about this one. A gum worm's
        // segments are banded: each picks its skin from its own position down the body, three of them
        // cycling. Filed under the one texture the whole kind was registered with, every segment of
        // every worm came out the same colour
        Identifier worn = layerTextures.containsKey(layer) ? null : wornByThisOne();
        Identifier bound = worn == null ? boundTexture(renderType, filed) : worn;
        final Identifier drawnTexture = me.drex.polymerpatcher.entity.render.RenderCaptureRules.outerTexture(entity,
            me.drex.polymerpatcher.entity.render.RenderCaptureRules.texture(renderType, bound));
        for (CitadelPart root : resolved.roots()) {
            root.visit(poseStack, (part, matrix) -> updateItemDisplayElement(
                createItemStack(model.modelPath(drawnTexture, layer, part.id), false, false), matrix
            ));
        }

        // Shader-only shells never call Citadel's model draw hook. Compat may name that missing
        // texture while the generic path repeats the same already-resolved geometry for it.
        for (Identifier extraTexture :
            me.drex.polymerpatcher.entity.render.RenderCaptureRules.extraModelTextures(entity, bound)) {
            for (CitadelPart root : resolved.roots()) {
                root.visit(poseStack, (part, matrix) -> updateItemDisplayElement(
                    createItemStack(model.modelPath(extraTexture, layer, part.id), false, true), matrix
                ));
            }
        }
    }
}
