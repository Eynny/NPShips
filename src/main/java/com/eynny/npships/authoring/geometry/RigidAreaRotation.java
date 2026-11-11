package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicRegions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Rotate in block units. Only the enclosing integer grid is resized, never the contents. */
public final class RigidAreaRotation {
    public record Result(AreaGeometry.Result geometry, SchematicRegions.Region region) {}

    private RigidAreaRotation() {}

    public static SchematicRegions.Region bounds(BlockPos size, Vec3 center, double[] angles) {
        var rotation = matrix(angles);
        double[] half = {0, 0, 0};
        for (int i = 0; i < 8; i++) {
            var q =
                    rotation.transform(
                            new Vector3d(
                                    (i & 1) == 0 ? -size.getX() / 2d : size.getX() / 2d,
                                    (i & 2) == 0 ? -size.getY() / 2d : size.getY() / 2d,
                                    (i & 4) == 0 ? -size.getZ() / 2d : size.getZ() / 2d));
            half[0] = Math.max(half[0], Math.abs(q.x));
            half[1] = Math.max(half[1], Math.abs(q.y));
            half[2] = Math.max(half[2], Math.abs(q.z));
        }
        var target =
                new BlockPos(
                        Math.max(1, (int) Math.ceil(half[0] * 2 - 1e-8)),
                        Math.max(1, (int) Math.ceil(half[1] * 2 - 1e-8)),
                        Math.max(1, (int) Math.ceil(half[2] * 2 - 1e-8)));
        if ((long) target.getX() * target.getY() * target.getZ() > 8_000_000)
            throw new IllegalArgumentException("Rotated area exceeds 8,000,000 cells.");
        var origin =
                BlockPos.containing(
                        center.x - target.getX() / 2d,
                        center.y - target.getY() / 2d,
                        center.z - target.getZ() / 2d);
        return new SchematicRegions.Region(origin, target);
    }

    private static Matrix3d matrix(double[] angles) {
        return new Matrix3d()
                .rotationXYZ(
                        Math.toRadians(angles[0]),
                        Math.toRadians(angles[1]),
                        Math.toRadians(angles[2]));
    }

    public static Result apply(AreaGeometry.Result input, Vec3 center, double[] angles) {
        return apply(input, center, angles, false);
    }

    public static Result apply(
            AreaGeometry.Result input, Vec3 center, double[] angles, boolean continuous) {
        return apply(input, center, angles, continuous, false);
    }

    public static Result apply(
            AreaGeometry.Result input,
            Vec3 center,
            double[] angles,
            boolean continuous,
            boolean allowConfiguredLoss) {
        var size = input.content().size();
        var region = bounds(size, center, angles);
        var target = region.size();
        var rotation = matrix(angles);
        var inverse = new Matrix3d(rotation).transpose();
        var orientation =
                ShapeDeformation.IDENTITY.parameters(
                        new double[] {angles[0], angles[1], angles[2], 0, 0, 0, 0});
        var result =
                AreaGeometry.resampleMapped(
                        input.content(),
                        input.mask(),
                        target,
                        orientation,
                        p -> {
                            var q =
                                    inverse.transform(
                                            new Vector3d(
                                                    p.x - target.getX() / 2d,
                                                    p.y - target.getY() / 2d,
                                                    p.z - target.getZ() / 2d));
                            return new Vec3(
                                    q.x + size.getX() / 2d,
                                    q.y + size.getY() / 2d,
                                    q.z + size.getZ() / 2d);
                        },
                        p -> {
                            var q =
                                    rotation.transform(
                                            new Vector3d(
                                                    p.x - size.getX() / 2d,
                                                    p.y - size.getY() / 2d,
                                                    p.z - size.getZ() / 2d));
                            return new Vec3(
                                    q.x + target.getX() / 2d,
                                    q.y + target.getY() / 2d,
                                    q.z + target.getZ() / 2d);
                        },
                        continuous,
                        true,
                        allowConfiguredLoss);
        return new Result(result, region);
    }
}
