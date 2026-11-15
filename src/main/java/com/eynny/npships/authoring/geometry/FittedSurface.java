package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Voxelizes a sampled surface before tracing its interior, so oblique walls cannot leak between
 * samples.
 */
public final class FittedSurface {
    private FittedSurface() {}

    public record Stage(
            SchematicRegions.Region frame, ShapeDeformation deformation, boolean spanBend) {
        public Stage(SchematicRegions.Region frame, ShapeDeformation deformation) {
            this(frame, deformation, true);
        }
    }

    public static FittedPlane.Result build(
            SchematicDocument doc,
            BlockPos seed,
            FittedPlane.Orientation orientation,
            List<Stage> stages,
            BlockState brush) {
        if (brush.isAir())
            throw new IllegalArgumentException("Choose a non-air block for the plane.");
        if (stages.stream().allMatch(stage -> stage.deformation().identity()))
            return FittedPlane.build(doc, seed, orientation, brush);
        var size = doc.size();
        var transform = transform(stages);
        java.util.function.BiFunction<Double, Double, Vec3> point =
                (u, v) -> {
                    var raw =
                            switch (orientation) {
                                case XZ -> new Vec3(u, seed.getY() + .5, v);
                                case XY -> new Vec3(u, v, seed.getZ() + .5);
                                case YZ -> new Vec3(seed.getX() + .5, u, v);
                            };
                    return transform.apply(raw);
                };
        int radius =
                (int)
                        Math.ceil(
                                Math.sqrt(
                                        (double) size.getX() * size.getX()
                                                + (double) size.getY() * size.getY()
                                                + (double) size.getZ() * size.getZ()));
        int w = radius * 2 + 1,
                h = w,
                loU = orientation.u(seed) - radius,
                loV = orientation.v(seed) - radius;
        var cells = new BitSet();
        var border = new BitSet();
        var supports = new BitSet(w * h);
        var domain = new BitSet(w * h);
        for (int v = 0; v < h; v++)
            for (int u = 0; u < w; u++) {
                int at = v * w + u;
                boolean edge = u == 0 || v == 0 || u == w - 1 || v == h - 1;
                rasterQuad(
                        point,
                        loU + u,
                        loV + v,
                        size,
                        i -> {
                            domain.set(at);
                            cells.set(i);
                            if (edge) border.set(i);
                            if (occupied(doc, AreaGeometry.position(i, size))) supports.set(at);
                        });
            }
        if (cells.cardinality() > 8_000_000)
            throw new IllegalArgumentException("Fitted surface exceeds 8,000,000 cells.");
        var target = point.apply(orientation.u(seed) + .5, orientation.v(seed) + .5);
        // Moving the surface may put the old reference inside a support. Follow the nearest
        // free component in the new section instead of making that reference a hard constraint.
        int start = -1;
        double best = Double.MAX_VALUE;
        var free = (BitSet) cells.clone();
        for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
            var p = AreaGeometry.position(i, size);
            if (occupied(doc, p)) {
                free.clear(i);
                continue;
            }
            double d = Vec3.atCenterOf(p).distanceToSqr(target);
            if (d < best) {
                best = d;
                start = i;
            }
        }
        if (start < 0)
            throw new IllegalArgumentException("No free section remains inside the schematic.");
        var component = new BitSet();
        var queue = new int[free.cardinality()];
        int read = 0, write = 0;
        component.set(start);
        queue[write++] = start;
        boolean open = false;
        int sx = size.getX(), sz = size.getZ(), sy = size.getY(), row = sx * sz;
        while (read < write) {
            int i = queue[read++];
            var p = AreaGeometry.position(i, size);
            if (border.get(i)
                    || p.getX() == 0
                    || p.getY() == 0
                    || p.getZ() == 0
                    || p.getX() == sx - 1
                    || p.getY() == sy - 1
                    || p.getZ() == sz - 1) open = true;
            for (int d = 0; d < 6; d++) {
                int n =
                        switch (d) {
                            case 0 -> p.getX() > 0 ? i - 1 : -1;
                            case 1 -> p.getX() + 1 < sx ? i + 1 : -1;
                            case 2 -> p.getZ() > 0 ? i - sx : -1;
                            case 3 -> p.getZ() + 1 < sz ? i + sx : -1;
                            case 4 -> p.getY() > 0 ? i - row : -1;
                            default -> p.getY() + 1 < sy ? i + row : -1;
                        };
                if (n >= 0 && free.get(n) && !component.get(n)) {
                    component.set(n);
                    queue[write++] = n;
                }
            }
        }
        if (open) {
            // Isolated supports keep the polygon rule. Closed sections above use all connected
            // interior cells, including recesses hidden from the reference point.
            var polygon = FittedPlane.cells(supports, domain, w, h, radius, radius);
            component.clear();
            for (int i = polygon.nextSetBit(0); i >= 0; i = polygon.nextSetBit(i + 1))
                rasterQuad(point, loU + i % w, loV + i / w, size, component::set);
            component.and(free);
        }
        return document(component, size, brush);
    }

    /** Shared surface map for geometry and editor reference markers. */
    public static java.util.function.Function<Vec3, Vec3> transform(List<Stage> stages) {
        var maps = new ArrayList<java.util.function.Function<Vec3, Vec3>>();
        for (var stage : stages) {
            var frame = stage.frame();
            var deformation = stage.deformation();
            if (deformation.identity()) continue;
            double[] parameters = deformation.parameters();
            var rotation =
                    new Matrix3d()
                            .rotationXYZ(
                                    Math.toRadians(parameters[0]),
                                    Math.toRadians(parameters[1]),
                                    Math.toRadians(parameters[2]));
            Arrays.fill(parameters, 0, 3, 0);
            var model = frame.size();
            deformation.direct().validate(model);
            // A one-block sheet still needs a visible normal bend; its span, not its voxel
            // thickness, defines the bend amplitude. Corner offsets retain their block units.
            double span = Math.max(model.getX(), Math.max(model.getY(), model.getZ()));
            if (stage.spanBend() && model.getX() == 1) parameters[3] *= span;
            if (stage.spanBend() && model.getZ() == 1) parameters[4] *= span;
            if (stage.spanBend() && model.getY() == 1) parameters[6] *= span;
            var warp = deformation.unrestricted(true).parameters(parameters);
            var center = frame.bounds().getCenter();
            boolean plain = warp.identity();
            maps.add(
                    raw -> {
                        if (plain) {
                            var relative = raw.subtract(center);
                            var q =
                                    rotation.transform(
                                            new Vector3d(relative.x, relative.y, relative.z));
                            return center.add(q.x, q.y, q.z);
                        }
                        var local = raw.subtract(Vec3.atLowerCornerOf(frame.origin()));
                        // Extend cage edge offsets instead of extrapolating nonlinear warps to
                        // infinity.
                        var clamped =
                                new Vec3(
                                        Math.clamp(local.x, 0, model.getX()),
                                        Math.clamp(local.y, 0, model.getY()),
                                        Math.clamp(local.z, 0, model.getZ()));
                        var moved =
                                warp.forward(clamped, model)
                                        .subtract(clamped)
                                        .add(raw)
                                        .subtract(center);
                        var q = rotation.transform(new Vector3d(moved.x, moved.y, moved.z));
                        return center.add(q.x, q.y, q.z);
                    });
        }
        return raw -> {
            for (var map : maps) raw = map.apply(raw);
            return raw;
        };
    }

    private static boolean occupied(SchematicDocument doc, BlockPos p) {
        var id = doc.blockName(p);
        return !id.equals("minecraft:air")
                && !id.equals("minecraft:cave_air")
                && !id.equals("minecraft:void_air");
    }

    private static void rasterQuad(
            java.util.function.BiFunction<Double, Double, Vec3> map,
            double u,
            double v,
            BlockPos size,
            IntConsumer sink) {
        var a = map.apply(u, v);
        var b = map.apply(u + 1, v);
        var c = map.apply(u + 1, v + 1);
        var d = map.apply(u, v + 1);
        triangle(a, b, c, size, sink);
        triangle(a, c, d, size, sink);
    }

    static void triangle(Vec3 a, Vec3 b, Vec3 c, BlockPos size, IntConsumer sink) {
        int x0 = Math.max(0, (int) Math.floor(Math.min(a.x, Math.min(b.x, c.x)) - 1e-7));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a.y, Math.min(b.y, c.y)) - 1e-7));
        int z0 = Math.max(0, (int) Math.floor(Math.min(a.z, Math.min(b.z, c.z)) - 1e-7));
        int x1 =
                Math.min(
                        size.getX() - 1,
                        (int) Math.floor(Math.max(a.x, Math.max(b.x, c.x)) + 1e-7));
        int y1 =
                Math.min(
                        size.getY() - 1,
                        (int) Math.floor(Math.max(a.y, Math.max(b.y, c.y)) + 1e-7));
        int z1 =
                Math.min(
                        size.getZ() - 1,
                        (int) Math.floor(Math.max(a.z, Math.max(b.z, c.z)) + 1e-7));
        if (x1 < x0 || y1 < y0 || z1 < z0) return;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 32768) {
            // Split raster work, not the user's allowed deformation range.
            double ab2 = a.distanceToSqr(b), bc2 = b.distanceToSqr(c), ca2 = c.distanceToSqr(a);
            if (ab2 >= bc2 && ab2 >= ca2) {
                var mid = a.lerp(b, .5);
                triangle(a, mid, c, size, sink);
                triangle(mid, b, c, size, sink);
            } else if (bc2 >= ca2) {
                var mid = b.lerp(c, .5);
                triangle(a, b, mid, size, sink);
                triangle(a, mid, c, size, sink);
            } else {
                var mid = c.lerp(a, .5);
                triangle(a, b, mid, size, sink);
                triangle(mid, b, c, size, sink);
            }
            return;
        }
        var ab = b.subtract(a);
        var bc = c.subtract(b);
        var ca = a.subtract(c);
        var axes = new ArrayList<Vec3>();
        axes.add(ab.cross(bc));
        for (var e : List.of(ab, bc, ca)) {
            axes.add(new Vec3(0, e.z, -e.y));
            axes.add(new Vec3(-e.z, 0, e.x));
            axes.add(new Vec3(e.y, -e.x, 0));
        }
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    var center = new Vec3(x + .5, y + .5, z + .5);
                    boolean hit = true;
                    for (var axis : axes) {
                        double o = center.dot(axis),
                                p = a.dot(axis) - o,
                                q = b.dot(axis) - o,
                                r = c.dot(axis) - o;
                        double extent =
                                .5 * (Math.abs(axis.x) + Math.abs(axis.y) + Math.abs(axis.z))
                                        + 1e-8;
                        if (Math.min(p, Math.min(q, r)) > extent
                                || Math.max(p, Math.max(q, r)) < -extent) {
                            hit = false;
                            break;
                        }
                    }
                    if (hit) sink.accept((y * size.getZ() + z) * size.getX() + x);
                }
    }

    public static FittedPlane.Result document(BitSet cells, BlockPos size, BlockState brush) {
        if (cells.isEmpty())
            throw new IllegalArgumentException("No free cells for the fitted surface.");
        int x0 = size.getX(), y0 = size.getY(), z0 = size.getZ(), x1 = 0, y1 = 0, z1 = 0;
        for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
            var p = AreaGeometry.position(i, size);
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        var origin = new BlockPos(x0, y0, z0);
        var extent = new BlockPos(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        if ((long) extent.getX() * extent.getY() * extent.getZ() > 8_000_000)
            throw new IllegalArgumentException("Fitted surface bounds exceed 8,000,000 cells.");
        var tag = AuthoringCreation.empty(extent, ExportProfile.EMPTY).nbtCopy();
        var palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(brush));
        tag.put("palette", palette);
        var blocks = new ListTag();
        var mask = new BitSet();
        for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
            var p = AreaGeometry.position(i, size).subtract(origin);
            var b = new CompoundTag();
            b.put("pos", StructureSegments.ints(p));
            b.putInt("state", 0);
            blocks.add(b);
            mask.set(AreaGeometry.index(p, extent));
        }
        tag.put("blocks", blocks);
        return new FittedPlane.Result(
                new SchematicRegions.Region(origin, extent),
                new AreaGeometry.Result(new SchematicDocument(tag, null), mask));
    }
}
