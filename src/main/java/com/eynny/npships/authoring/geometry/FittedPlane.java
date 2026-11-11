package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** A star polygon around the picked point; visible supports are joined, not flood-filled. */
public final class FittedPlane {
    public enum Orientation {
        XZ,
        XY,
        YZ;

        public BlockPos point(int u, int v, int fixed) {
            return switch (this) {
                case XZ -> new BlockPos(u, fixed, v);
                case XY -> new BlockPos(u, v, fixed);
                case YZ -> new BlockPos(fixed, u, v);
            };
        }

        public int u(BlockPos p) {
            return this == YZ ? p.getY() : p.getX();
        }

        public int v(BlockPos p) {
            return this == XZ ? p.getZ() : this == XY ? p.getY() : p.getZ();
        }

        public int fixed(BlockPos p) {
            return this == XZ ? p.getY() : this == XY ? p.getZ() : p.getX();
        }
    }

    public record Result(SchematicRegions.Region region, AreaGeometry.Result geometry) {}

    private record Point(double u, double v, double angle) {}

    private FittedPlane() {}

    public static Result build(
            SchematicDocument doc, BlockPos seed, Orientation plane, BlockState brush) {
        if (!doc.contains(seed))
            throw new IllegalArgumentException("Choose a point inside the schematic.");
        if (brush.isAir())
            throw new IllegalArgumentException("Choose a non-air block for the plane.");
        int w = plane.u(doc.size()),
                h = plane.v(doc.size()),
                su = plane.u(seed),
                sv = plane.v(seed),
                fixed = plane.fixed(seed);
        var occupied = new BitSet(w * h);
        for (int v = 0; v < h; v++)
            for (int u = 0; u < w; u++)
                if (!Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
                        .contains(doc.blockName(plane.point(u, v, fixed)))) occupied.set(v * w + u);
        return document(cells(occupied, w, h, su, sv), w, h, fixed, plane, brush);
    }

    public static BitSet cells(BitSet occupied, int w, int h, int su, int sv) {
        return cells(occupied, null, w, h, su, sv);
    }

    /** Domain is the actual section of the schematic, not the sampling canvas around it. */
    public static BitSet cells(BitSet occupied, BitSet domain, int w, int h, int su, int sv) {
        if (occupied.get(sv * w + su) || domain != null && !domain.get(sv * w + su)) {
            int nearest = -1;
            long distance = Long.MAX_VALUE;
            for (int v = 0; v < h; v++)
                for (int u = 0; u < w; u++) {
                    int i = v * w + u;
                    if (occupied.get(i) || domain != null && !domain.get(i)) continue;
                    long d = (long) (u - su) * (u - su) + (long) (v - sv) * (v - sv);
                    if (d < distance) {
                        nearest = i;
                        distance = d;
                    }
                }
            if (nearest < 0) return new BitSet(w * h);
            su = nearest % w;
            sv = nearest / w;
        }
        // A closed section can be deeply concave: visibility from the seed alone is insufficient.
        // Traverse the complete free component before considering separated support points.
        var enclosed = enclosedComponent(occupied, w, h, su, sv);
        if (enclosed != null) {
            if (domain != null) enclosed.and(domain);
            return enclosed;
        }
        var polygon = new ArrayList<Point>();
        for (int i = occupied.nextSetBit(0); i >= 0; i = occupied.nextSetBit(i + 1)) {
            int u = i % w, v = i / w;
            if (visible(occupied, w, su, sv, u, v)) polygon.add(point(u + .5, v + .5, su, sv));
        }
        polygon = new ArrayList<>(neighborSupports(polygon, w, h, su, sv));
        polygon.sort(Comparator.comparingDouble(Point::angle));
        // Only genuinely open half-planes fall back to the schematic boundary. Three supports
        // surrounding the seed therefore produce a triangle, not a rectangle or a flood fill.
        var supports = List.copyOf(polygon);
        var boundary = boundary(supports, domain, w, h, su, sv);
        for (var p : boundary) {
            boolean open = supports.size() < 2;
            for (int i = 0; i < supports.size() && !open; i++) {
                double a = supports.get(i).angle, b = supports.get((i + 1) % supports.size()).angle;
                if (b <= a) b += Math.PI * 2;
                double angle = p.angle;
                if (angle < a) angle += Math.PI * 2;
                if (b - a >= Math.PI - 1e-8 && angle > a + 1e-8 && angle < b - 1e-8) open = true;
            }
            // Boundary fallback cannot see through a nearby support just because the open
            // contour reaches the edge of the schematic.
            int u = Math.clamp((int) Math.floor(p.u), 0, w - 1);
            int v = Math.clamp((int) Math.floor(p.v), 0, h - 1);
            if (open && visible(occupied, w, su, sv, u, v)) polygon.add(p);
        }
        polygon.sort(Comparator.comparingDouble(Point::angle));
        var fill = new BitSet(w * h);
        var crossings = new ArrayList<Double>();
        for (int v = 0; v < h; v++) {
            crossings.clear();
            double y = v + .5;
            for (int i = 0; i < polygon.size(); i++) {
                var a = polygon.get(i);
                var b = polygon.get((i + 1) % polygon.size());
                if ((a.v > y) != (b.v > y))
                    crossings.add(a.u + (y - a.v) * (b.u - a.u) / (b.v - a.v));
            }
            crossings.sort(Double::compare);
            for (int i = 0; i + 1 < crossings.size(); i += 2)
                for (int u = Math.max(0, (int) Math.ceil(crossings.get(i) - .5));
                        u < w && u + .5 <= crossings.get(i + 1) + 1e-8;
                        u++) if (!occupied.get(v * w + u)) fill.set(v * w + u);
        }
        if (domain != null) fill.and(domain);
        return fill;
    }

    /**
     * Natural neighbors of the seed bound the local support polygon, not every distant visible
     * block.
     */
    private static List<Point> neighborSupports(
            List<Point> supports, int w, int h, int su, int sv) {
        double x = su + .5, y = sv + .5;
        var cell =
                new ArrayList<Point>(
                        List.of(
                                point(0, 0, su, sv),
                                point(w, 0, su, sv),
                                point(w, h, su, sv),
                                point(0, h, su, sv)));
        var ordered = new ArrayList<>(supports);
        ordered.sort(
                Comparator.comparingDouble(p -> (p.u - x) * (p.u - x) + (p.v - y) * (p.v - y)));
        for (var support : ordered) {
            double nx = support.u - x, ny = support.v - y;
            double limit = (support.u * support.u + support.v * support.v - x * x - y * y) / 2;
            var clipped = new ArrayList<Point>();
            for (int i = 0; i < cell.size(); i++) {
                var a = cell.get(i);
                var b = cell.get((i + 1) % cell.size());
                double da = nx * a.u + ny * a.v - limit, db = nx * b.u + ny * b.v - limit;
                if (da <= 1e-8) clipped.add(a);
                if ((da < 0) != (db < 0)) {
                    double t = da / (da - db);
                    clipped.add(point(a.u + t * (b.u - a.u), a.v + t * (b.v - a.v), su, sv));
                }
            }
            cell = clipped;
        }
        var neighbors = new ArrayList<Point>();
        for (var support : ordered) {
            double nx = support.u - x, ny = support.v - y;
            double limit = (support.u * support.u + support.v * support.v - x * x - y * y) / 2;
            for (var vertex : cell)
                if (Math.abs(nx * vertex.u + ny * vertex.v - limit) < 1e-6) {
                    neighbors.add(support);
                    break;
                }
        }
        return neighbors;
    }

    private static BitSet enclosedComponent(BitSet occupied, int w, int h, int su, int sv) {
        var seen = new BitSet(w * h);
        var queue = new int[w * h];
        int read = 0, write = 0;
        int first = sv * w + su;
        seen.set(first);
        queue[write++] = first;
        while (read < write) {
            int i = queue[read++], u = i % w, v = i / w;
            if (u == 0 || v == 0 || u == w - 1 || v == h - 1) return null;
            for (int d = 0; d < 4; d++) {
                int n =
                        switch (d) {
                            case 0 -> u > 0 ? i - 1 : -1;
                            case 1 -> u + 1 < w ? i + 1 : -1;
                            case 2 -> v > 0 ? i - w : -1;
                            default -> v + 1 < h ? i + w : -1;
                        };
                if (n >= 0 && !occupied.get(n) && !seen.get(n)) {
                    seen.set(n);
                    queue[write++] = n;
                }
            }
        }
        return seen;
    }

    private static Result document(
            BitSet fill, int w, int h, int fixed, Orientation plane, BlockState brush) {
        if (fill.isEmpty())
            throw new IllegalArgumentException("The supports leave no room for a plane.");
        int minU = w, minV = h, maxU = 0, maxV = 0;
        for (int i = fill.nextSetBit(0); i >= 0; i = fill.nextSetBit(i + 1)) {
            minU = Math.min(minU, i % w);
            maxU = Math.max(maxU, i % w);
            minV = Math.min(minV, i / w);
            maxV = Math.max(maxV, i / w);
        }
        var origin = plane.point(minU, minV, fixed);
        var end = plane.point(maxU, maxV, fixed);
        var size = end.subtract(origin).offset(1, 1, 1);
        var nbt = AuthoringCreation.empty(size, ExportProfile.EMPTY).nbtCopy();
        var palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(brush));
        nbt.put("palette", palette);
        var blocks = new ListTag();
        var mask = new BitSet();
        for (int i = fill.nextSetBit(0); i >= 0; i = fill.nextSetBit(i + 1)) {
            var pos = plane.point(i % w, i / w, fixed).subtract(origin);
            var entry = new CompoundTag();
            entry.put("pos", StructureSegments.ints(pos));
            entry.putInt("state", 0);
            blocks.add(entry);
            mask.set(AreaGeometry.index(pos, size));
        }
        nbt.put("blocks", blocks);
        return new Result(
                new SchematicRegions.Region(origin, size),
                new AreaGeometry.Result(new SchematicDocument(nbt, null), mask));
    }

    private static List<Point> boundary(
            List<Point> supports, BitSet domain, int w, int h, int su, int sv) {
        boolean open = supports.size() < 2;
        for (int i = 0; i < supports.size(); i++) {
            double a = supports.get(i).angle, b = supports.get((i + 1) % supports.size()).angle;
            if (b <= a) b += Math.PI * 2;
            if (b - a >= Math.PI - 1e-8) open = true;
        }
        if (!open) return List.of();
        List<Point> result =
                new ArrayList<>(
                        List.of(
                                point(0, 0, su, sv),
                                point(w, 0, su, sv),
                                point(w, h, su, sv),
                                point(0, h, su, sv)));
        if (domain == null) {
            // Boundary samples preserve the visible intervals when a support hides a corner.
            result.clear();
            result.add(point(0, 0, su, sv));
            for (int u = 0; u < w; u++) result.add(point(u + .5, 0, su, sv));
            result.add(point(w, 0, su, sv));
            for (int v = 0; v < h; v++) result.add(point(w, v + .5, su, sv));
            result.add(point(w, h, su, sv));
            for (int u = w - 1; u >= 0; u--) result.add(point(u + .5, h, su, sv));
            result.add(point(0, h, su, sv));
            for (int v = h - 1; v >= 0; v--) result.add(point(0, v + .5, su, sv));
        }
        if (domain != null) {
            result.clear();
            var outside = new BitSet(w * h);
            outside.set(0, w * h);
            outside.andNot(domain);
            for (int i = domain.nextSetBit(0); i >= 0; i = domain.nextSetBit(i + 1)) {
                int u = i % w, v = i / w;
                if ((u == 0
                                || v == 0
                                || u == w - 1
                                || v == h - 1
                                || !domain.get(i - 1)
                                || !domain.get(i + 1)
                                || !domain.get(i - w)
                                || !domain.get(i + w))
                        && visible(outside, w, su, sv, u, v))
                    result.add(point(u + .5, v + .5, su, sv));
            }
        }
        // Boundary clipping requires a cyclic contour, including domain samples collected by row.
        result.sort(Comparator.comparingDouble(Point::angle));
        // Complete only the open angular gap. Clipping against every supporting edge would
        // intersect their half-planes and collapse a concave support chain to its tiny kernel.
        for (int i = 0; i < supports.size(); i++) {
            var a = supports.get(i);
            var b = supports.get((i + 1) % supports.size());
            double gap = b.angle - a.angle;
            if (gap <= 0) gap += Math.PI * 2;
            if (gap < Math.PI - 1e-8) continue;
            // The single chord across the open gap limits only boundary fallback. Interior
            // support edges may be concave and must never constrain unrelated parts of it.
            double sign = Math.signum(cross(a, b, point(su + .5, sv + .5, su, sv)));
            if (sign == 0) continue;
            var clipped = new ArrayList<Point>();
            for (int j = 0; j < result.size(); j++) {
                var p = result.get(j);
                var q = result.get((j + 1) % result.size());
                double dp = sign * cross(a, b, p), dq = sign * cross(a, b, q);
                if (dp >= -1e-8) clipped.add(p);
                if (domain == null && (dp >= 0) != (dq >= 0)) {
                    double t = dp / (dp - dq);
                    clipped.add(point(p.u + t * (q.u - p.u), p.v + t * (q.v - p.v), su, sv));
                }
            }
            result = clipped;
        }
        return result;
    }

    private static double cross(Point a, Point b, Point p) {
        return (b.u - a.u) * (p.v - a.v) - (b.v - a.v) * (p.u - a.u);
    }

    private static Point point(double u, double v, int su, int sv) {
        return new Point(u, v, Math.atan2(v - sv - .5, u - su - .5));
    }

    private static boolean visible(BitSet cells, int width, int su, int sv, int tu, int tv) {
        int u = su,
                v = sv,
                dx = tu - su,
                dy = tv - sv,
                stepU = Integer.signum(dx),
                stepV = Integer.signum(dy);
        double du = dx == 0 ? Double.POSITIVE_INFINITY : 1d / Math.abs(dx),
                dv = dy == 0 ? Double.POSITIVE_INFINITY : 1d / Math.abs(dy);
        double nextU = du * .5, nextV = dv * .5;
        while (u != tu || v != tv) {
            if (nextU < nextV) {
                u += stepU;
                nextU += du;
            } else if (nextV < nextU) {
                v += stepV;
                nextV += dv;
            } else {
                u += stepU;
                v += stepV;
                nextU += du;
                nextV += dv;
            }
            if (cells.get(v * width + u)) return u == tu && v == tv;
        }
        return true;
    }
}
