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

    /** Attempt-owned live preflight; no body list copy or independent ticker. */
    static final class Scan {
        private final ServerLevel level;
        private final BlockPos origin, size;
        private final double yaw;
        private final java.util.function.Predicate<AABB> footprint;
        private int index;
        private long progress;
        private boolean complete;
        private Overlap result;

        Scan(ServerLevel level, BlockPos origin, BlockPos size, double yaw) {
            this.level = level;
            this.origin = origin.immutable();
            this.size = size.immutable();
            this.yaw = yaw;
            footprint =
                    yaw == 0
                            ? bounds -> overlapsBounds(bounds, this.origin, this.size)
                            : com.eynny.npships.ship.SpawnRegionGuard.physicalFootprint(
                                    this.origin, this.size, yaw);
        }

        boolean matches(ServerLevel level, BlockPos origin, BlockPos size, double yaw) {
            return this.level == level
                    && this.origin.equals(origin)
                    && this.size.equals(size)
                    && Double.doubleToLongBits(this.yaw) == Double.doubleToLongBits(yaw);
        }

        boolean step(java.util.function.BooleanSupplier canContinue) {
            while (!complete && canContinue.getAsBoolean()) {
                progress++;
                var container = SubLevelContainer.getContainer(level);
                if (container == null || index >= container.getAllSubLevels().size()) {
                    complete = true;
                    break;
                }
                var body = container.getAllSubLevels().get(index++);
                if (body == null
                        || body.isRemoved()
                        || container.getSubLevel(body.getUniqueId()) != body) continue;
                var bounds = com.eynny.npships.lifecycle.NPShipsLifecycleQuery.worldBounds(body);
                if (bounds != null && footprint.test(bounds)) {
                    result = new Overlap(body.getUniqueId(), bounds);
                    complete = true;
                }
            }
            return complete;
        }

        long progress() {
            return progress;
        }

        Overlap result() {
            if (!complete) throw new IllegalStateException("Physical preflight unfinished");
            return result;
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
        return findOverlapDetails(level, origin, size, java.util.Set.of());
    }

    /** Only this attempt's exact allocated identities are excluded during partial construction. */
    @Nullable
    public static Overlap findOverlapDetails(
            final ServerLevel level,
            final BlockPos origin,
            final BlockPos size,
            final java.util.Set<UUID> ownedPhysicalIds) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);

        if (container == null) {
            return null;
        }

        for (final SubLevel subLevel : container.getAllSubLevels()) {

            if (subLevel == null
                    || subLevel.isRemoved()
                    || ownedPhysicalIds.contains(subLevel.getUniqueId())) {

                continue;
            }

            var bounds = com.eynny.npships.lifecycle.NPShipsLifecycleQuery.worldBounds(subLevel);
            if (bounds != null && overlapsBounds(bounds, origin, size))
                return new Overlap(subLevel.getUniqueId(), bounds);
        }

        return null;
    }
}
