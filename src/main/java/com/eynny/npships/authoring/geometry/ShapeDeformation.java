package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicRegions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.*;

/** Immutable procedural cage. Inverse sampling avoids holes caused by pushing individual voxels. */
public final class ShapeDeformation {
    public static final double MAX_WARP = .49;
    private static final List<Vec3> UNIT_CORNERS = createUnitCorners();
    public static final ShapeDeformation IDENTITY =
            new ShapeDeformation(0, 0, 0, 0, 0, 0, unitCorners());
    private final boolean unrestricted;
    private final DirectDeformation direct;
    private final double rx, ry, rz, bx, bz, inset, by;
    private final List<Vec3> corners;

    public ShapeDeformation(
            double rx,
            double ry,
            double rz,
            double bx,
            double bz,
            double inset,
            List<Vec3> corners) {
        this(rx, ry, rz, bx, bz, inset, 0, corners);
    }

    public ShapeDeformation(
            double rx,
            double ry,
            double rz,
            double bx,
            double bz,
            double inset,
            double by,
            List<Vec3> corners) {
        this(rx, ry, rz, bx, bz, inset, by, corners, false);
    }

    private ShapeDeformation(
            double rx,
            double ry,
            double rz,
            double bx,
            double bz,
            double inset,
            double by,
            List<Vec3> corners,
            boolean unrestricted) {
        this(rx, ry, rz, bx, bz, inset, by, corners, unrestricted, DirectDeformation.NONE);
    }

    private ShapeDeformation(
            double rx,
            double ry,
            double rz,
            double bx,
            double bz,
            double inset,
            double by,
            List<Vec3> corners,
            boolean unrestricted,
            DirectDeformation direct) {
        this.direct = direct;
        this.unrestricted = unrestricted;
        for (double v : new double[] {rx, ry, rz, bx, bz, inset, by})
            if (!Double.isFinite(v))
                throw new IllegalArgumentException("Finite deformation values required.");
        if (corners.size() != 8) throw new IllegalArgumentException("Eight corners required");
        if (!unrestricted
                && (Math.abs(bx) > MAX_WARP
                        || Math.abs(bz) > MAX_WARP
                        || Math.abs(inset) > MAX_WARP
                        || Math.abs(by) > MAX_WARP
                        || corners.size() != 8))
            throw new IllegalArgumentException("Bend and concavity: -0.49 .. 0.49");
        for (var p : corners)
            if (!Double.isFinite(p.lengthSqr())
                    || !unrestricted
                            && Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z)))
                                    > 1.000001)
                throw new IllegalArgumentException("Corner outside the shape cage.");
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.bx = bx;
        this.bz = bz;
        this.inset = inset;
        this.by = by;
        this.corners = List.copyOf(corners);
        // Reject inverted/folded cages rather than silently dropping their contents.
        if (!unrestricted)
            for (int y = 0; y < 3; y++)
                for (int z = 0; z < 3; z++)
                    for (int x = 0; x < 3; x++)
                        if (jacobian(new Vector3d(x - 1, y - 1, z - 1)).determinant() < .005)
                            throw new IllegalArgumentException(
                                    "The shape cage folds or collapses. Move the corner back.");
    }

    private static List<Vec3> createUnitCorners() {
        var list = new ArrayList<Vec3>();
        for (int i = 0; i < 8; i++)
            list.add(new Vec3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
        return List.copyOf(list);
    }

    private static List<Vec3> unitCorners() {
        return UNIT_CORNERS;
    }

    public boolean unrestricted() {
        return unrestricted;
    }

    public ShapeDeformation unrestricted(boolean value) {
        return new ShapeDeformation(rx, ry, rz, bx, bz, inset, by, corners, value, direct);
    }

    public boolean identity() {
        return direct.identity()
                && rx % 360 == 0
                && ry % 360 == 0
                && rz % 360 == 0
                && bx == 0
                && bz == 0
                && inset == 0
                && by == 0
                && corners.equals(unitCorners());
    }

    public double[] parameters() {
        return new double[] {rx, ry, rz, bx, bz, inset, by};
    }

    public ShapeDeformation parameters(double[] p) {
        return new ShapeDeformation(
                p[0],
                p[1],
                p[2],
                p[3],
                p[4],
                p[5],
                p.length > 6 ? p[6] : 0,
                corners,
                unrestricted,
                direct);
    }

    public ShapeDeformation corner(int i, Vec3 p) {
        var list = new ArrayList<>(corners);
        list.set(i, unrestricted ? p : new Vec3(clamp(p.x), clamp(p.y), clamp(p.z)));
        return new ShapeDeformation(rx, ry, rz, bx, bz, inset, by, list, unrestricted, direct);
    }

    public Vec3 corner(int i) {
        return corners.get(i);
    }

    /** Move a selection atomically, limiting the shared displacement at cage boundaries. */
    public ShapeDeformation moveCorners(BitSet selected, Vec3 delta) {
        if (selected.isEmpty()) return this;
        double dx = delta.x, dy = delta.y, dz = delta.z;
        if (!unrestricted)
            for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
                var c = corners.get(i);
                dx = Math.max(-1 - c.x, Math.min(1 - c.x, dx));
                dy = Math.max(-1 - c.y, Math.min(1 - c.y, dy));
                dz = Math.max(-1 - c.z, Math.min(1 - c.z, dz));
            }
        var next = new ArrayList<>(corners);
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1))
            next.set(i, corners.get(i).add(dx, dy, dz));
        return new ShapeDeformation(rx, ry, rz, bx, bz, inset, by, next, unrestricted, direct);
    }

    /** Change coordinate basis with an exact schematic quarter turn, preserving the live cage. */
    public ShapeDeformation turned(boolean right) {
        var quarter = new Matrix3d().rotationY(right ? -Math.PI / 2 : Math.PI / 2);
        var angles =
                new Matrix3d(quarter)
                        .mul(rotation())
                        .mul(new Matrix3d(quarter).transpose())
                        .getEulerAnglesXYZ(new Vector3d());
        var list = new ArrayList<Vec3>(Collections.nCopies(8, Vec3.ZERO));
        for (int i = 0; i < 8; i++) {
            var nominal = unitCorners().get(i);
            var n = quarter.transform(new Vector3d(nominal.x, nominal.y, nominal.z));
            int index = (n.x > 0 ? 1 : 0) | (n.y > 0 ? 2 : 0) | (n.z > 0 ? 4 : 0);
            var c = corners.get(i);
            var p = quarter.transform(new Vector3d(c.x, c.y, c.z));
            list.set(index, new Vec3(p.x, p.y, p.z));
        }
        return new ShapeDeformation(
                Math.toDegrees(angles.x),
                Math.toDegrees(angles.y),
                Math.toDegrees(angles.z),
                right ? -bz : bz,
                right ? bx : -bx,
                inset,
                by,
                list,
                unrestricted,
                direct.turned(right));
    }

    public com.google.gson.JsonObject encode() {
        var root = new com.google.gson.JsonObject();
        var p = new com.google.gson.JsonArray();
        for (double v : parameters()) p.add(v);
        root.add("parameters", p);
        if (direct.mode() != DirectDeformation.Mode.NONE) root.add("direct", direct.encode());
        root.addProperty("unrestricted", unrestricted);
        var list = new com.google.gson.JsonArray();
        for (var c : corners) {
            var a = new com.google.gson.JsonArray();
            a.add(c.x);
            a.add(c.y);
            a.add(c.z);
            list.add(a);
        }
        root.add("corners", list);
        return root;
    }

    public static ShapeDeformation decode(com.google.gson.JsonObject root) {
        if (root == null || !root.has("parameters")) return IDENTITY;
        var p = root.getAsJsonArray("parameters");
        var list = new ArrayList<Vec3>();
        if (root.has("corners"))
            for (var e : root.getAsJsonArray("corners")) {
                var a = e.getAsJsonArray();
                if (a.size() != 3)
                    throw new IllegalArgumentException("A deformation corner requires X Y Z");
                list.add(
                        new Vec3(
                                a.get(0).getAsDouble(),
                                a.get(1).getAsDouble(),
                                a.get(2).getAsDouble()));
            }
        if (root.has("corners") && list.size() != 8)
            throw new IllegalArgumentException("Deformation corners require exactly eight points");
        return new ShapeDeformation(
                p.get(0).getAsDouble(),
                p.get(1).getAsDouble(),
                p.get(2).getAsDouble(),
                p.get(3).getAsDouble(),
                p.get(4).getAsDouble(),
                p.get(5).getAsDouble(),
                p.size() > 6 ? p.get(6).getAsDouble() : 0,
                list.size() == 8 ? list : unitCorners(),
                root.has("unrestricted") && root.get("unrestricted").getAsBoolean(),
                DirectDeformation.decode(root.getAsJsonObject("direct")));
    }

    public DirectDeformation direct() {
        return direct;
    }

    public ShapeDeformation direct(DirectDeformation value) {
        return new ShapeDeformation(rx, ry, rz, bx, bz, inset, by, corners, unrestricted, value);
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    private Matrix3d rotation() {
        return new Matrix3d()
                .rotationXYZ(Math.toRadians(rx), Math.toRadians(ry), Math.toRadians(rz));
    }

    private double fit(BlockPos size) {
        var r = rotation();
        double sx = size.getX(), sy = size.getY(), sz = size.getZ();
        return Math.min(
                sx / (Math.abs(r.m00()) * sx + Math.abs(r.m10()) * sy + Math.abs(r.m20()) * sz),
                Math.min(
                        sy
                                / (Math.abs(r.m01()) * sx
                                        + Math.abs(r.m11()) * sy
                                        + Math.abs(r.m21()) * sz),
                        sz
                                / (Math.abs(r.m02()) * sx
                                        + Math.abs(r.m12()) * sy
                                        + Math.abs(r.m22()) * sz)));
    }

    private Vector3d cage(Vector3d p) {
        double x = p.x, y = p.y, z = p.z;
        var q = new Vector3d();
        for (int i = 0; i < 8; i++) {
            double weight =
                    (1 + ((i & 1) == 0 ? -x : x))
                            * (1 + ((i & 2) == 0 ? -y : y))
                            * (1 + ((i & 4) == 0 ? -z : z))
                            / 8;
            var c = corners.get(i);
            q.add(c.x * weight, c.y * weight, c.z * weight);
        }
        return q;
    }

    private Vector3d bend(Vector3d q) {
        double x = q.x, y = q.y, z = q.z;
        return new Vector3d(
                x * (1 - inset * (1 - y * y) * (1 - z * z)) + bx * (1 - y * y) * (1 - x * x),
                y * (1 - inset * (1 - x * x) * (1 - z * z))
                        + by * (1 - x * x) * (1 - y * y) * (1 - z * z),
                z * (1 - inset * (1 - x * x) * (1 - y * y)) + bz * (1 - y * y) * (1 - z * z));
    }

    private Vector3d warp(Vector3d p) {
        return bend(cage(p));
    }

    /** Deformation follows the rotated local axes of the zone. */
    private Vector3d displayedWarp(Vector3d p, Matrix3d rotation, BlockPos size, double scale) {
        var q =
                rotation.transform(
                        direct.forward(
                                warp(p).mul(size.getX() / 2d, size.getY() / 2d, size.getZ() / 2d),
                                size));
        q.mul(scale).mul(2d / size.getX(), 2d / size.getY(), 2d / size.getZ());
        return q;
    }

    private Matrix3d jacobian(Vector3d p) {
        double e = 1e-5;
        var f = warp(p);
        return new Matrix3d()
                .setColumn(0, warp(new Vector3d(p).add(e, 0, 0)).sub(f).div(e))
                .setColumn(1, warp(new Vector3d(p).add(0, e, 0)).sub(f).div(e))
                .setColumn(2, warp(new Vector3d(p).add(0, 0, e)).sub(f).div(e));
    }

    public Vec3 handle(int i, SchematicRegions.Region region) {
        var size = region.size();
        var c = unitCorners().get(i);
        var p =
                forward(
                        new Vec3(
                                (c.x + 1) * size.getX() / 2,
                                (c.y + 1) * size.getY() / 2,
                                (c.z + 1) * size.getZ() / 2),
                        size);
        return Vec3.atLowerCornerOf(region.origin()).add(p);
    }

    public Vec3 unscaledHandle(int i, Vec3 center, BlockPos size) {
        var c = unitCorners().get(i);
        var p =
                forward(
                        new Vec3(
                                (c.x + 1) * size.getX() / 2,
                                (c.y + 1) * size.getY() / 2,
                                (c.z + 1) * size.getZ() / 2),
                        size);
        return center.add(
                p.subtract(size.getX() / 2d, size.getY() / 2d, size.getZ() / 2d)
                        .scale(1 / fit(size)));
    }

    public Vec3 unscaledPoint(Vec3 point, Vec3 center, BlockPos size) {
        return center.add(
                forward(point, size)
                        .subtract(size.getX() / 2d, size.getY() / 2d, size.getZ() / 2d)
                        .scale(1 / fit(size)));
    }

    public Vec3 unscaledCageDelta(Vec3 delta, BlockPos size) {
        return cageDelta(delta, size).scale(fit(size));
    }

    public Vec3 cageDelta(Vec3 delta, BlockPos size) {
        var v =
                rotation()
                        .transpose()
                        .transform(new Vector3d(delta.x, delta.y, delta.z))
                        .div(fit(size));
        return new Vec3(v.x * 2 / size.getX(), v.y * 2 / size.getY(), v.z * 2 / size.getZ());
    }

    /** Reusable inverse transform for resampling arbitrary selected contents. */
    public java.util.function.Function<Vec3, Vec3> inverseSampler(BlockPos size) {
        if (!direct.identity()) {
            var legacy = direct(DirectDeformation.NONE).inverseSampler(size);
            var r = rotation();
            var inv = new Matrix3d(r).transpose();
            double fit = fit(size);
            return point -> {
                var q =
                        inv.transform(
                                        new Vector3d(
                                                point.x - size.getX() / 2d,
                                                point.y - size.getY() / 2d,
                                                point.z - size.getZ() / 2d))
                                .div(fit);
                q = direct.inverse(q, size);
                if (q == null) return null;
                r.transform(q).mul(fit);
                return legacy.apply(
                        new Vec3(
                                q.x + size.getX() / 2d,
                                q.y + size.getY() / 2d,
                                q.z + size.getZ() / 2d));
            };
        }
        var rotation = rotation();
        var inverse = new Matrix3d(rotation).transpose();
        double scale = fit(size);
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        boolean plain =
                bx == 0 && bz == 0 && by == 0 && inset == 0 && corners.equals(unitCorners());
        return point -> {
            var target =
                    new Vector3d(point.x * 2 / sx - 1, point.y * 2 / sy - 1, point.z * 2 / sz - 1);
            var p =
                    inverse.transform(new Vector3d(target).mul(sx / 2d, sy / 2d, sz / 2d))
                            .div(scale)
                            .mul(2d / sx, 2d / sy, 2d / sz);
            boolean valid = plain;
            if (!plain)
                for (int i = 0; i < 20; i++) {
                    var f = displayedWarp(p, rotation, size, scale);
                    var difference = new Vector3d(f).sub(target);
                    if (difference.lengthSquared() < 1e-10) {
                        valid = true;
                        break;
                    }
                    double e = 1e-5;
                    var j =
                            new Matrix3d()
                                    .setColumn(
                                            0,
                                            displayedWarp(
                                                            new Vector3d(p).add(e, 0, 0),
                                                            rotation,
                                                            size,
                                                            scale)
                                                    .sub(f)
                                                    .div(e))
                                    .setColumn(
                                            1,
                                            displayedWarp(
                                                            new Vector3d(p).add(0, e, 0),
                                                            rotation,
                                                            size,
                                                            scale)
                                                    .sub(f)
                                                    .div(e))
                                    .setColumn(
                                            2,
                                            displayedWarp(
                                                            new Vector3d(p).add(0, 0, e),
                                                            rotation,
                                                            size,
                                                            scale)
                                                    .sub(f)
                                                    .div(e));
                    if (Math.abs(j.determinant()) < 1e-8) break;
                    p.sub(j.invert().transform(difference));
                    if (p.lengthSquared() > 16) break;
                }
            return valid
                            && Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z)))
                                    <= 1.00001
                    ? new Vec3((p.x + 1) * sx / 2, (p.y + 1) * sy / 2, (p.z + 1) * sz / 2)
                    : null;
        };
    }

    public Vec3 forward(Vec3 point, BlockPos size) {
        var q =
                displayedWarp(
                        new Vector3d(
                                point.x * 2 / size.getX() - 1,
                                point.y * 2 / size.getY() - 1,
                                point.z * 2 / size.getZ() - 1),
                        rotation(),
                        size,
                        fit(size));
        return new Vec3(
                (q.x + 1) * size.getX() / 2,
                (q.y + 1) * size.getY() / 2,
                (q.z + 1) * size.getZ() / 2);
    }

    public BitSet raster(SchematicRegions.Shape shape, BlockPos size) {
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        if ((long) sx * sy * sz > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Shape too large");
        var result = new BitSet(sx * sy * sz);
        var sample = inverseSampler(size);
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    var p = sample.apply(new Vec3(x + .5, y + .5, z + .5));
                    if (p != null
                            && shape.includes(
                                    (int) Math.floor(p.x),
                                    (int) Math.floor(p.y),
                                    (int) Math.floor(p.z),
                                    size)) result.set((y * sz + z) * sx + x);
                }
        return result;
    }
}
