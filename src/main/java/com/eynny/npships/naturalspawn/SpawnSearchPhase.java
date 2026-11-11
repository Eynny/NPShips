package com.eynny.npships.naturalspawn;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.search.NaturalSpawnSearchDriver;
import com.eynny.npships.naturalspawn.search.SpawnAreaScanner;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;
import com.eynny.npships.naturalspawn.search.StructurePlacementSearch;

import net.minecraft.core.BlockPos;

/** One phase of the server-thread attempt; shared state and cleanup remain owned by the attempt. */
final class SpawnSearchPhase {
    private final NaturalSpawnAttempt attempt;

    SpawnSearchPhase(NaturalSpawnAttempt attempt) {
        this.attempt = attempt;
    }

    void initializePrimarySearch() {

        if (attempt.selectedStructure == null
                || attempt.initialPoint == null
                || attempt.level == null) {

            attempt.finishFailed(
                    "Natural spawn internal error: " + "cannot initialize placement search.");

            return;
        }

        final BlockPos size = attempt.selectedStructure.size();
        attempt.prepareSegmentPlan();

        /*
         * Initial radius is related to the actual structure size.
         *
         * The minimum configured radius still applies, so small
         * structures do not collapse the search area too much.
         */
        final int structureHorizontalSpan = Math.max(size.getX(), size.getZ());

        final int structureVerticalSpan = size.getY();

        attempt.currentHorizontalRadius =
                attempt.clampRadius(
                        attempt.ceilHalf(structureHorizontalSpan),
                        NaturalSpawnLimits.min_horizontal_radius(),
                        NaturalSpawnLimits.max_horizontal_radius());

        attempt.currentVerticalRadius =
                attempt.clampRadius(
                        attempt.ceilHalf(structureVerticalSpan),
                        NaturalSpawnLimits.min_vertical_radius(),
                        NaturalSpawnLimits.max_vertical_radius());

        /*
         * Expansion steps are structure-dependent:
         *
         * horizontal = 0.50 * max(X,Z)
         * vertical   = 0.75 * Y
         */
        attempt.horizontalExpansionStep =
                Math.max(1, (int) Math.ceil(structureHorizontalSpan * 0.50D));

        attempt.verticalExpansionStep = Math.max(1, (int) Math.ceil(structureVerticalSpan * 0.75D));

        attempt.activeSearchVolume =
                SpawnVolumes.createSearchVolume(
                        attempt.level,
                        attempt.mode,
                        attempt.initialPoint,
                        attempt.selectedStructure,
                        attempt.currentHorizontalRadius,
                        attempt.currentVerticalRadius);

        attempt.maximumSearchVolume =
                SpawnVolumes.createSearchVolume(
                        attempt.level,
                        attempt.mode,
                        attempt.initialPoint,
                        attempt.selectedStructure,
                        NaturalSpawnLimits.max_horizontal_radius(),
                        NaturalSpawnLimits.max_vertical_radius());

        attempt.areaScanner =
                new SpawnAreaScanner(
                        attempt.level,
                        attempt.maximumSearchVolume,
                        attempt.activeSearchVolume,
                        attempt.initialPoint,
                        true,
                        attempt.snapshotMemory());

        attempt.searchChunkLoader =
                NaturalSpawnChunkLoader.requested(
                        attempt.level,
                        attempt.activeSearchVolume,
                        NaturalSpawnLimits.search_chunk_margin(),
                        attempt.initialPoint,
                        attempt.chunkResources);

        attempt.searchChunkLoader.timing(attempt.timing());
        attempt.areaScanner.timing(attempt.timing());
        attempt.fallbackSearch = false;
        attempt.searchChunkLoader.useRequestedChunks();
        attempt.placementSearch =
                new StructurePlacementSearch(
                        attempt.level,
                        attempt.areaScanner.snapshot(),
                        attempt.activeSearchVolume,
                        attempt.selectedStructure);

        attempt.foundPlacement = null;
    }

    NaturalSpawnSearchDriver.Result stepSearchWork() {
        var result =
                NaturalSpawnSearchDriver.step(
                        attempt.placementSearch,
                        attempt.areaScanner,
                        attempt.searchChunkLoader,
                        attempt.initialPoint,
                        NaturalSpawnLimits.max_search_operations_per_tick(),
                        NaturalSpawnLimits.max_scan_checks_per_tick(),
                        NaturalSpawnLimits.max_chunk_operations_per_tick(),
                        attempt.timing());
        attempt.resourceWork.search(result.search().operationsUsed(), result.scanOperations());
        return result;
    }

    void processPrimarySearch() {

        if (attempt.selectedStructure == null
                || attempt.initialPoint == null
                || attempt.level == null
                || attempt.areaScanner == null
                || attempt.searchChunkLoader == null
                || attempt.placementSearch == null
                || attempt.maximumSearchVolume == null) {

            attempt.finishFailed();
            return;
        }

        final var work = attempt.stepSearchWork();
        final NaturalSpawnSearchResult searchResult = work.search();

        switch (searchResult.status()) {
            case FOUND -> {
                attempt.foundPlacement = searchResult.placement();

                if (!attempt.validateFoundPlacement()) {
                    return;
                }

                attempt.phase = NaturalSpawnAttempt.Phase.PRELOAD_CHUNKS;
            }

            case RUNNING -> {
                /*
                 * Continue.
                 */
            }

            case WAITING_FOR_DATA -> {
                /*
                 * Normal.
                 *
                 * The search has reached the nearest not-yet-scanned
                 * candidate and is waiting for scanner progress.
                 */
            }

            case EXHAUSTED -> {
                if (attempt.expandPrimarySearch()) {
                    return;
                }

                if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

                    attempt.finishFailed(
                            NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED,
                            "Natural spawn rejected at "
                                    + attempt.initialPoint
                                    + ". The requested structure does not "
                                    + "satisfy its natural-spawn environment "
                                    + "conditions within the maximum "
                                    + "configured search area.");

                    return;
                }

                attempt.placementSearch = null;

                attempt.selectedStructure = null;

                attempt.phase = NaturalSpawnAttempt.Phase.SEARCH_FALLBACK;
            }
        }
    }

    boolean expandPrimarySearch() {

        if (attempt.selectedStructure == null
                || attempt.initialPoint == null
                || attempt.level == null
                || attempt.areaScanner == null
                || attempt.searchChunkLoader == null
                || attempt.placementSearch == null) {

            return false;
        }

        final boolean canExpandHorizontal =
                attempt.currentHorizontalRadius < NaturalSpawnLimits.max_horizontal_radius();

        final boolean canExpandVertical =
                attempt.currentVerticalRadius < NaturalSpawnLimits.max_vertical_radius();

        if (!canExpandHorizontal && !canExpandVertical) {

            return false;
        }

        if (canExpandHorizontal) {

            attempt.currentHorizontalRadius =
                    Math.min(
                            NaturalSpawnLimits.max_horizontal_radius(),
                            attempt.currentHorizontalRadius + attempt.horizontalExpansionStep);
        }

        if (canExpandVertical) {

            attempt.currentVerticalRadius =
                    Math.min(
                            NaturalSpawnLimits.max_vertical_radius(),
                            attempt.currentVerticalRadius + attempt.verticalExpansionStep);
        }

        final SpawnSearchVolume expanded =
                SpawnVolumes.createSearchVolume(
                        attempt.level,
                        attempt.mode,
                        attempt.initialPoint,
                        attempt.selectedStructure,
                        attempt.currentHorizontalRadius,
                        attempt.currentVerticalRadius);

        attempt.activeSearchVolume = expanded;

        /*
         * Scanner preserves the old snapshot and scans only blocks
         * which are not already present.
         */
        attempt.areaScanner.setActiveVolume(expanded);

        // Sparse search requests only the chunks containing missing samples.

        /*
         * Search preserves its existing queue and appends only the
         * newly exposed origin-space shells.
         */
        attempt.placementSearch.expandTo(expanded);

        return true;
    }

    void processFallbackSearch() {

        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            attempt.finishFailed("Manual natural spawn unexpectedly " + "entered fallback search.");

            return;
        }

        if (attempt.structureQueue == null
                || attempt.areaScanner == null
                || attempt.initialPoint == null
                || attempt.level == null) {

            attempt.finishFailed();
            return;
        }

        if (attempt.placementSearch == null) {

            if (!attempt.startNextStructure()) {

                attempt.finishFailed(
                        attempt.lastCandidateFailure,
                        "Natural spawn failed: all available " + "structures were exhausted.");

                return;
            }

            attempt.prepareSegmentPlan();
            attempt.fallbackSearch = true;
            initializeFallbackVolume();
            attempt.placementSearch =
                    new StructurePlacementSearch(
                            attempt.level,
                            attempt.areaScanner.snapshot(),
                            attempt.activeSearchVolume,
                            attempt.selectedStructure,
                            StructurePlacementSearch.SearchMode.WAIT_FOR_DATA);
        }

        final var work = attempt.stepSearchWork();
        final NaturalSpawnSearchResult result = work.search();

        switch (result.status()) {
            case FOUND -> {
                attempt.foundPlacement = result.placement();

                if (!attempt.validateFoundPlacement()) {
                    return;
                }

                attempt.phase = NaturalSpawnAttempt.Phase.PRELOAD_CHUNKS;
            }

            case RUNNING -> {
                /*
                 * Continue.
                 */
            }

            case WAITING_FOR_DATA -> {
                // Smaller/different fallback candidates can request missing columns.
            }

            case EXHAUSTED -> {
                attempt.selectedStructure = null;

                attempt.placementSearch = null;
            }
        }
    }

    private void initializeFallbackVolume() {
        var spawnTiming = attempt.timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.ENVELOPE);
        try {
            final SpawnSearchVolume desired =
                    SpawnVolumes.createSearchVolume(
                            attempt.level,
                            attempt.mode,
                            attempt.initialPoint,
                            attempt.selectedStructure,
                            NaturalSpawnLimits.max_horizontal_radius(),
                            NaturalSpawnLimits.max_vertical_radius());
            final SpawnSearchVolume stored = attempt.areaScanner.maximumVolume();
            if (stored.minX() <= desired.minX()
                    && stored.minY() <= desired.minY()
                    && stored.minZ() <= desired.minZ()
                    && stored.maxX() >= desired.maxX()
                    && stored.maxY() >= desired.maxY()
                    && stored.maxZ() >= desired.maxZ()) {
                // Physical storage can be larger than this candidate's valid search domain.
                attempt.areaScanner.setActiveVolume(desired);
            } else {
                // Drop every owner of the old snapshot before allocating the replacement.
                // The attempt's candidate queue and independent yaw observations remain intact.
                if (attempt.searchChunkLoader != null) attempt.searchChunkLoader.release();
                attempt.searchChunkLoader = null;
                if (attempt.areaScanner != null) attempt.areaScanner.close();
                attempt.areaScanner = null;
                attempt.rotationCheck = null;
                attempt.areaScanner =
                        new SpawnAreaScanner(
                                attempt.level,
                                desired,
                                desired,
                                attempt.initialPoint,
                                true,
                                attempt.snapshotMemory());
                attempt.searchChunkLoader =
                        NaturalSpawnChunkLoader.requested(
                                attempt.level,
                                desired,
                                NaturalSpawnLimits.search_chunk_margin(),
                                attempt.initialPoint,
                                attempt.chunkResources);
            }
            attempt.searchChunkLoader.timing(attempt.timing());
            attempt.areaScanner.timing(attempt.timing());
            attempt.maximumSearchVolume = desired;
            attempt.activeSearchVolume = desired;
            attempt.currentHorizontalRadius = NaturalSpawnLimits.max_horizontal_radius();
            attempt.currentVerticalRadius = NaturalSpawnLimits.max_vertical_radius();
            attempt.searchChunkLoader.useRequestedChunks();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }
}
