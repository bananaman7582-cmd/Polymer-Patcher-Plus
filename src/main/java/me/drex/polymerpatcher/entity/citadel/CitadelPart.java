package me.drex.polymerpatcher.entity.citadel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * One part of a Citadel model, resolved once so that posing it costs no lookups.
 * <p>
 * The tree a Citadel model holds never changes after the model is built, so its shape - which parts
 * hang off which, and the boxes each one carries - is read once here and kept. What does change every
 * tick is the pose: where a part has been put, how far it has been turned, whether it is being shown
 * at all. Those few fields are looked up once as well and then only read, which is what keeps this
 * cheap enough to run for every part of every visible mob on every tick.
 */
public final class CitadelPart {
    private static final float PIXELS_PER_BLOCK = 16.0F;

    /**
     * What Citadel divides a part's scale by before handing it to a child, so a scaled part does not
     * drag everything below it along. Matching the floor keeps a part scaled to zero from turning the
     * child transform into infinities.
     */
    private static final float MIN_SCALE = 1.0E-4F;

    public final Object handle;

    /**
     * Which generated model stands for this part, or -1 when the part carries no boxes and so has
     * nothing to show. Parts like that are still walked, because their children hang off them.
     */
    public final int id;

    public final List<Object> cubes;
    public final List<CitadelPart> children;

    private final @Nullable Field showModel;
    private final @Nullable Field rotationPointX;
    private final @Nullable Field rotationPointY;
    private final @Nullable Field rotationPointZ;
    private final @Nullable Field rotateAngleX;
    private final @Nullable Field rotateAngleY;
    private final @Nullable Field rotateAngleZ;

    // Only an AdvancedModelBox carries a scale of its own; a plain BasicModelPart leaves these null.
    private final @Nullable Field scaleX;
    private final @Nullable Field scaleY;
    private final @Nullable Field scaleZ;
    private final @Nullable Field scaleChildren;

    private CitadelPart(Object handle, int id, List<Object> cubes, List<CitadelPart> children) {
        this.handle = handle;
        this.id = id;
        this.cubes = cubes;
        this.children = children;

        Class<?> type = handle.getClass();
        this.showModel = CitadelModel.findField(type, "showModel");
        this.rotationPointX = CitadelModel.findField(type, "rotationPointX");
        this.rotationPointY = CitadelModel.findField(type, "rotationPointY");
        this.rotationPointZ = CitadelModel.findField(type, "rotationPointZ");
        this.rotateAngleX = CitadelModel.findField(type, "rotateAngleX");
        this.rotateAngleY = CitadelModel.findField(type, "rotateAngleY");
        this.rotateAngleZ = CitadelModel.findField(type, "rotateAngleZ");
        this.scaleX = CitadelModel.findField(type, "scaleX");
        this.scaleY = CitadelModel.findField(type, "scaleY");
        this.scaleZ = CitadelModel.findField(type, "scaleZ");
        this.scaleChildren = CitadelModel.findField(type, "scaleChildren");
    }

    /**
     * Resolves a model's whole part tree, numbering the parts that have something to draw.
     * <p>
     * The numbering is the order the parts are walked in, which is the order they are drawn in, and it
     * has to hold between the resource pack being built and the mob being posed - the number is the
     * only thing tying a part to the model generated for it.
     */
    @NotNull
    public static List<CitadelPart> resolve(@NotNull Object model) {
        List<CitadelPart> roots = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] nextId = {0};

        for (Object root : CitadelModel.rootParts(model)) {
            CitadelPart part = build(root, seen, nextId);
            if (part != null) {
                roots.add(part);
            }
        }

        return roots;
    }

    @Nullable
    private static CitadelPart build(@NotNull Object handle, @NotNull Set<Object> seen, int[] nextId) {
        // A part listed below itself would otherwise walk forever
        if (!seen.add(handle)) {
            return null;
        }

        List<Object> cubes = CitadelModel.cubesOf(handle);
        int id = cubes.isEmpty() ? -1 : nextId[0]++;

        List<CitadelPart> children = new ArrayList<>();
        for (Object child : CitadelModel.childrenOf(handle)) {
            CitadelPart resolved = build(child, seen, nextId);
            if (resolved != null) {
                children.add(resolved);
            }
        }

        return new CitadelPart(handle, id, cubes, children);
    }

    /**
     * Every part below and including this one, in the order they are drawn.
     */
    public void flatten(@NotNull List<CitadelPart> into) {
        into.add(this);
        for (CitadelPart child : children) {
            child.flatten(into);
        }
    }

    /**
     * Walks the branch as {@code BasicModelPart#render} would, handing each part that draws something
     * the matrix it would have drawn under.
     */
    public void visit(@NotNull PoseStack poseStack, @NotNull Visitor visitor) {
        // A part the model has switched off, along with everything hanging from it. Citadel skips the
        // whole branch rather than just the boxes, so a model can carry pieces it almost never shows -
        // a joke hat, a second head for one variant - without them appearing on every mob
        if (!visible() || (cubes.isEmpty() && children.isEmpty())) {
            return;
        }

        poseStack.pushPose();
        translateAndRotate(poseStack);

        if (id >= 0) {
            visitor.visit(this, poseStack.last().pose());
        }

        unscaleChildren(poseStack);
        for (CitadelPart child : children) {
            child.visit(poseStack, visitor);
        }

        poseStack.popPose();
    }

    private boolean visible() {
        return !(read(showModel, 1) == 0);
    }

    private void translateAndRotate(@NotNull PoseStack poseStack) {
        poseStack.translate(
            read(rotationPointX, 0) / PIXELS_PER_BLOCK,
            read(rotationPointY, 0) / PIXELS_PER_BLOCK,
            read(rotationPointZ, 0) / PIXELS_PER_BLOCK
        );

        float z = read(rotateAngleZ, 0);
        if (z != 0) {
            poseStack.mulPose(Axis.ZP.rotation(z));
        }

        float y = read(rotateAngleY, 0);
        if (y != 0) {
            poseStack.mulPose(Axis.YP.rotation(y));
        }

        float x = read(rotateAngleX, 0);
        if (x != 0) {
            poseStack.mulPose(Axis.XP.rotation(x));
        }

        // Citadel resizes a part as it draws it - a gorilla's head is an ordinary box made bigger at
        // the last moment - so the scale belongs here rather than baked into the generated model
        if (scaleX != null) {
            poseStack.scale(read(scaleX, 1), read(scaleY, 1), read(scaleZ, 1));
        }
    }

    private void unscaleChildren(@NotNull PoseStack poseStack) {
        if (scaleX == null || read(scaleChildren, 0) != 0) {
            return;
        }

        poseStack.scale(
            1.0F / Math.max(read(scaleX, 1), MIN_SCALE),
            1.0F / Math.max(read(scaleY, 1), MIN_SCALE),
            1.0F / Math.max(read(scaleZ, 1), MIN_SCALE)
        );
    }

    /**
     * Reads a pose field, treating a boolean's {@code true} as 1 so flags and numbers share one path.
     */
    private float read(@Nullable Field field, float fallback) {
        if (field == null) {
            return fallback;
        }

        try {
            Object value = field.get(handle);
            if (value instanceof Number number) {
                return number.floatValue();
            }
            if (value instanceof Boolean flag) {
                return flag ? 1 : 0;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Left at whatever the caller would have used anyway
        }

        return fallback;
    }

    @FunctionalInterface
    public interface Visitor {
        void visit(CitadelPart part, Matrix4f matrix);
    }
}
