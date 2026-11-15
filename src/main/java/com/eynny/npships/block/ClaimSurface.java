package com.eynny.npships.block;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Exposed rectangles of a voxel shape, including partial coverage by adjacent blocks. */
public final class ClaimSurface {
    private static final double EPS = 1e-7;

    public record Face(Direction direction, AABB bounds) {}

    private record Rect(double u0, double v0, double u1, double v1) {}

    private ClaimSurface() {}

    public static List<Face> faces(VoxelShape shape, Function<Direction, VoxelShape> adjacent) {
        var boxes = shape.toAabbs();
        if (boxes.isEmpty()) return List.of();
        var covers = new ArrayList<>(boxes);
        for (var direction : Direction.values()) {
            var offset = direction.getNormal();
            for (var box : adjacent.apply(direction).toAabbs())
                covers.add(box.move(offset.getX(), offset.getY(), offset.getZ()));
        }
        var result = new ArrayList<Face>();
        for (var box : boxes)
            for (var direction : Direction.values()) {
                int axis = direction.getAxis().ordinal(), u = (axis + 1) % 3, v = (axis + 2) % 3;
                boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                double plane = positive ? max(box, axis) : min(box, axis);
                List<Rect> exposed = new ArrayList<>();
                exposed.add(new Rect(min(box, u), min(box, v), max(box, u), max(box, v)));
                for (var cover : covers) {
                    boolean beyond =
                            positive
                                    ? min(cover, axis) <= plane + EPS
                                            && max(cover, axis) > plane + EPS
                                    : max(cover, axis) >= plane - EPS
                                            && min(cover, axis) < plane - EPS;
                    if (!beyond) continue;
                    var next = new ArrayList<Rect>();
                    for (var rect : exposed)
                        subtract(
                                rect,
                                min(cover, u),
                                min(cover, v),
                                max(cover, u),
                                max(cover, v),
                                next);
                    exposed = next;
                    if (exposed.isEmpty()) break;
                }
                for (var rect : exposed) {
                    double[] low = new double[3], high = new double[3];
                    low[axis] = high[axis] = plane;
                    low[u] = rect.u0;
                    high[u] = rect.u1;
                    low[v] = rect.v0;
                    high[v] = rect.v1;
                    result.add(
                            new Face(
                                    direction,
                                    new AABB(low[0], low[1], low[2], high[0], high[1], high[2])));
                }
            }
        return result;
    }

    private static void subtract(
            Rect r, double u0, double v0, double u1, double v1, List<Rect> out) {
        double a = Math.max(r.u0, u0),
                b = Math.max(r.v0, v0),
                c = Math.min(r.u1, u1),
                d = Math.min(r.v1, v1);
        if (c <= a + EPS || d <= b + EPS) {
            out.add(r);
            return;
        }
        if (a > r.u0 + EPS) out.add(new Rect(r.u0, r.v0, a, r.v1));
        if (c < r.u1 - EPS) out.add(new Rect(c, r.v0, r.u1, r.v1));
        if (b > r.v0 + EPS) out.add(new Rect(a, r.v0, c, b));
        if (d < r.v1 - EPS) out.add(new Rect(a, d, c, r.v1));
    }

    private static double min(AABB b, int axis) {
        return axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ;
    }

    private static double max(AABB b, int axis) {
        return axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ;
    }
}
