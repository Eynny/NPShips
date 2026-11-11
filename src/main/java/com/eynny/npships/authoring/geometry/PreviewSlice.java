package com.eynny.npships.authoring.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared visibility, ray bounds and construction plane for editor sections. */
public final class PreviewSlice {
    private PreviewSlice() {}

    public static boolean visible(BlockPos pos, int layer, int mode) {
        int coordinate = mode < 3 ? pos.getY() : pos.getX();
        return switch (mode % 3) {
            case 0 -> coordinate <= layer;
            case 1 -> coordinate == layer;
            default -> coordinate >= layer;
        };
    }

    public static AABB bounds(BlockPos size, int layer, int mode) {
        double min = mode % 3 == 0 ? 0 : layer;
        double max = mode % 3 == 2 ? (mode < 3 ? size.getY() : size.getX()) : layer + 1;
        return mode < 3
                ? new AABB(0, min, 0, size.getX(), max, size.getZ())
                : new AABB(min, 0, 0, max, size.getY(), size.getZ());
    }

    public static Vec3 intersect(Vec3 from, Vec3 to, int layer, int mode) {
        double start = mode < 3 ? from.y : from.x;
        double delta = mode < 3 ? to.y - from.y : to.x - from.x;
        if (Math.abs(delta) < 1e-8) return null;
        int plane = mode % 3 == 0 ? 0 : layer;
        double t = (plane - start) / delta;
        if (t < 0 || t > 1) return null;
        Vec3 hit = from.lerp(to, t);
        // Use the exact integer coordinate to avoid selecting the previous layer by roundoff.
        return mode < 3 ? new Vec3(hit.x, plane, hit.z) : new Vec3(plane, hit.y, hit.z);
    }
}
