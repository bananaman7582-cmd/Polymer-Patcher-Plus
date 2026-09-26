package me.drex.polymerpatcher.block;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

public record ShapeKey(List<AABB> boxes) {

    private static final double EPS = 1e-7;

    /**
     * Measures how unlike two collision shapes are. The bulk of the score is the volume that belongs
     * to only one of the shapes. Bounds are included as well so that two thin shapes on opposite sides
     * of a block are not considered good substitutes merely because both occupy little volume.
     */
    public double distanceTo(ShapeKey other) {
        if (this.equals(other)) return 0;

        double volume = volume(this.boxes) + volume(other.boxes);
        double intersection = 0;
        for (AABB first : this.boxes) {
            for (AABB second : other.boxes) {
                intersection += intersectionVolume(first, second);
            }
        }

        // VoxelShape#toAabbs returns non-overlapping boxes, so this is the exact symmetric difference.
        double symmetricDifference = Math.max(0, volume - 2 * intersection);
        double boundsDifference = boundsDifference(this.boxes, other.boxes);
        double emptyMismatch = this.boxes.isEmpty() != other.boxes.isEmpty() ? 1 : 0;
        double complexityDifference = Math.abs(this.boxes.size() - other.boxes.size()) * 1e-5;
        return symmetricDifference + boundsDifference * 0.25 + emptyMismatch + complexityDifference;
    }

    public static ShapeKey of(VoxelShape shape) {
        if (shape.isEmpty()) return new ShapeKey(List.of());

        List<AABB> boxes = new ArrayList<>(shape.toAabbs());

        boxes.sort(Comparator
            .comparingDouble((AABB bb) -> bb.minX)
            .thenComparingDouble(bb -> bb.minY)
            .thenComparingDouble(bb -> bb.minZ)
            .thenComparingDouble(bb -> bb.maxX)
            .thenComparingDouble(bb -> bb.maxY)
            .thenComparingDouble(bb -> bb.maxZ)
        );

        return new ShapeKey(List.copyOf(boxes));
    }

    private static double volume(List<AABB> boxes) {
        double result = 0;
        for (AABB box : boxes) {
            result += Math.max(0, box.maxX - box.minX)
                * Math.max(0, box.maxY - box.minY)
                * Math.max(0, box.maxZ - box.minZ);
        }
        return result;
    }

    private static double intersectionVolume(AABB first, AABB second) {
        double x = Math.max(0, Math.min(first.maxX, second.maxX) - Math.max(first.minX, second.minX));
        double y = Math.max(0, Math.min(first.maxY, second.maxY) - Math.max(first.minY, second.minY));
        double z = Math.max(0, Math.min(first.maxZ, second.maxZ) - Math.max(first.minZ, second.minZ));
        return x * y * z;
    }

    private static double boundsDifference(List<AABB> first, List<AABB> second) {
        if (first.isEmpty() || second.isEmpty()) return 0;

        AABB firstBounds = bounds(first);
        AABB secondBounds = bounds(second);
        return squaredDifference(firstBounds.minX, secondBounds.minX)
            + squaredDifference(firstBounds.minY, secondBounds.minY)
            + squaredDifference(firstBounds.minZ, secondBounds.minZ)
            + squaredDifference(firstBounds.maxX, secondBounds.maxX)
            + squaredDifference(firstBounds.maxY, secondBounds.maxY)
            + squaredDifference(firstBounds.maxZ, secondBounds.maxZ);
    }

    private static AABB bounds(List<AABB> boxes) {
        AABB result = boxes.getFirst();
        for (int i = 1; i < boxes.size(); i++) {
            result = result.minmax(boxes.get(i));
        }
        return result;
    }

    private static double squaredDifference(double first, double second) {
        double difference = first - second;
        return difference * difference;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ShapeKey(List<AABB> boxes1))) return false;
        if (boxes.size() != boxes1.size()) return false;

        for (int i = 0; i < boxes.size(); i++) {
            AABB a = boxes.get(i);
            AABB b = boxes1.get(i);

            if (Math.abs(a.minX - b.minX) > EPS) return false;
            if (Math.abs(a.minY - b.minY) > EPS) return false;
            if (Math.abs(a.minZ - b.minZ) > EPS) return false;
            if (Math.abs(a.maxX - b.maxX) > EPS) return false;
            if (Math.abs(a.maxY - b.maxY) > EPS) return false;
            if (Math.abs(a.maxZ - b.maxZ) > EPS) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (AABB bb : boxes) {
            h = 31 * h + java.util.Objects.hash(
                bb.minX, bb.minY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ
            );
        }
        return h;
    }
}
