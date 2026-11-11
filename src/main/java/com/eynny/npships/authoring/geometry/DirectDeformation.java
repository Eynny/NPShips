package com.eynny.npships.authoring.geometry;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;

/** One editable operation in local block units. Signed axes survive schematic quarter turns. */
public record DirectDeformation(
        Mode mode,
        int axis,
        int normal,
        double amount,
        boolean both,
        Vec3 center,
        double radius,
        double softness) {
    public enum Mode {
        NONE,
        BEND,
        TAPER,
        SHEAR,
        BULGE,
        SCALE
    }

    public static final DirectDeformation NONE =
            new DirectDeformation(Mode.NONE, 1, 2, 0, false, Vec3.ZERO, 4, 2);

    public DirectDeformation {
        if (mode == null
                || axis == 0
                || normal == 0
                || axis < -3
                || axis > 3
                || normal < -3
                || normal > 3
                || Math.abs(axis) == Math.abs(normal)
                || !Double.isFinite(amount)
                || !Double.isFinite(radius)
                || radius < .5
                || !Double.isFinite(softness)
                || softness < 1
                || softness > 4
                || center == null
                || !Double.isFinite(center.lengthSqr())
                || Math.max(Math.abs(center.x), Math.max(Math.abs(center.y), Math.abs(center.z)))
                        > 1) throw new IllegalArgumentException("Invalid deformation parameters");
    }

    public static double neutralAmount(Mode mode) {
        return mode == Mode.TAPER || mode == Mode.SCALE ? 100 : 0;
    }

    public boolean identity() {
        return mode == Mode.NONE || amount == neutralAmount(mode);
    }

    public DirectDeformation amount(double value) {
        return new DirectDeformation(mode, axis, normal, value, both, center, radius, softness);
    }

    public DirectDeformation axes(int a, int n) {
        return new DirectDeformation(mode, a, n, amount, both, center, radius, softness);
    }

    public DirectDeformation center(Vec3 value) {
        return new DirectDeformation(mode, axis, normal, amount, both, value, radius, softness);
    }

    public DirectDeformation radius(double value) {
        return new DirectDeformation(mode, axis, normal, amount, both, center, value, softness);
    }

    public static Vec3 direction(int axis) {
        return new Vec3(
                axis == 1 ? 1 : axis == -1 ? -1 : 0,
                axis == 2 ? 1 : axis == -2 ? -1 : 0,
                axis == 3 ? 1 : axis == -3 ? -1 : 0);
    }

    public static double length(BlockPos size, int axis) {
        return Math.abs(axis) == 1 ? size.getX() : Math.abs(axis) == 2 ? size.getY() : size.getZ();
    }

    private static double get(Vector3d p, int axis) {
        return p.get(Math.abs(axis) - 1) * Math.signum(axis);
    }

    private static void set(Vector3d p, int axis, double v) {
        p.setComponent(Math.abs(axis) - 1, v * Math.signum(axis));
    }

    public void validate(BlockPos size) {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1)
            throw new IllegalArgumentException("Deformation requires a nonempty frame.");
    }

    /** Coordinates are relative to the undeformed frame centre, before rotation. */
    public Vector3d forward(Vector3d point, BlockPos size) {
        return map(point, size, false);
    }

    public Vector3d inverse(Vector3d point, BlockPos size) {
        return map(point, size, true);
    }

    private Vector3d map(Vector3d point, BlockPos size, boolean inverse) {
        var p = new Vector3d(point);
        if (identity()) return p;
        double len = length(size, axis), x = get(p, axis), y = get(p, normal);
        double t = (x + len / 2) / len;
        switch (mode) {
            case BEND -> {
                double k = Math.toRadians(amount) / len;
                if (inverse) {
                    double u = k * (x + len / 2), v = 1 - k * y;
                    double angle = Math.atan2(u, v);
                    if (amount > 180 && angle < 0) angle += Math.PI * 2;
                    if (amount < -180 && angle > 0) angle -= Math.PI * 2;
                    set(p, axis, angle / k - len / 2);
                    set(p, normal, (1 - Math.hypot(u, v)) / k);
                } else {
                    double angle = (x + len / 2) * k;
                    set(p, axis, Math.sin(angle) * (1 / k - y) - len / 2);
                    set(p, normal, (1 - Math.cos(angle)) / k + Math.cos(angle) * y);
                }
            }
            case TAPER -> {
                double scale = 1 + (amount / 100 - 1) * t;
                if (inverse && Math.abs(scale) < 1e-12) return null;
                for (int i = 1; i <= 3; i++)
                    if (i != Math.abs(axis) && (both || i == Math.abs(normal)))
                        set(p, i, get(p, i) * (inverse ? 1 / scale : scale));
            }
            case SCALE -> {
                double scale = amount / 100;
                if (inverse && Math.abs(scale) < 1e-12) return null;
                for (int i = 1; i <= 3; i++)
                    if (both || i == Math.abs(axis))
                        set(p, i, get(p, i) * (inverse ? 1 / scale : scale));
            }
            case SHEAR -> set(p, normal, y + (inverse ? -1 : 1) * amount * t);
            case BULGE -> {
                double d2 = 0;
                for (int i = 1; i <= 3; i++)
                    if (i != Math.abs(normal)) {
                        double c =
                                (i == 1 ? center.x : i == 2 ? center.y : center.z)
                                        * length(size, i)
                                        / 2;
                        double d = get(p, i) - c;
                        d2 += d * d;
                    }
                double w = Math.max(0, 1 - d2 / (radius * radius));
                // Zero slope at the rim, including the broadest profile.
                w = Math.pow(w, softness + 1);
                set(p, normal, y + (inverse ? -1 : 1) * amount * w);
            }
            default -> {}
        }
        return p;
    }

    public DirectDeformation turned(boolean right) {
        return new DirectDeformation(
                mode,
                turnAxis(axis, right),
                turnAxis(normal, right),
                amount,
                both,
                right
                        ? new Vec3(-center.z, center.y, center.x)
                        : new Vec3(center.z, center.y, -center.x),
                radius,
                softness);
    }

    private static int turnAxis(int a, boolean right) {
        return Math.abs(a) == 2
                ? a
                : Math.abs(a) == 1
                        ? (right ? 3 : -3) * Integer.signum(a)
                        : (right ? -1 : 1) * Integer.signum(a);
    }

    public JsonObject encode() {
        var o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("axis", axis);
        o.addProperty("normal", normal);
        o.addProperty("amount", amount);
        o.addProperty("both", both);
        o.addProperty("cx", center.x);
        o.addProperty("cy", center.y);
        o.addProperty("cz", center.z);
        o.addProperty("radius", radius);
        o.addProperty("softness", softness);
        return o;
    }

    public static DirectDeformation decode(JsonObject o) {
        if (o == null) return NONE;
        return new DirectDeformation(
                Mode.valueOf(o.get("mode").getAsString()),
                com.eynny.npships.authoring.validation.JsonNumbers.integer(
                        o.get("axis"), "deformation axis"),
                com.eynny.npships.authoring.validation.JsonNumbers.integer(
                        o.get("normal"), "deformation normal"),
                o.get("amount").getAsDouble(),
                o.get("both").getAsBoolean(),
                new Vec3(
                        o.get("cx").getAsDouble(),
                        o.get("cy").getAsDouble(),
                        o.get("cz").getAsDouble()),
                o.get("radius").getAsDouble(),
                o.get("softness").getAsDouble());
    }
}
