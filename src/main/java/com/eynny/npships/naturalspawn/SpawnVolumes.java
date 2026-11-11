package com.eynny.npships.naturalspawn;

import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;

import net.minecraft.core.BlockPos;

/** Origin-space and world-volume conversion; no tickets or mutable attempt state. */
final class SpawnVolumes {
    private SpawnVolumes() {}

    private static int coordinate(long value) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Search coordinate exceeds supported integer range: " + value, overflow);
        }
    }

    static SpawnSearchVolume createSearchVolume(
            final net.minecraft.server.level.ServerLevel level,
            final NaturalSpawnAttemptMode mode,
            final BlockPos center,
            final NaturalSpawnStructureCandidate candidate,
            final int horizontalRadius,
            final int verticalRadius) {
        if (level == null) {
            throw new IllegalStateException("Natural spawn level is not initialized.");
        }
        final var environment = candidate.config().environment();
        final int underDepth =
                environment.under() == null ? 0 : Math.max(0, environment.under().depth());
        final int aboveDepth =
                environment.above() == null ? 0 : Math.max(0, environment.above().depth());
        final int virtualUnder =
                environment.under() != null
                                && environment.under().type()
                                        == com.eynny.npships.config.EnvironmentRule.Type.AIR
                        ? underDepth
                        : 0;
        final int virtualAbove =
                environment.above() != null
                                && environment.above().type()
                                        == com.eynny.npships.config.EnvironmentRule.Type.AIR
                        ? aboveDepth
                        : 0;
        final int centerY =
                mode == NaturalSpawnAttemptMode.AUTOMATIC
                        ? automaticReferenceY(
                                level, center.getY(), candidate.config(), candidate.size().getY())
                        : center.getY();
        /*
         * The search volume is symmetric around the reference point
         * in origin-space.
         *
         * Environment depth is added to the raw scanned world volume,
         * because under/above validation needs data outside the
         * physical structure footprint.
         */
        final int minY =
                coordinate(
                        Math.max(
                                (long) level.getMinBuildHeight() - virtualUnder,
                                (long) centerY - verticalRadius - underDepth));
        final int maxY =
                coordinate(
                        Math.min(
                                (long) level.getMaxBuildHeight() - 1 + virtualAbove,
                                (long) centerY
                                        + verticalRadius
                                        + candidate.size().getY()
                                        - 1
                                        + aboveDepth));
        if (minY > maxY) {
            throw new IllegalArgumentException(
                    "Natural spawn search volume lies outside " + "the world's build height.");
        }
        return new SpawnSearchVolume(
                coordinate((long) center.getX() - horizontalRadius),
                minY,
                coordinate((long) center.getZ() - horizontalRadius),
                coordinate((long) center.getX() + horizontalRadius + candidate.size().getX() - 1),
                maxY,
                coordinate((long) center.getZ() + horizontalRadius + candidate.size().getZ() - 1));
    }

    /** Share candidate centering with eligibility; the raw initial point remains pinned. */
    static int automaticReferenceY(
            net.minecraft.server.level.ServerLevel level,
            int requestedY,
            com.eynny.npships.config.NPShipsStructureConfig config,
            int structureHeight) {
        final var environment = config.environment();
        return NaturalSpawnAttemptContext.automaticReferenceY(
                level,
                requestedY,
                structureHeight,
                reservedDepth(environment.under()),
                reservedDepth(environment.above()));
    }

    private static int reservedDepth(com.eynny.npships.config.EnvironmentRule rule) {
        return rule == null || rule.type() == com.eynny.npships.config.EnvironmentRule.Type.AIR
                ? 0
                : Math.max(0, rule.depth());
    }

    static SpawnSearchVolume createStructureVolume(
            final NaturalSpawnStructureCandidate candidate, final NaturalSpawnPlacement placement) {
        final BlockPos origin = placement.origin();
        final BlockPos size = candidate.size();
        return new SpawnSearchVolume(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                coordinate((long) origin.getX() + size.getX() - 1),
                coordinate((long) origin.getY() + size.getY() - 1),
                coordinate((long) origin.getZ() + size.getZ() - 1));
    }
}
