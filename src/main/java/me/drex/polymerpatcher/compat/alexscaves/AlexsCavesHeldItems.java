package me.drex.polymerpatcher.compat.alexscaves;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.component.DataComponents;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.item.HeldItemProbe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Item definitions for Alex's Caves' hand-drawn weapons that a client without the mod can follow.
 * <p>
 * The spears, the primitive club, the two staffs, the ortholance and the dreadbow are drawn by the mod's own
 * renderer, and it draws each of them two ways: in a hand it draws the 3D model, and anywhere else - the
 * hotbar, the ground, an item frame - it draws a flat picture kept as an item model of its own. The 3D shape
 * is baked by {@link me.drex.polymerpatcher.item.CitadelItemModels}, and pointed at that shape alone a
 * client without the mod drew the 3D model on the hotbar too, and held it wherever the mod's bare
 * transforms left it - which for a spear was lying across the player's head.
 * <p>
 * So the definition written here chooses the way the renderer does: the picture outside a hand, the shape
 * in one. And the shape is given the transforms the renderer would have ended up with, worked out from the
 * renderer's own steps rather than tuned by eye: the model's transform for that hand, then the renderer's
 * turns, moves and scales, folded into the one transform a model file can carry.
 */
public final class AlexsCavesHeldItems {

    private AlexsCavesHeldItems() {
    }

    private static final String NAMESPACE = "alexscaves";

    /** Most baked shapes are a quarter of their size, to fit inside a model's allowed element space. */
    private static final float DEFAULT_SHAPE_SCALE = 4.0F;
    private static final float PIXELS_PER_BLOCK = 16.0F;

    /** The furthest a model file may move or grow an item; the game clamps anything beyond. */
    private static final float MAX_TRANSLATION = 80.0F;
    private static final float MAX_SCALE = 4.0F;

    private static final String[] HANDS = {
        "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand"
    };

    private static final String[] ALL_CONTEXTS = {
        "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand",
        "head", "gui", "ground", "fixed"
    };

    /**
     * These are 3D in every context; the other renderer-owned items use an inventory sprite outside a hand.
     * <p>
     * The five after the first two have no inventory sprite in the mod at all - the model is the only
     * picture they have - which is how they are told apart without asking the renderer.
     */
    private static final Set<String> SHAPED_EVERYWHERE = Set.of(
        "resistor_shield", "galena_gauntlet",
        "shot_gum", "raygun", "gobthumper", "siren_light", "copper_valve", "beholder"
    );

    /** Flips the x axis: the game mirrors a left hand's transform this way as it applies it. */
    private static final Matrix4f MIRROR = new Matrix4f().scaling(-1, 1, 1);

    @FunctionalInterface
    private interface RendererSteps {
        /** What the renderer does to the pose between the model's transform and drawing the model. */
        void apply(Matrix4f pose, boolean firstPerson, boolean leftHand);
    }

    /**
     * Read off {@code ACItemstackRenderer#renderByItem} in Alex's Caves 1.0.10. Each of these first undoes the
     * half-block the game moves every item by, so that is left out here along with the game's own.
     */
    private static final Map<String, RendererSteps> STEPS = steps();

    private static Map<String, RendererSteps> steps() {
        Map<String, RendererSteps> steps = new java.util.LinkedHashMap<>();

        steps.put("limestone_spear", upsideDown(-0.85F, -0.1F, 0.5F, 0.75F, 0.75F, 0.75F));
        steps.put("extinction_spear", upsideDown(-0.85F, -0.1F, 0.5F, 0.75F, 0.75F, 0.75F));
        steps.put("frostmint_spear", upsideDown(-0.85F, -0.1F, 0.5F, 0.75F, 0.75F, 0.75F));
        steps.put("primitive_club", upsideDown(-1.15F, -0.1F, 0.1F, 0.8F, 0.8F, 0.8F));
        steps.put("sea_staff", upsideDown(-0.5F, 0.0F, 0.0F, 0.6F, 0.6F, 0.6F));
        steps.put("ortholance", upsideDown(-1.1F, 0.0F, 0.0F, 0.6F, 1.0F, 0.6F));
        steps.put("sugar_staff", upsideDown(-1.0F, 0.0F, 0.4F, 0.6F, 0.6F, 0.6F));

        // The only two that do not put the half block back before they draw, so it is carried here instead
        steps.put("galena_gauntlet", (pose, firstPerson, leftHand) ->
            halfBlock(pose).rotateX(radians(-90)).rotateY(radians(-180)));
        steps.put("resistor_shield", resistorShield(0.0F));
        steps.put("dreadbow", dreadbow(0.0F));

        // Five more the renderer draws that nobody had written down. Each begins from
        // translate(0.5, 1.5, 0.5) - the half block every item is moved by, and a whole one above it - and
        // turns over from there, which is what a model built as a mob needs. Left out, they were sent with
        // no transform at all and arrived at a quarter of their size: the shot gum was a trinket in the hand
        steps.put("siren_light", standingUp(0.0F, 0.0F));
        steps.put("copper_valve", standingUp(0.0F, 0.0F));
        // The special item renderer uses the same whole-block lift and 180-degree correction as the
        // valve. Without a named entry it fell through to the raw quarter-size captured shape and
        // appeared tiny and upside-down in hand.
        steps.put("beholder", standingUp(0.0F, 0.0F));
        steps.put("gobthumper", standingUp(0.0F, 0.0F));
        steps.put("shot_gum", standingUp(180.0F, 0.8F));
        steps.put("raygun", standingUp(180.0F, 0.9F));

        return Map.copyOf(steps);
    }

    /**
     * The steps the later half of the renderer shares: a whole block up, turned over, and for the two it
     * holds like a weapon, turned to face forward and made a little smaller.
     *
     * @param turn  degrees about the upright axis, or zero for none
     * @param scale the size it is drawn at, or zero for full size
     */
    private static RendererSteps standingUp(float turn, float scale) {
        return (pose, firstPerson, leftHand) -> {
            pose.translate(0, 1.0F, 0).rotateX(radians(-180));
            if (turn != 0.0F) {
                pose.rotateY(radians(turn));
            }
            if (scale != 0.0F) {
                pose.scale(scale);
            }
        };
    }

    /**
     * The dreadbow's steps, for a bow drawn this far.
     * <p>
     * The renderer draws the bow further back the further it is pulled, and bends the model to match. Both
     * are kept here: the model is posed and baked again at each of the three draws the mod itself changes
     * picture at, and the move that belongs with a draw is written into that shape's own transform.
     */
    private static RendererSteps dreadbow(float pull) {
        return (pose, firstPerson, leftHand) -> {
            if (firstPerson) {
                pose.translate(leftHand ? -0.1F : 0.1F, 0.1F, -0.1F).scale(0.5F).rotateX(radians(15));
            } else {
                pose.translate(leftHand ? 0.1F : -0.1F, -0.45F, 0.35F - pull * 0.3F)
                    .rotateY(radians(leftHand ? 7 : -7));
            }
        };
    }

    /**
     * The resistor shield's steps, for a shield that has been held up this long.
     * <p>
     * Read off the renderer: it works out {@code min(10, ticks) / 10}, snaps the shield out over the first
     * quarter of that, and rides a sine bump through the middle of it. At rest every one of those terms is
     * zero, which is why what was here before - the resting pose alone - was right as far as it went.
     */
    private static RendererSteps resistorShield(float raised) {
        float out = Math.min(raised * 4.0F, 1.0F);
        float bump = (float) Math.sin(raised * Math.PI);

        return (pose, firstPerson, leftHand) -> {
            halfBlock(pose).translate(0, 0.25F, 0.125F);
            float hand = leftHand ? -1.0F : 1.0F;

            if (firstPerson) {
                pose.translate(out * 0.2F * hand, bump, 0).rotateX(radians(out * -10.0F));
            } else {
                pose.translate(out * 0.4F * hand, raised * -0.1F, out * -0.2F)
                    .rotateZ(radians(out * 10.0F * hand))
                    .rotateY(radians(out * 80.0F * hand));
            }

            pose.rotateX(radians(-180));
        };
    }

    /**
     * An item the mod poses from how long it has been used, and what stands in for that.
     * <p>
     * A model file cannot bend a shape, but it can be told to use a different shape depending on how long
     * the item has been held down - which is exactly what a vanilla bow does, and exactly what all three of
     * these are driven by. So the mod's own model is posed and baked once per stage, the renderer's own
     * moves for that stage are folded into each one's transform, and the client picks between them.
     *
     * @param ticksToFull  the mod's own timer: forty ticks to a full draw, ten to a raised shield, five to a
     *                     charged gauntlet
     * @param stages       how far through the motion each baked shape is
     * @param thresholds   where the client changes from one to the next, in the same units
     * @param pose         how to put the mod's model into that stage
     * @param steps        the renderer's own moves at that stage
     */
    private record Animation(float ticksToFull, float[] stages, float[] thresholds,
                             ModelPose pose, java.util.function.Function<Float, RendererSteps> steps) {
    }

    @FunctionalInterface
    private interface ModelPose {
        void apply(Object model, float amount);
    }

    private static final Map<String, Animation> ANIMATIONS = Map.of(
        // Forty ticks to full, changing picture at a half and four fifths - the mod's own three
        "dreadbow", new Animation(40.0F, new float[]{0.25F, 0.65F, 1.0F}, new float[]{0.0F, 0.5F, 0.8F},
            AlexsCavesHeldItems::poseModel, AlexsCavesHeldItems::dreadbow),
        // Five ticks to a full charge, and the gauntlet is only bent by its own model
        "galena_gauntlet", new Animation(5.0F, new float[]{0.2F, 0.5F, 0.8F, 1.0F},
            new float[]{0.0F, 0.33F, 0.66F, 1.0F},
            AlexsCavesHeldItems::poseModel, amount -> STEPS.get("galena_gauntlet")),
        // Ten ticks, and more stages than the others need. The shield rides a sine bump a whole block
        // high through the middle of its raise - in first person that is most of the screen - so sampled
        // four times it spent a quarter of the motion parked at the top of the arc, out of view. Eight
        // stages pass through it instead of sitting in it
        "resistor_shield", new Animation(10.0F,
            new float[]{0.06F, 0.19F, 0.31F, 0.44F, 0.56F, 0.69F, 0.81F, 1.0F},
            new float[]{0.0F, 0.125F, 0.25F, 0.375F, 0.5F, 0.625F, 0.75F, 0.875F},
            AlexsCavesHeldItems::poseModel, AlexsCavesHeldItems::resistorShield)
    );

    /**
     * The half block the game moves an item by, which these two are drawn with still in place.
     * <p>
     * Every item transform ends by moving the item half a block back along each axis, so that a model drawn
     * from one corner ends up centred where it is held. Alex's Caves puts that back at the top of each of
     * its items - {@code poseStack.translate(0.5, 0.5, 0.5)} - and since the two cancel, both are left out
     * of the steps written here.
     * <p>
     * Two of its ten items do not put it back: the galena gauntlet begins at {@code translate(0, 0, 0)} and
     * the resistor shield at {@code translate(0, 0.25, 0.125)}. For those the move is still in place when
     * the model is drawn, and leaving it out of the steps is what put both of them half a block from where
     * they belong - far enough that the gauntlet sat half off the screen. They are the two that were
     * reported as being in the wrong place, and the eight that put it back are the eight that were not.
     */
    private static Matrix4f halfBlock(Matrix4f pose) {
        return pose.translate(-0.5F, -0.5F, -0.5F);
    }

    /** The steps most of them share: turned upside down, moved, and made smaller in first person. */
    private static RendererSteps upsideDown(float down, float back, float firstPersonUp,
                                            float firstPersonX, float firstPersonY, float firstPersonZ) {
        return (pose, firstPerson, leftHand) -> {
            pose.rotateX(radians(-180)).translate(0, down, back);
            if (firstPerson) {
                pose.translate(0, firstPersonUp, 0).scale(firstPersonX, firstPersonY, firstPersonZ);
            }
        };
    }

    /**
     * A definition that draws this item as the mod's renderer would, pointed at its baked shape - or null where
     * the item is not one of these, or something it needs is not in the pack.
     */
    public static @Nullable String definition(ResourcePackBuilder builder, String namespace, String itemPath, Identifier shape) {
        // The fallback below (a neutral renderer-owned shape restored from its quarter-size bake) is
        // useful to every Citadel item. Only the named poses are Alex's Caves facts; do not accidentally
        // apply one merely because another mod happens to call an item "dreadbow" or "raygun" too.
        boolean alexsCaves = namespace.equals(NAMESPACE);
        RendererSteps steps = alexsCaves ? STEPS.get(itemPath) : null;

        // A renderer-owned item nobody has worked out the steps for still has to be the right size. The
        // shape is baked at a quarter, and a definition is the only thing that puts that back - so without
        // one the item arrives in the hand at a quarter of its size, which is what happened to the shot gum,
        // the raygun, the gobthumper and the siren light. Given no steps, it is written with the mod's own
        // transforms and nothing else: the right size, in roughly the right place, rather than a trinket.
        if (steps == null) {
            if (builder.getDataOrSource("assets/" + namespace + "/models/item/" + itemPath + ".json") == null) {
                return null;
            }
            steps = (pose, firstPerson, leftHand) -> {
            };
        }

        boolean shapedEverywhere = alexsCaves && SHAPED_EVERYWHERE.contains(itemPath);
        String picture = namespace + ":item/" + itemPath + "_inventory";
        if (!shapedEverywhere && builder.getDataOrSource(modelPath(picture)) == null) {
            return null;
        }

        JsonObject root = readJson(builder, "assets/" + namespace + "/items/" + itemPath + ".json");
        JsonObject model = root == null ? null : object(root, "model");
        if (model == null) {
            return null;
        }

        // The mod's definition names the model whose transforms the renderer starts from: one at rest, and for
        // a spear or the ortholance another while it is being used - thrown back, or charged
        String restingBase = null;
        String usingBase = null;
        String type = string(model, "type");
        if ("minecraft:special".equals(type)) {
            restingBase = string(model, "base");
        } else if ("minecraft:range_dispatch".equals(type) && "alexscaves:legacy".equals(string(model, "property"))) {
            JsonObject fallback = object(model, "fallback");
            restingBase = fallback == null ? null : string(fallback, "base");
            JsonElement entries = model.get("entries");
            if (entries != null && entries.isJsonArray() && entries.getAsJsonArray().size() == 1
                && entries.getAsJsonArray().get(0).isJsonObject()) {
                JsonObject using = object(entries.getAsJsonArray().get(0).getAsJsonObject(), "model");
                usingBase = using == null ? null : string(using, "base");
            }
        }
        if (restingBase == null) {
            return null;
        }

        float shapeScale = alexsCaves ? shapeScale(itemPath) : DEFAULT_SHAPE_SCALE;
        if (shapedEverywhere) {
            JsonObject everywhere = fullShapeModel(builder, shape, itemPath, restingBase, steps, shapeScale);
            if (everywhere == null) {
                return null;
            }
            JsonObject definition = new JsonObject();
            definition.add("model", everywhere);
            return definition.toString();
        }

        JsonObject inHand = handModel(builder, shape, itemPath, "held", restingBase, steps, shapeScale);
        if (inHand == null) {
            return null;
        }
        JsonObject animated = animatedModel(builder, namespace, itemPath, restingBase, shapeScale, inHand, HANDS, null);
        if (animated != null) {
            inHand = animated;
        } else if (usingBase != null) {
            // The mod's second model is the one it draws while the item is being used, and the renderer
            // is only ever asked about an item at rest - so this one keeps the steps written for it
            JsonObject using = handModel(builder, shape, null, "using", usingBase, steps, shapeScale);
            if (using != null) {
                JsonObject condition = new JsonObject();
                condition.addProperty("type", "minecraft:condition");
                condition.addProperty("property", "minecraft:using_item");
                condition.add("on_true", using);
                condition.add("on_false", inHand);
                inHand = condition;
            }
        }

        JsonArray hands = new JsonArray();
        for (String hand : HANDS) {
            hands.add(hand);
        }
        JsonObject handCase = new JsonObject();
        handCase.add("when", hands);
        handCase.add("model", inHand);
        JsonArray cases = new JsonArray();
        cases.add(handCase);

        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        select.add("cases", cases);
        select.add("fallback", pictures(builder, namespace, itemPath, picture));

        JsonObject definition = new JsonObject();
        definition.add("model", select);
        return definition.toString();
    }

    /**
     * This item's shape at each stage of its motion, chosen by how long it has been used.
     * <p>
     * Forty ticks is a full draw on the bow, ten a raised shield, five a charged gauntlet. An enchantment
     * can shorten the bow's and a model file has no way of reading one, so an enchanted bow reaches its full
     * draw a little before it is shown as fully drawn - which is what a vanilla bow does with quick charge.
     *
     * @param colour which of the flattened colour passes to use, where the item has them
     */
    private static @Nullable JsonObject animatedModel(ResourcePackBuilder builder, String namespace, String itemPath,
                                                      String base, float shapeScale, JsonObject rest,
                                                      String[] contexts, @Nullable String colour) {
        JsonObject measured = measuredAnimation(builder, namespace, itemPath, base, shapeScale, rest, contexts, colour);
        if (measured != null) {
            return measured;
        }

        Animation animation = ANIMATIONS.get(itemPath);
        if (animation == null) {
            return null;
        }

        JsonArray entries = new JsonArray();
        JsonObject firstStage = null;

        for (int stage = 0; stage < animation.stages().length; stage++) {
            float amount = animation.stages()[stage];
            Identifier shape = me.drex.polymerpatcher.item.CitadelItemModels.bake(
                builder, namespace, itemPath, "_stage_" + stage, model -> animation.pose().apply(model, amount));
            Identifier worn = shape == null || colour == null ? shape : variantShape(builder, shape, colour);
            JsonObject model = worn == null ? null
                : transformedModel(builder, worn, "held", base, animation.steps().apply(amount), shapeScale, contexts);
            if (model == null) {
                PolymerPatcher.LOGGER.info("{}:{} will be held still; the shape for stage {} could not be built",
                    namespace, itemPath, stage);
                return null;
            }

            if (stage == 0) {
                firstStage = model;
                continue;
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("threshold", animation.thresholds()[stage]);
            entry.add("model", model);
            entries.add(entry);
        }

        return whileUsed(firstStage, entries, rest, animation.ticksToFull());
    }

    /** The most shapes a motion is written out as. Past this it is more resource pack than it is worth. */
    private static final int MOST_STAGES = 8;

    /** No steps at all, for a transform that comes from the renderer rather than from the table. */
    private static final RendererSteps NOTHING = (pose, firstPerson, leftHand) -> {
    };

    /**
     * The item's motion as its own renderer draws it, written out stage by stage.
     * <p>
     * Nothing about the item is known here beyond its name: how long it moves for, what it looks like at
     * each moment of that, and where the renderer puts it at each moment are all measured by holding it
     * down and watching - see {@link me.drex.polymerpatcher.item.HeldItemMotion}. An item whose motion
     * has no end that can be seen from outside the mod is left to whatever was written for it by hand.
     */
    private static @Nullable JsonObject measuredAnimation(ResourcePackBuilder builder, String namespace,
                                                          String itemPath, String base, float shapeScale,
                                                          JsonObject rest, String[] contexts,
                                                          @Nullable String colour) {
        if (!namespace.equals(NAMESPACE)) {
            return null;
        }

        var motion = motionOf(itemPath);
        ItemStack stack = motion == null ? null : stackOf(itemPath);
        if (motion == null || stack == null) {
            return null;
        }

        var stages = me.drex.polymerpatcher.item.HeldItemMotion.stages(motion, MOST_STAGES);
        if (stages.size() < 2) {
            return null;
        }

        JsonArray entries = new JsonArray();
        List<JsonObject> stageModels = new ArrayList<>();
        List<Float> stageThresholds = new ArrayList<>();
        JsonObject firstStage = null;

        for (int stage = 0; stage < stages.size(); stage++) {
            var frame = stages.get(stage);

            // Drawn again and written out there and then. These models are kept one per class and posed
            // in place, so the next thing to draw one moves the same object out from under this
            Object posed = me.drex.polymerpatcher.item.HeldItemMotion.again(
                namespace, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, motion, frame);
            Identifier shape = posed == null ? null : me.drex.polymerpatcher.item.CitadelItemModels.bakeAsDrawn(
                builder, namespace, itemPath, "_stage_" + stage, posed);
            Identifier worn = shape == null || colour == null ? shape : variantShape(builder, shape, colour);
            JsonObject model = worn == null ? null : transformedModel(builder, worn, "held", base, NOTHING,
                shapeScale, contexts, new Held(itemPath, motion.key(), frame.ticks()));

            if (model == null) {
                PolymerPatcher.LOGGER.info("{}:{} will be held still; its shape {} tick(s) in could not be built",
                    namespace, itemPath, frame.ticks());
                return null;
            }

            if (stage == 0) {
                firstStage = model;
            } else {
                JsonObject entry = new JsonObject();
                entry.addProperty("threshold", frame.ticks() / (float) motion.ticks());
                entry.add("model", model);
                entries.add(entry);
            }
            stageModels.add(model);
            stageThresholds.add(frame.ticks() / (float) motion.ticks());
        }

        if (itemPath.equals("shot_gum")) {
            return damageDriven(stageModels, stageThresholds, rest, true);
        }
        if (itemPath.equals("raygun")) {
            return damageDriven(stageModels, stageThresholds, rest, false);
        }
        return whileUsed(firstStage, entries, rest, motion.ticks());
    }

    /** Renderer state projected onto DAMAGE, which vanilla deliberately excludes from re-equip bobbing. */
    private static JsonObject damageDriven(List<JsonObject> models, List<Float> thresholds,
                                           JsonObject rest, boolean returnsToRest) {
        JsonArray entries = new JsonArray();
        for (int index = 0; index < models.size(); index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("threshold", thresholds.get(index) * (returnsToRest ? 0.5F : 1.0F));
            entry.add("model", models.get(index));
            entries.add(entry);
        }
        if (returnsToRest) {
            for (int index = models.size() - 2; index >= 0; index--) {
                JsonObject entry = new JsonObject();
                entry.addProperty("threshold", 1.0F - thresholds.get(index) * 0.5F);
                entry.add("model", models.get(index));
                entries.add(entry);
            }
        }
        JsonObject finalRest = new JsonObject();
        finalRest.addProperty("threshold", 1.0F);
        finalRest.add("model", rest);
        entries.add(finalRest);

        JsonObject range = new JsonObject();
        range.addProperty("type", "minecraft:range_dispatch");
        range.addProperty("property", "minecraft:damage");
        range.add("fallback", rest);
        range.add("entries", entries);
        return range;
    }

    /**
     * Puts a model into one stage of its motion, the way the mod's renderer does before it draws it.
     * <p>
     * All three of these take the same shape of call - the entity, how far through the motion it is, a
     * second number of the mod's own, and three it ignores - so one way of making it covers all of them.
     */
    private static void poseModel(Object model, float amount) {
        for (java.lang.reflect.Method method : model.getClass().getMethods()) {
            if (!method.getName().equals("setupAnim") || method.getParameterCount() != 6
                || method.getParameterTypes()[1] != float.class) {
                continue;
            }

            try {
                method.invoke(model, null, amount, 0.0F, 0.0F, 0.0F, 0.0F);
                return;
            } catch (Throwable e) {
                throw new IllegalStateException("The model would not be posed", e);
            }
        }

        throw new IllegalStateException("The model has no way of being posed");
    }

    /**
     * The picture shown outside a hand. The mod changes it as the bow is drawn, and so does this.
     */
    private static JsonObject pictures(ResourcePackBuilder builder, String namespace, String itemPath, String picture) {
        Animation animation = ANIMATIONS.get(itemPath);
        if (animation == null || !itemPath.equals("dreadbow")) {
            return reference(picture);
        }

        JsonArray entries = new JsonArray();
        JsonObject firstStage = null;

        for (int stage = 0; stage < animation.stages().length; stage++) {
            String drawn = namespace + ":item/" + itemPath + "_pulling_" + stage + "_inventory";
            if (builder.getDataOrSource(modelPath(drawn)) == null) {
                return reference(picture);
            }

            if (stage == 0) {
                firstStage = reference(drawn);
                continue;
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("threshold", animation.thresholds()[stage]);
            entry.add("model", reference(drawn));
            entries.add(entry);
        }

        return whileUsed(firstStage, entries, reference(picture), animation.ticksToFull());
    }

    /**
     * One of the posed models while the item is being used, and the resting one the rest of the time.
     * Written the way a vanilla bow's own definition is, so a client follows it the same way.
     */
    private static JsonObject whileUsed(JsonObject firstStage, JsonArray entries, JsonObject rest, float ticksToFull) {
        JsonObject range = new JsonObject();
        range.addProperty("type", "minecraft:range_dispatch");
        range.addProperty("property", "minecraft:use_duration");
        range.addProperty("scale", 1.0F / ticksToFull);
        range.add("fallback", firstStage);
        range.add("entries", entries);

        JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:condition");
        condition.addProperty("property", "minecraft:using_item");
        condition.add("on_true", range);
        condition.add("on_false", rest);
        return condition;
    }

    /**
     * Writes the baked shape again with the transforms it needs in each hand, starting from the named model's.
     */
    private static @Nullable JsonObject handModel(ResourcePackBuilder builder, Identifier shape,
                                                  @Nullable String itemPath, String suffix, String base,
                                                  RendererSteps steps, float shapeScale) {
        return transformedModel(builder, shape, suffix, base, steps, shapeScale, HANDS, atRest(itemPath));
    }

    /** The item as it is before anything is done with it, which is what most of these are written for. */
    private static @Nullable Held atRest(@Nullable String itemPath) {
        return itemPath == null ? null : new Held(itemPath, null, 0);
    }

    /** A 3D renderer-owned item, transformed in inventories and frames as well as hands. */
    private static @Nullable JsonObject fullShapeModel(ResourcePackBuilder builder, Identifier shape, String itemPath,
                                                       String base, RendererSteps steps, float shapeScale) {
        Identifier redShape = variantShape(builder, shape, "red");
        Identifier blueShape = variantShape(builder, shape, "blue");

        // Most of these have one picture and no second pass to choose between
        if (redShape.equals(shape) && blueShape.equals(shape)) {
            JsonObject still = transformedModel(builder, shape, "rendered", base, steps, shapeScale,
                ALL_CONTEXTS, atRest(itemPath));
            if (still == null) {
                return null;
            }

            // And it may still move while it is held - a gun winding its crank, for one. Nothing here
            // knows which of them do; the item is asked, and answers by moving or not moving
            JsonObject moving = animatedModel(builder, NAMESPACE, itemPath, base, shapeScale, still,
                ALL_CONTEXTS, null);
            return moving == null ? still : moving;
        }
        JsonObject red = transformedModel(builder, redShape, "rendered", base, steps, shapeScale, ALL_CONTEXTS, atRest(itemPath));
        JsonObject blue = transformedModel(builder, blueShape, "rendered", base, steps, shapeScale, ALL_CONTEXTS, atRest(itemPath));
        if (red == null || blue == null) {
            return transformedModel(builder, shape, "rendered", base, steps, shapeScale, ALL_CONTEXTS, atRest(itemPath));
        }

        if (itemPath.equals("galena_gauntlet")) {
            // The mod fades from scarlet to azure over the five ticks it takes to charge, and bends the
            // gauntlet as it goes. The colour cannot be faded by a model file, but the bend can be shown
            // stage by stage and the colour changed with it
            JsonObject charging = animatedModel(builder, NAMESPACE, itemPath, base, shapeScale, red, ALL_CONTEXTS, "blue");
            if (charging != null) {
                return charging;
            }

            JsonObject condition = new JsonObject();
            condition.addProperty("type", "minecraft:condition");
            condition.addProperty("property", "minecraft:using_item");
            condition.add("on_true", blue);
            condition.add("on_false", red);
            return condition;
        }

        // Resistor shield polarity is copied to custom-model-data on the stand-in stack below; the shield
        // itself snaps out and back over the ten ticks it is held up for, in whichever polarity it is in
        JsonObject raisedRed = animatedModel(builder, NAMESPACE, itemPath, base, shapeScale, red, ALL_CONTEXTS, "red");
        JsonObject raisedBlue = animatedModel(builder, NAMESPACE, itemPath, base, shapeScale, blue, ALL_CONTEXTS, "blue");

        JsonObject scarlet = new JsonObject();
        scarlet.addProperty("when", "scarlet");
        scarlet.add("model", raisedRed == null ? red : raisedRed);
        JsonArray cases = new JsonArray();
        cases.add(scarlet);

        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.addProperty("index", 0);
        select.add("cases", cases);
        select.add("fallback", raisedBlue == null ? blue : raisedBlue);
        return select;
    }

    private static Identifier variantShape(ResourcePackBuilder builder, Identifier shape, String color) {
        Identifier variant = Identifier.fromNamespaceAndPath(shape.getNamespace(), shape.getPath() + "_" + color);
        return builder.getDataOrSource(modelPath(variant.toString())) != null ? variant : shape;
    }

    private static @Nullable JsonObject transformedModel(ResourcePackBuilder builder, Identifier shape, String suffix,
                                                         String base, RendererSteps steps, float shapeScale,
                                                         String[] contexts) {
        return transformedModel(builder, shape, suffix, base, steps, shapeScale, contexts, null);
    }

    /**
     * The item a transform is being written for, at the moment of its motion it is being written for.
     *
     * @param itemPath the item, whose own renderer can be asked what it does with it instead of the
     *                 answer being taken from the table written by hand
     * @param key      the number on the item its motion is carried by, or null for an item at rest
     * @param ticks    how long it has been held by this moment
     */
    private record Held(String itemPath, @Nullable String key, int ticks) {
    }

    /**
     * @param held the item this is being written for and how far into its motion, or null for a pose the
     *             renderer would have to be holding the item to show
     */
    private static @Nullable JsonObject transformedModel(ResourcePackBuilder builder, Identifier shape, String suffix,
                                                         String base, RendererSteps steps, float shapeScale,
                                                         String[] contexts, @Nullable Held held) {
        JsonObject baseModel = readJson(builder, modelPath(base));
        JsonObject baseDisplay = baseModel == null ? null : object(baseModel, "display");

        JsonObject display = new JsonObject();
        for (String context : contexts) {
            JsonObject transform = transform(baseDisplay == null ? null : object(baseDisplay, context),
                rendererSteps(held, context, steps, context.startsWith("firstperson"), context.endsWith("lefthand")),
                context.endsWith("lefthand"), shapeScale);
            if (transform == null) {
                PolymerPatcher.LOGGER.warn("The {} transform for {} ({}) does not fit in a model file", context, shape, suffix);
                return null;
            }
            display.add(context, transform);
        }

        JsonObject model = new JsonObject();
        model.addProperty("parent", shape.toString());
        model.add("display", display);
        Identifier id = Identifier.fromNamespaceAndPath(shape.getNamespace(), shape.getPath() + "_" + suffix);
        builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json",
            model.toString().getBytes(StandardCharsets.UTF_8));
        return reference(id.toString());
    }

    /**
     * One hand's transform: what the model file gives that hand, then the renderer's steps, then the growth that
     * undoes the baked shape's quarter size - taken apart again into a move, a turn and a scale.
     */
    /**
     * What the renderer does between the model's own transform and drawing the model, measured from the
     * renderer itself where it will say and read from the table where it will not.
     * <p>
     * The measurement starts from an empty pose stack, so what comes back is everything the renderer did -
     * including the half block the game moves an item by before a special renderer runs, which eight of
     * Alex's Caves' ten items put back as their first step and two do not. What the steps here mean is the
     * same thing without that move, because the model written out is an ordinary one and the game moves
     * those by the same half block on its own. Hence the translate in front.
     */
    private static Matrix4f rendererSteps(@Nullable Held held, String context, RendererSteps steps,
                                          boolean firstPerson, boolean leftHand) {
        Matrix4f written = new Matrix4f();
        steps.apply(written, firstPerson, leftHand);

        Matrix4f measured = measure(held, context);
        if (measured == null) {
            return written;
        }

        Matrix4f asSteps = new Matrix4f().translate(-0.5F, -0.5F, -0.5F).mul(measured);

        // The measurement is what is used. Where an item also has an entry in the table the two are
        // compared, because fifteen entries read out of the mod's bytecode by hand, checked against a
        // build-time test of seventy-six matrices and then checked again by someone holding the thing are
        // worth keeping as a second opinion - all fifteen agreed to four decimal places the first time
        // this was run, which is what made it safe to prefer the measurement everywhere else.
        //
        // A disagreement is said out loud rather than hidden, because one of the two is then wrong and
        // which one it is cannot be decided from here
        if (held.ticks() == 0 && STEPS.containsKey(held.itemPath()) && !asSteps.equals(written, 1.0E-4F)) {
            PolymerPatcher.LOGGER.info("{}'s own renderer disagrees with the steps written for it ({}): it draws at "
                + "{}, the table says {}. Going with the renderer", held.itemPath(), context,
                describe(asSteps), describe(written));
        }
        return asSteps;
    }

    /** A matrix in the terms a model file uses, for saying what two of them disagree about. */
    private static String describe(Matrix4f pose) {
        Vector3f translation = pose.getTranslation(new Vector3f()).mul(PIXELS_PER_BLOCK);
        Vector3f scale = pose.getScale(new Vector3f());
        Vector3f rotation = new Vector3f();
        pose.get3x3(new Matrix3f()).scale(1 / scale.x, 1 / scale.y, 1 / scale.z).getEulerAnglesXYZ(rotation);
        return String.format(java.util.Locale.ROOT, "move %.2f %.2f %.2f, turn %.1f %.1f %.1f, size %.3f",
            translation.x, translation.y, translation.z,
            Math.toDegrees(rotation.x), Math.toDegrees(rotation.y), Math.toDegrees(rotation.z), scale.x);
    }

    /**
     * Runs the item's own renderer and takes the pose it had built by the moment it drew, or null where
     * it will not run, draws nothing, or the setting says not to ask.
     * <p>
     * Asked once per item and context and kept, because a single item is written out several times over -
     * once for each colour it comes in, and again for each stage of anything it does.
     */
    private static @Nullable Matrix4f measure(@Nullable Held held, String context) {
        if (held == null || !ConfigManager.config().resources.measureHeldItemTransforms
            || !HeldItemProbe.canProbe(NAMESPACE)) {
            return null;
        }

        ItemDisplayContext display = CONTEXTS.get(context);
        if (display == null) {
            return null;
        }

        Matrix4f measured = MEASURED.computeIfAbsent(held + " " + context, wanted -> {
            ItemStack stack = stackOf(held.itemPath());
            if (stack == null) {
                return NOT_DRAWN;
            }

            HeldItemProbe.Drawn drawn = held.key() == null || held.ticks() == 0
                ? HeldItemProbe.probe(NAMESPACE, stack, display)
                : HeldItemProbe.drawnWith(NAMESPACE, stack, display, held.key(), held.ticks());
            return drawn == null ? NOT_DRAWN : drawn.pose();
        });
        return measured == NOT_DRAWN ? null : measured;
    }

    /** One of this mod's items, or null where it is not registered. */
    private static @Nullable ItemStack stackOf(String itemPath) {
        Item item = BuiltInRegistries.ITEM
            .getOptional(Identifier.fromNamespaceAndPath(NAMESPACE, itemPath)).orElse(null);
        return item == null ? null : new ItemStack(item);
    }

    /**
     * How this item moves while it is held, measured once and kept.
     * <p>
     * Measured in the right hand in third person, because the motion is the item's own: what changes
     * between one hand and the other, or between third person and first, is where the renderer puts it,
     * and that is measured separately for each of them anyway.
     */
    private static @Nullable me.drex.polymerpatcher.item.HeldItemMotion.Motion motionOf(String itemPath) {
        if (!ConfigManager.config().resources.measureHeldItemTransforms || !HeldItemProbe.canProbe(NAMESPACE)) {
            return null;
        }

        return MOTIONS.computeIfAbsent(itemPath, wanted -> {
            ItemStack stack = stackOf(wanted);
            var motion = stack == null ? null : me.drex.polymerpatcher.item.HeldItemMotion.measure(
                NAMESPACE, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            if (motion != null) {
                me.drex.polymerpatcher.item.HeldItemMotion.report(NAMESPACE, wanted, motion,
                    me.drex.polymerpatcher.item.HeldItemMotion.stages(motion, MOST_STAGES));
            }
            return java.util.Optional.ofNullable(motion);
        }).orElse(null);
    }

    private static final Map<String, java.util.Optional<me.drex.polymerpatcher.item.HeldItemMotion.Motion>> MOTIONS =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<String, Matrix4f> MEASURED = new java.util.concurrent.ConcurrentHashMap<>();


    /** Stands for "the renderer drew nothing for this", since a map will not hold null. */
    private static final Matrix4f NOT_DRAWN = new Matrix4f();

    /** The display contexts under the names a model file gives them. */
    private static final Map<String, ItemDisplayContext> CONTEXTS = Map.of(
        "thirdperson_righthand", ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
        "thirdperson_lefthand", ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
        "firstperson_righthand", ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
        "firstperson_lefthand", ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
        "head", ItemDisplayContext.HEAD,
        "gui", ItemDisplayContext.GUI,
        "ground", ItemDisplayContext.GROUND,
        "fixed", ItemDisplayContext.FIXED
    );

    private static @Nullable JsonObject transform(@Nullable JsonObject base, RendererSteps steps, boolean firstPerson,
                                                  boolean leftHand, float shapeScale) {
        Matrix4f steppedTo = new Matrix4f();
        steps.apply(steppedTo, firstPerson, leftHand);
        return transform(base, steppedTo, leftHand, shapeScale);
    }

    private static @Nullable JsonObject transform(@Nullable JsonObject base, Matrix4f steppedTo,
                                                  boolean leftHand, float shapeScale) {
        Vector3f rotation = vector(base, "rotation", 0);
        Matrix4f pose = new Matrix4f()
            .translate(vector(base, "translation", 0).div(PIXELS_PER_BLOCK))
            .rotate(new Quaternionf().rotationXYZ(radians(rotation.x), radians(rotation.y), radians(rotation.z)))
            .scale(vector(base, "scale", 1));

        Matrix4f rendererSteps = new Matrix4f(steppedTo);
        if (leftHand) {
            // The game mirrors a left hand's transform as it applies it - the model file's, and so the one written
            // here too - but not the renderer's steps, which come after. Mirroring those here is what makes the
            // game's mirroring of the whole come out as the renderer's
            rendererSteps = new Matrix4f(MIRROR).mul(rendererSteps).mul(MIRROR);
        }
        pose.mul(rendererSteps).scale(shapeScale);

        Vector3f translation = pose.getTranslation(new Vector3f()).mul(PIXELS_PER_BLOCK);
        Vector3f scale = pose.getScale(new Vector3f());
        if (Math.abs(translation.x) > MAX_TRANSLATION || Math.abs(translation.y) > MAX_TRANSLATION
            || Math.abs(translation.z) > MAX_TRANSLATION || scale.x > MAX_SCALE + 1.0E-3F
            || scale.y > MAX_SCALE + 1.0E-3F || scale.z > MAX_SCALE + 1.0E-3F) {
            return null;
        }

        JsonObject transform = new JsonObject();
        transform.add("rotation", array(eulerXYZ(pose.get3x3(new Matrix3f()).scale(1 / scale.x, 1 / scale.y, 1 / scale.z))));
        transform.add("translation", array(translation));
        transform.add("scale", array(scale));
        return transform;
    }

    /** Package-private seam used by the build-time matrix checker; it is not part of the mod API. */
    static @Nullable JsonObject transformForValidation(@Nullable JsonObject base, String itemPath,
                                                       boolean firstPerson, boolean leftHand) {
        RendererSteps steps = STEPS.get(itemPath);
        return steps == null ? null : transform(base, steps, firstPerson, leftHand, shapeScale(itemPath));
    }

    static float shapeScaleForValidation(String itemPath) {
        return shapeScale(itemPath);
    }

    /** Scale applied to the flattened Citadel geometry before it is written as vanilla elements. */
    public static float geometryScale(String namespace, String itemPath) {
        return namespace.equals(NAMESPACE) ? 1.0F / shapeScale(itemPath) : 1.0F / DEFAULT_SHAPE_SCALE;
    }

    private static float shapeScale(String itemPath) {
        // The resistor shield's first-person base transform is already 1.25x. A quarter-size bake
        // would need a 5x correction, but vanilla model files clamp scale at 4. Baking it at 1/3.2
        // keeps every transform exact and within the format's limit.
        return itemPath.equals("resistor_shield") ? 3.2F : DEFAULT_SHAPE_SCALE;
    }

    /**
     * Adds the coloured texture passes which Alex's Caves normally draws in Java.
     * <p>
     * Each pass is composited into one PNG before it reaches the client. Layering two identical
     * vanilla models would make their coplanar faces fight; flattening the pixels preserves the same
     * appearance without adding geometry or another draw call.
     */
    public static void addTextureVariants(ResourcePackBuilder builder, String namespace, String itemPath,
                                          JsonObject baseModel, Identifier modelId) {
        if (!namespace.equals(NAMESPACE) || !SHAPED_EVERYWHERE.contains(itemPath)) {
            return;
        }

        byte[] base = builder.getDataOrSource("assets/" + namespace + "/textures/entity/" + itemPath + ".png");
        if (base == null) {
            return;
        }

        Map<String, Identifier> textures = new java.util.HashMap<>();
        for (String color : List.of("red", "blue")) {
            byte[] overlay = builder.getDataOrSource(
                "assets/" + namespace + "/textures/entity/" + itemPath + "_" + color + ".png");
            byte[] combined = overlay == null ? null : compositePng(base, overlay);
            if (combined == null) {
                continue;
            }

            Identifier texture = PolymerPatcher.id("item_shape/" + namespace + "/" + itemPath + "_" + color);
            builder.addData(texturePath(texture), combined);
            // Nothing else in the pack mentions this picture, and a picture no model file in the pack asks
            // for by name is left out of the atlas - which is the same as having no picture at all
            me.drex.polymerpatcher.resources.ResourcePackGenerator.EXTRA_SPRITES.add(texture);
            textures.put(color, texture);

            JsonObject variant = baseModel.deepCopy();
            variant.getAsJsonObject("textures").addProperty("txt", texture.toString());
            Identifier variantId = Identifier.fromNamespaceAndPath(modelId.getNamespace(), modelId.getPath() + "_" + color);
            builder.addData(modelPath(variantId.toString()), variant.toString().getBytes(StandardCharsets.UTF_8));
        }

        String defaultColor = itemPath.equals("resistor_shield") ? "blue" : "red";
        Identifier defaultTexture = textures.get(defaultColor);
        if (defaultTexture != null) {
            baseModel.getAsJsonObject("textures").addProperty("txt", defaultTexture.toString());
        }
    }

    private static @Nullable byte[] compositePng(byte[] baseBytes, byte[] overlayBytes) {
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseBytes));
            BufferedImage overlay = ImageIO.read(new ByteArrayInputStream(overlayBytes));
            if (base == null || overlay == null || base.getWidth() != overlay.getWidth()
                || base.getHeight() != overlay.getHeight()) {
                return null;
            }

            BufferedImage combined = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = combined.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(base, 0, 0, null);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(overlay, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            return ImageIO.write(combined, "png", output) ? output.toByteArray() : null;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not combine an Alex's Caves item texture", e);
            return null;
        }
    }

    /** Package-private seam for the build-time image check. */
    static @Nullable byte[] compositePngForValidation(byte[] baseBytes, byte[] overlayBytes) {
        return compositePng(baseBytes, overlayBytes);
    }

    /** Copies the resistor shield's server-side polarity into a property vanilla item models can read. */
    public static void modifyItemStack(ItemStack out, ItemStack original) {
        Identifier id = BuiltInRegistries.ITEM.getKey(original.getItem());
        if (id == null || !id.getNamespace().equals(NAMESPACE)) {
            return;
        }

        CustomData data = original.get(DataComponents.CUSTOM_DATA);
        if (id.getPath().equals("shot_gum") || id.getPath().equals("raygun")) {
            net.minecraft.nbt.CompoundTag tag = data == null ? new net.minecraft.nbt.CompoundTag() : data.copyTag();
            int maximum;
            int phase;
            if (id.getPath().equals("shot_gum")) {
                int shoot = Math.clamp(tag.getIntOr("ShootTime", 0), 0, 5);
                boolean shooting = tag.getBooleanOr("Shooting", false);
                maximum = 10;
                phase = shoot == 0 ? 0 : shooting ? shoot : maximum - shoot;
            } else {
                maximum = 5;
                phase = Math.clamp(tag.getIntOr("UseTime", 0), 0, maximum);
            }
            out.set(DataComponents.MAX_DAMAGE, maximum);
            out.set(DataComponents.DAMAGE, Math.clamp(phase, 0, maximum));
            out.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
            return;
        }
        if (!id.getPath().equals("resistor_shield")) {
            return;
        }

        boolean scarlet = data != null && data.copyTag().getBooleanOr("Polarity", false);
        CustomModelData existing = out.get(DataComponents.CUSTOM_MODEL_DATA);
        List<Float> floats = existing == null ? List.of() : existing.floats();
        List<Boolean> flags = existing == null ? List.of() : existing.flags();
        List<Integer> colors = existing == null ? List.of() : existing.colors();
        List<String> strings = new ArrayList<>(existing == null ? List.of() : existing.strings());
        if (strings.isEmpty()) {
            strings.add(scarlet ? "scarlet" : "azure");
        } else {
            strings.set(0, scarlet ? "scarlet" : "azure");
        }
        out.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, flags, List.copyOf(strings), colors));
    }

    /**
     * The angles, in degrees, that the game turns an item by to give this rotation. It turns about x, then y,
     * then z, each about the axes already turned.
     */
    private static Vector3f eulerXYZ(Matrix3f rotation) {
        // Column-first naming: m20 is the third column's first row
        float sinY = Math.clamp(rotation.m20, -1.0F, 1.0F);
        float x;
        float y = (float) Math.asin(sinY);
        float z;
        if (Math.abs(sinY) < 0.9999F) {
            x = (float) Math.atan2(-rotation.m21, rotation.m22);
            z = (float) Math.atan2(-rotation.m10, rotation.m00);
        } else {
            // Turned a quarter about y, where the x and z turns are the same turn; all of it is given to x
            x = (float) Math.atan2(rotation.m12, rotation.m11);
            z = 0;
        }
        return new Vector3f((float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z));
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static JsonArray array(Vector3f vector) {
        JsonArray array = new JsonArray();
        array.add(round(vector.x));
        array.add(round(vector.y));
        array.add(round(vector.z));
        return array;
    }

    private static float round(float value) {
        float rounded = Math.round(value * 10000.0F) / 10000.0F;
        // No -0 in the file: it reads the same, but looks like a sign that means something
        return rounded == 0 ? 0 : rounded;
    }

    private static Vector3f vector(@Nullable JsonObject transform, String key, float fallback) {
        Vector3f vector = new Vector3f(fallback);
        JsonElement element = transform == null ? null : transform.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() == 3) {
            JsonArray array = element.getAsJsonArray();
            vector.set(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
        }
        return vector;
    }

    private static JsonObject reference(String model) {
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", model);
        return reference;
    }

    private static String modelPath(String model) {
        int colon = model.indexOf(':');
        String namespace = colon < 0 ? Identifier.DEFAULT_NAMESPACE : model.substring(0, colon);
        return "assets/" + namespace + "/models/" + model.substring(colon + 1) + ".json";
    }

    private static String texturePath(Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
    }

    private static @Nullable JsonObject readJson(ResourcePackBuilder builder, String path) {
        byte[] data = builder.getDataOrSource(path);
        if (data == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static @Nullable JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static @Nullable String string(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
