package com.eynny.npships.authoring.geometry;

import net.minecraft.core.BlockPos;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** GUI-independent projection math; input model matrix includes the current orbit/pan/zoom. */
public final class SchematicViewProjection {
    private SchematicViewProjection() {}

    public static float depth(Matrix4f model, BlockPos size) {
        float depth = 1024;
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    depth =
                            Math.max(
                                    depth,
                                    Math.abs(
                                                    model.transformPosition(
                                                                    new Vector3f(
                                                                            x * size.getX(),
                                                                            y * size.getY(),
                                                                            z * size.getZ()))
                                                            .z)
                                            + 1024);
        return depth;
    }

    public static Matrix4f projection(int width, int height, float depth) {
        return new Matrix4f().setOrtho(0, width, height, 0, -depth, depth);
    }

    /**
     * Perspective in GUI coordinates; the affine model remains usable by native block renderers.
     */
    public static Matrix4f perspective(float cx, float cy, float focal, float scale, float depth) {
        float near = Math.max(.5f, scale * .1f);
        float far = Math.max(focal * 4, depth * 4);
        float a = -depth * (far + near) / (far - near);
        float b = 2 * depth * near * far / (far - near);
        return new Matrix4f()
                .m20(-cx / focal)
                .m21(-cy / focal)
                .m22(-a / focal)
                .m23(-1 / focal)
                .m30(cx * 200 / focal)
                .m31(cy * 200 / focal)
                .m32((a * (focal + 200) + b) / focal)
                .m33((focal + 200) / focal);
    }

    /** Clip before perspective division: segments may cross the eye or near plane. */
    public static boolean projectSegment(Matrix4f matrix, float depth, Vector3f a, Vector3f b) {
        var p = matrix.transform(new org.joml.Vector4f(a, 1));
        var q = matrix.transform(new org.joml.Vector4f(b, 1));
        float pa = depth * p.w - p.z, qa = depth * q.w - q.z;
        if (pa < 0 && qa < 0) return false;
        if (pa < 0) p.lerp(q, pa / (pa - qa));
        else if (qa < 0) q.lerp(p, qa / (qa - pa));
        if (p.w <= 0 || q.w <= 0) return false;
        a.set(p.x / p.w, p.y / p.w, p.z / p.w);
        b.set(q.x / q.w, q.y / q.w, q.z / q.w);
        return a.isFinite() && b.isFinite();
    }

    /** Behind-camera handles must not acquire mirrored screen-space hit targets. */
    public static Vector3f projectHandle(Matrix4f matrix, Vector3f point) {
        var p = matrix.transform(new org.joml.Vector4f(point, 1));
        return p.w > .0001f
                ? new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w)
                : new Vector3f(Float.NaN);
    }

    public record RaySegment(
            net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {}

    /** Clip a finite ray without requiring its origin to be outside the box. */
    public static java.util.Optional<RaySegment> clip(
            net.minecraft.world.phys.AABB box,
            net.minecraft.world.phys.Vec3 from,
            net.minecraft.world.phys.Vec3 to) {
        double near = 0, far = 1;
        double[] a = {from.x, from.y, from.z}, d = {to.x - from.x, to.y - from.y, to.z - from.z};
        double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-12) {
                if (a[axis] < lo[axis] || a[axis] > hi[axis]) return java.util.Optional.empty();
            } else {
                double t0 = (lo[axis] - a[axis]) / d[axis], t1 = (hi[axis] - a[axis]) / d[axis];
                near = Math.max(near, Math.min(t0, t1));
                far = Math.min(far, Math.max(t0, t1));
                if (near > far) return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(new RaySegment(from.lerp(to, near), from.lerp(to, far)));
    }

    /** Inside a volume, pick its forward boundary so dragging still chooses a real face. */
    public static java.util.Optional<net.minecraft.world.phys.Vec3> hit(
            net.minecraft.world.phys.AABB box,
            net.minecraft.world.phys.Vec3 from,
            net.minecraft.world.phys.Vec3 to) {
        return clip(box, from, to)
                .map(segment -> box.contains(from) ? segment.end() : segment.start());
    }

    public static Vector3f direction(Matrix4f camera, Vector3f origin, Vector3f direction) {
        return camera.transformProject(new Vector3f(origin).add(direction))
                .sub(camera.transformProject(new Vector3f(origin)));
    }
}
