package com.eynny.npships.integration.sable;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/** Correct the horizontal vehicle union only after the native attack calculation has finished. */
public final class SableMountedCombat {
    private SableMountedCombat() {}

    public static AABB bounds(Mob rider, AABB nativeBounds) {
        if (!NPShipsRuntimeConfig.mobMountedAttackFixEnabled()
                || !(rider.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return nativeBounds;
        Entity vehicle = rider.getVehicle();
        if (vehicle == null || vehicle.level() != level) return nativeBounds;
        var owner = Sable.HELPER.getContaining(vehicle);
        if (owner == null
                || owner.isRemoved()
                || Sable.HELPER.getContaining(level, rider.position()) != null) return nativeBounds;
        var vehicleBounds = vehicle.getBoundingBox();
        var riderBounds = rider.getBoundingBox();
        if (!finite(nativeBounds)
                || !finite(vehicleBounds)
                || !finite(riderBounds)
                || Sable.HELPER.getContaining(level, vehicleBounds.getCenter()) != owner
                || Sable.HELPER.getContaining(level, riderBounds.getCenter()) != null)
            return nativeBounds;
        var localUnion = riderBounds.minmax(vehicleBounds);
        double left = localUnion.minX - nativeBounds.minX;
        double right = nativeBounds.maxX - localUnion.maxX;
        double front = localUnion.minZ - nativeBounds.minZ;
        double back = nativeBounds.maxZ - localUnion.maxZ;
        // An upstream world-space result no longer encloses the plot-space vehicle. Leave it
        // untouched, along with unfamiliar attack shapes. Native/custom isotropic reach survives.
        if (!Double.isFinite(left)
                || !Double.isFinite(right)
                || !Double.isFinite(front)
                || !Double.isFinite(back)
                || left < 0
                || right < 0
                || front < 0
                || back < 0
                || Math.abs(left - right) > 1e-6
                || Math.abs(left - front) > 1e-6
                || Math.abs(left - back) > 1e-6) return nativeBounds;
        var projected = new BoundingBox3d(vehicleBounds);
        var worldVehicle = projected.transform(owner.logicalPose(), projected).toMojang();
        if (!finite(worldVehicle)) return nativeBounds;
        var worldUnion = riderBounds.minmax(worldVehicle);
        // Rebase only the vehicle's contribution; Y and measured native reach retain their owner.
        return new AABB(
                worldUnion.minX - left,
                nativeBounds.minY,
                worldUnion.minZ - front,
                worldUnion.maxX + right,
                nativeBounds.maxY,
                worldUnion.maxZ + back);
    }

    private static boolean finite(AABB bounds) {
        return Double.isFinite(bounds.minX)
                && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY)
                && Double.isFinite(bounds.maxZ);
    }
}
