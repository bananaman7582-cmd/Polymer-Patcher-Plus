package me.drex.polymerpatcher.entity.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One bone of a GeckoLib model, resolved once so that posing it costs no lookups.
 * <p>
 * The counterpart to {@link me.drex.polymerpatcher.entity.citadel.CitadelPart}, and the same idea: the
 * shape of the model never changes after it is baked, so it is read once and kept, and only the pose
 * is asked for again every tick.
 * <p>
 * What differs is that a GeckoLib box carries a rotation of its own. An item model can only hold a box
 * square to its own axes, so a rotated box cannot be baked into one along with its unrotated
 * neighbours - it gets its own display, turned by the matrix instead. Boxes that are not rotated all
 * share a single one, which is nearly always all of them.
 */
public final class GeckoLibBone {

    public final Object handle;
    public final List<Piece> pieces;
    public final List<GeckoLibBone> children;
    /** The bone's name in the model file, which is how render layers name the bone they hang things on. */
    public final String name;
    /** Where the bone turns about, in blocks - where an item held on it is held. */
    public final float pivotX;
    public final float pivotY;
    public final float pivotZ;

    private GeckoLibBone(Object handle, List<Piece> pieces, List<GeckoLibBone> children) {
        this.handle = handle;
        this.pieces = pieces;
        this.children = children;
        this.name = String.valueOf(read(handle, "name"));
        this.pivotX = read(handle, "pivotX") instanceof Float f ? f / 16.0F : 0.0F;
        this.pivotY = read(handle, "pivotY") instanceof Float f ? f / 16.0F : 0.0F;
        this.pivotZ = read(handle, "pivotZ") instanceof Float f ? f / 16.0F : 0.0F;
    }

    @Nullable
    private static Object read(Object handle, String method) {
        try {
            return handle.getClass().getMethod(method).invoke(handle);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * A group of this bone's boxes that can be drawn as one model, and the box whose pivot and
     * rotation they share - null when they are not rotated at all and need no transform.
     */
    public record Piece(int id, List<Object> cubes, @Nullable Object transform) {
    }

    /**
     * Resolves a baked model's whole bone tree, numbering the pieces that have something to draw.
     * <p>
     * The numbering is the order the bones are walked in, and it has to hold between the resource pack
     * being built and the mob being posed - the number is the only thing tying a piece to the model
     * generated for it.
     */
    @NotNull
    public static List<GeckoLibBone> resolve(@NotNull Object renderer, @NotNull Object baked) {
        List<GeckoLibBone> roots = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] nextId = {0};

        for (Object bone : GeckoLibModel.topLevelBones(renderer, baked)) {
            GeckoLibBone resolved = build(renderer, bone, seen, nextId);
            if (resolved != null) {
                roots.add(resolved);
            }
        }

        return roots;
    }

    @Nullable
    private static GeckoLibBone build(@NotNull Object renderer, @NotNull Object handle, @NotNull Set<Object> seen, int[] nextId) {
        // A bone listed below itself would otherwise walk forever
        if (!seen.add(handle)) {
            return null;
        }

        List<Piece> pieces = new ArrayList<>();
        // Insertion-ordered so the numbering is the same every time the model is walked, which is what
        // lets the pack generated on one run match the poses sent on the next
        Map<String, List<Object>> groups = new LinkedHashMap<>();
        Map<String, Object> transforms = new LinkedHashMap<>();

        for (Object cube : GeckoLibModel.cubesOf(renderer, handle)) {
            Vec3 rotation = GeckoLibModel.rotationOf(renderer, cube);
            boolean turned = rotation.lengthSqr() != 0;
            // Every unturned box in a bone draws in the same place, so they share one model; a turned
            // one is only ever grouped with itself, since its pivot is its own
            String key = turned ? "r" + System.identityHashCode(cube) : "flat";

            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cube);
            if (turned) {
                transforms.put(key, cube);
            }
        }

        for (Map.Entry<String, List<Object>> group : groups.entrySet()) {
            pieces.add(new Piece(nextId[0]++, List.copyOf(group.getValue()), transforms.get(group.getKey())));
        }

        List<GeckoLibBone> children = new ArrayList<>();
        for (Object child : GeckoLibModel.childBones(renderer, handle)) {
            GeckoLibBone resolved = build(renderer, child, seen, nextId);
            if (resolved != null) {
                children.add(resolved);
            }
        }

        return new GeckoLibBone(handle, pieces, children);
    }

    /**
     * Every bone below and including this one, in the order they are drawn.
     */
    public void flatten(@NotNull List<GeckoLibBone> into) {
        into.add(this);
        for (GeckoLibBone child : children) {
            child.flatten(into);
        }
    }

    /**
     * Walks the branch as GeckoLib would, handing each piece the matrix it would have drawn under.
     * <p>
     * Only meaningful inside a render pass: outside one a bone has no pose to read, and every piece
     * comes back where the model file left it.
     */
    public void visit(@NotNull Object renderer, @NotNull PoseStack poseStack, @NotNull Visitor visitor) {
        visit(renderer, poseStack, visitor, null);
    }

    /**
     * As above, also telling {@code bones} where every bone stands - including bones with nothing to
     * draw, which is what the bones a mob holds things on usually are.
     */
    public void visit(@NotNull Object renderer, @NotNull PoseStack poseStack, @NotNull Visitor visitor,
                      @Nullable BoneVisitor bones) {
        if (GeckoLibModel.isHidden(renderer, handle)) {
            return;
        }

        poseStack.pushPose();
        GeckoLibModel.prepMatrixForBone(renderer, poseStack, handle);
        if (bones != null) {
            bones.visit(this, new Matrix4f(poseStack.last().pose()));
        }

        for (Piece piece : pieces) {
            if (piece.transform() == null) {
                visitor.visit(piece, new Matrix4f(poseStack.last().pose()));
                continue;
            }

            poseStack.pushPose();
            GeckoLibModel.applyCubeTransform(renderer, poseStack, piece.transform());
            visitor.visit(piece, new Matrix4f(poseStack.last().pose()));
            poseStack.popPose();
        }

        // A bone can hide everything hanging off it while staying visible itself
        if (!GeckoLibModel.areChildrenHidden(renderer, handle)) {
            for (GeckoLibBone child : children) {
                child.visit(renderer, poseStack, visitor, bones);
            }
        }

        poseStack.popPose();
    }

    @FunctionalInterface
    public interface Visitor {
        void visit(Piece piece, Matrix4f matrix);
    }

    @FunctionalInterface
    public interface BoneVisitor {
        void visit(GeckoLibBone bone, Matrix4f matrix);
    }
}
