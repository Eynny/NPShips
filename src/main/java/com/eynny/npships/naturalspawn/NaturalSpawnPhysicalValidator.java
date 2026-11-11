package com.eynny.npships.naturalspawn;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class NaturalSpawnPhysicalValidator {

    private NaturalSpawnPhysicalValidator() {}

    /** Matches Sable's conservative intersection rule, including touching faces. */
    public static boolean overlapsBounds(
            final AABB bounds, final BlockPos origin, final BlockPos size) {
        return bounds.maxX >= origin.getX()
                && bounds.minX <= (double) origin.getX() + size.getX()
                && bounds.maxY >= origin.getY()
                && bounds.minY <= (double) origin.getY() + size.getY()
                && bounds.maxZ >= origin.getZ()
                && bounds.minZ <= (double) origin.getZ() + size.getZ();
    }

    /** Immutable world-space bounds captured when an obstacle is discovered. */
    public record Overlap(UUID structureId, AABB bounds) {
        public Vec3 position() {
            return new Vec3(bounds.minX, bounds.minY, bounds.minZ);
        }

        public Vec3 size() {
            return new Vec3(bounds.getXsize(), bounds.getYsize(), bounds.getZsize());
        }

        public String message() {
            return "Natural spawn overlaps physical Sable structure "
                    + structureId
                    + " at "
                    + position()
                    + " with bounding size "
                    + size()
                    + ".";
        }
    }

    /**
     * Returns null when the placement is allowed. Returns an error message when it overlaps a
     * loaded Sable structure.
     */
    @Nullable
    public static String findOverlap(
            final ServerLevel level, final BlockPos origin, final BlockPos size) {
        final Overlap overlap = findOverlapDetails(level, origin, size);
        return overlap == null ? null : overlap.message();
    }

    @Nullable
    public static Overlap findOverlapDetails(
            final ServerLevel level, final BlockPos origin, final BlockPos size) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);

        if (container == null) {
            return null;
        }

        for (final SubLevel subLevel : container.getAllSubLevels()) {

            if (subLevel == null || subLevel.isRemoved()) {

                continue;
            }

            var bounds = com.eynny.npships.lifecycle.NPShipsLifecycleQuery.worldBounds(subLevel);
            if (bounds != null && overlapsBounds(bounds, origin, size))
                return new Overlap(subLevel.getUniqueId(), bounds);
        }

        return null;
    }
}
