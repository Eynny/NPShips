package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicRegions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Expands the sampling grid instead of clipping a new operation to its source frame. */
public final class DirectAreaTransform {
    private DirectAreaTransform() {}

    public static RigidAreaRotation.Result apply(
            AreaGeometry.Result input,
            Vec3 center,
            double[] angles,
            DirectDeformation deformation) {
        return apply(input, center, angles, deformation, false);
    }

    public static RigidAreaRotation.Result apply(
            AreaGeometry.Result input,
            Vec3 center,
            double[] angles,
            DirectDeformation deformation,
            boolean continuous) {
        return apply(input, center, angles, deformation, continuous, false);
    }

    public static RigidAreaRotation.Result apply(
            AreaGeometry.Result input,
            Vec3 center,
            double[] angles,
            DirectDeformation deformation,
            boolean continuous,
            boolean allowConfiguredLoss) {
        var size = input.content().size();
        deformation.validate(size);
        var rotation =
                new Matrix3d()
                        .rotationXYZ(
                                Math.toRadians(angles[0]),
                                Math.toRadians(angles[1]),
                                Math.toRadians(angles[2]));
        var inverse = new Matrix3d(rotation).transpose();
        var lo = new Vector3d(Double.POSITIVE_INFINITY);
        var hi = new Vector3d(Double.NEGATIVE_INFINITY);
        // Extrema of an axis-aligned bent box occur at the ends or quarter circles.
        for (int i = 0; i < 8; i++) {
            var p =
                    new Vector3d(
                            (i & 1) == 0 ? -size.getX() / 2d : size.getX() / 2d,
                            (i & 2) == 0 ? -size.getY() / 2d : size.getY() / 2d,
                            (i & 4) == 0 ? -size.getZ() / 2d : size.getZ() / 2d);
            var q = deformation.forward(p, size);
            lo.min(q);
            hi.max(q);
            if (deformation.mode() == DirectDeformation.Mode.BEND
                    && Math.abs(deformation.amount()) >= 90) {
                for (int quarter = 1;
                        quarter <= 4 && quarter * 90 <= Math.abs(deformation.amount());
                        quarter++) {
                    double t = quarter * 90 / Math.abs(deformation.amount());
                    p.setComponent(
                            Math.abs(deformation.axis()) - 1,
                            Math.signum(deformation.axis())
                                    * DirectDeformation.length(size, deformation.axis())
                                    * (t - .5));
                    q = deformation.forward(p, size);
                    lo.min(q);
                    hi.max(q);
                }
            }
        }
        if (deformation.mode() == DirectDeformation.Mode.BULGE) {
            int a = Math.abs(deformation.normal()) - 1;
            double d = deformation.amount() * Math.signum(deformation.normal());
            double half = DirectDeformation.length(size, deformation.normal()) / 2;
            lo.setComponent(a, -half + Math.min(0, d));
            hi.setComponent(a, half + Math.max(0, d));
        }
        var worldLo = new Vector3d(Double.POSITIVE_INFINITY);
        var worldHi = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int i = 0; i < 8; i++) {
            var q =
                    rotation.transform(
                                    new Vector3d(
                                            (i & 1) == 0 ? lo.x : hi.x,
                                            (i & 2) == 0 ? lo.y : hi.y,
                                            (i & 4) == 0 ? lo.z : hi.z))
                            .add(center.x, center.y, center.z);
            worldLo.min(q);
            worldHi.max(q);
        }
        for (double value :
                new double[] {worldLo.x, worldLo.y, worldLo.z, worldHi.x, worldHi.y, worldHi.z})
            if (!Double.isFinite(value)
                    || value <= Integer.MIN_VALUE + 1d
                    || value >= Integer.MAX_VALUE - 1d)
                throw new IllegalArgumentException("Deformed area exceeds coordinate bounds.");
        var origin = BlockPos.containing(worldLo.x + 1e-8, worldLo.y + 1e-8, worldLo.z + 1e-8);
        long sx = Math.max(1, (long) Math.ceil(worldHi.x - 1e-8) - origin.getX());
        long sy = Math.max(1, (long) Math.ceil(worldHi.y - 1e-8) - origin.getY());
        long sz = Math.max(1, (long) Math.ceil(worldHi.z - 1e-8) - origin.getZ());
        if ((double) sx * sy * sz > 8_000_000)
            throw new IllegalArgumentException("Deformed area exceeds 8,000,000 cells.");
        var target = new BlockPos((int) sx, (int) sy, (int) sz);
        var orientation = ShapeDeformation.IDENTITY.parameters(angles).direct(deformation);
        var result =
                AreaGeometry.resampleMapped(
                        input.content(),
                        input.mask(),
                        target,
                        orientation,
                        point -> {
                            var q =
                                    inverse.transform(
                                            new Vector3d(
                                                    point.x + origin.getX() - center.x,
                                                    point.y + origin.getY() - center.y,
                                                    point.z + origin.getZ() - center.z));
                            q = deformation.inverse(q, size);
                            return q == null
                                    ? null
                                    : new Vec3(
                                            q.x + size.getX() / 2d,
                                            q.y + size.getY() / 2d,
                                            q.z + size.getZ() / 2d);
                        },
                        point -> {
                            var q =
                                    deformation.forward(
                                            new Vector3d(
                                                    point.x - size.getX() / 2d,
                                                    point.y - size.getY() / 2d,
                                                    point.z - size.getZ() / 2d),
                                            size);
                            rotation.transform(q)
                                    .add(
                                            center.x - origin.getX(),
                                            center.y - origin.getY(),
                                            center.z - origin.getZ());
                            return new Vec3(q.x, q.y, q.z);
                        },
                        continuous,
                        true,
                        allowConfiguredLoss);
        return new RigidAreaRotation.Result(result, new SchematicRegions.Region(origin, target));
    }
}
