package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.InsideRule;
import com.eynny.npships.naturalspawn.NaturalSpawnLimits;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalValidator;
import com.eynny.npships.naturalspawn.NaturalSpawnPlacement;
import com.eynny.npships.naturalspawn.NaturalSpawnSearchResult;
import com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Incremental placement search for one structure.
 *
 * <p>Search pipeline:
 *
 * <p>sparse anchor profiles (combined under/above/inside origin-Y intervals) -> surviving origin
 * tiles, prioritized by minimum possible distance -> exact full-footprint profiles using the same
 * snapshot -> exclusions -> FOUND
 *
 * <p>Primary AND automatic fallback request missing data and retain WAITING tasks. Explicit
 * SNAPSHOT_ONLY callers may search only the known part of a snapshot.
 *
 * <p>The queue stores X/Z together with the corresponding Y range.
 *
 * <p>expandTo() never recreates the queue. It adds only the newly exposed origin-space shells.
 */
public final class StructurePlacementSearch {

    public enum SearchMode {
        WAIT_FOR_DATA,

        SNAPSHOT_ONLY
    }

    private enum InsideCheckStatus {
        COMPLETE,

        NEEDS_MORE_OPERATIONS,

        WAITING_FOR_DATA
    }

    private record InsideCheckResult(InsideCheckStatus status, boolean valid, int operationsUsed) {}

    private final ServerLevel level;

    private final SpawnSpatialSnapshot snapshot;

    private final NaturalSpawnStructureCandidate candidate;

    private final BlockPos size;

    private final int sizeX;

    private final int sizeY;

    private final int sizeZ;

    private final SearchMode searchMode;

    private final List<AABB> excludedPhysicalBounds = new ArrayList<>();

    public SearchMode searchMode() {
        return searchMode;
    }

    /** Continue the existing queue; the rejected origin will be skipped on the next step. */
    public void excludePhysicalBounds(final AABB bounds) {
        excludedPhysicalBounds.add(bounds);
        insideCheck.reset();
    }

    /** Reject the currently returned placement without throwing away the search queue. */
    public void rejectFoundPlacement() {
        insideCheck.reset();
        currentIntervalY++;
    }

    private boolean overlapsExcludedBounds(final BlockPos origin) {
        for (final AABB bounds : excludedPhysicalBounds) {
            if (NaturalSpawnPhysicalValidator.overlapsBounds(bounds, origin, size)) {
                return true;
            }
        }
        return false;
    }

    private final Deque<StructurePlacementSearchRegion> pendingRegions = new ArrayDeque<>();

    /** X/Z + exact origin Y range belonging to this shell. */
    private final PriorityQueue<StructurePlacementSearchColumnTask> columnQueue =
            new PriorityQueue<>(
                    Comparator.comparingLong(this::distanceToPreferredOrigin)
                            .thenComparingInt(StructurePlacementSearchColumnTask::x)
                            .thenComparingInt(StructurePlacementSearchColumnTask::z)
                            .thenComparingInt(StructurePlacementSearchColumnTask::minY)
                            .thenComparingInt(StructurePlacementSearchColumnTask::maxY));

    private final Set<StructurePlacementSearchColumnTask> queuedTasks = new HashSet<>();

    private boolean columnQueueInitialized;
    @Nullable private RegionPreparation regionPreparation;
    private long preparationOperations;
    private long preparedTaskCount;
    private int peakQueuedTaskCount;

    @Nullable private StructurePlacementSearchColumnTask currentTask;

    @Nullable private FootprintEnvironmentProfile underProfile;
    private final PlacementProfileCache placementProfiles;
    private final SparseOriginQueue sparseOrigins;
    private PlacementProfileCache.Task completeProfile;
    private boolean completeDataAvailable;
    private boolean availabilityChecked;

    private boolean missingSurfaceMaterial(EnvironmentRule rule) {
        return rule != null
                && rule.type() == EnvironmentRule.Type.SURFACE
                && (!rule.blocks().isEmpty() || !rule.tags().isEmpty())
                && !snapshot.hasObservedState(
                        state -> EnvironmentBlockMatcher.matchesSurfaceSupport(state, rule));
    }

    /** Upgrade incremental profiles once scanning completes; retain an existing shared task. */
    public void setActiveVolumeComplete(boolean complete) {
        if (complete
                && !completeDataAvailable
                && completeProfile == null
                && currentTask != null
                && currentIntervals == null
                && searchMode == SearchMode.WAIT_FOR_DATA
                && candidate.config().environment().requiresSnapshot()) {
            completeProfile =
                    placementProfiles.task(
                            currentTask.x(),
                            currentTask.z(),
                            currentTask.minY(),
                            currentTask.maxY());
            insideAnchor = null;
            anchorIntervals = null;
            underProfile = null;
            aboveProfile = null;
        }
        completeDataAvailable = complete;
    }

    public String diagnosticState() {
        return "task="
                + currentTask
                + ", queued="
                + columnQueue.size()
                + ", preparing="
                + (regionPreparation != null || !pendingRegions.isEmpty())
                + ", preparationOperations="
                + preparationOperations
                + ", preparedTasks="
                + preparedTaskCount
                + ", peakQueued="
                + peakQueuedTaskCount
                + ", sharedProfiles="
                + (completeProfile != null)
                + ", insideOrigin="
                + insideCheck.origin()
                + (sparseOrigins == null ? "" : ", " + sparseOrigins.diagnosticState());
    }

    private ColumnEnvironmentProfile insideAnchor;
    private List<OriginYInterval> anchorIntervals;
    private final java.util.Map<FootprintEnvironmentProfile.ColumnKey, List<OriginYInterval>>
            profileCache = new java.util.HashMap<>();

    @Nullable private FootprintEnvironmentProfile aboveProfile;

    @Nullable private List<OriginYInterval> currentIntervals;

    private int currentIntervalIndex;

    private int currentIntervalY;

    private final StructurePlacementSearchInsideCheck insideCheck =
            new StructurePlacementSearchInsideCheck();

    private int coveredMinX;

    private int coveredMaxX;

    private int coveredMinY;

    private int coveredMaxY;

    private int coveredMinZ;

    private int coveredMaxZ;

    private boolean exhausted;
    private boolean hasCoveredRegion;

    @Nullable private BlockPos preferredOrigin;

    public StructurePlacementSearch(
            final ServerLevel level,
            final SpawnSpatialSnapshot snapshot,
            final SpawnSearchVolume searchVolume,
            final NaturalSpawnStructureCandidate candidate) {

        this(level, snapshot, searchVolume, candidate, SearchMode.WAIT_FOR_DATA);
    }

    public StructurePlacementSearch(
            final ServerLevel level,
            final SpawnSpatialSnapshot snapshot,
            final SpawnSearchVolume searchVolume,
            final NaturalSpawnStructureCandidate candidate,
            final SearchMode searchMode) {
        this(level, snapshot, searchVolume, candidate, searchMode, true);
    }

    // Dense mode is retained as an independent exhaustive reference in regression tests.
    StructurePlacementSearch(
            ServerLevel level,
            SpawnSpatialSnapshot snapshot,
            SpawnSearchVolume searchVolume,
            NaturalSpawnStructureCandidate candidate,
            SearchMode searchMode,
            boolean sparse) {

        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot cannot be null.");
        }

        if (searchVolume == null) {
            throw new IllegalArgumentException("searchVolume cannot be null.");
        }

        if (candidate == null) {
            throw new IllegalArgumentException("candidate cannot be null.");
        }

        if (searchMode == null) {
            throw new IllegalArgumentException("searchMode cannot be null.");
        }

        this.level = level;

        this.snapshot = snapshot;

        this.candidate = candidate;
        placementProfiles = new PlacementProfileCache(level, snapshot, candidate);
        completeDataAvailable = snapshot.isComplete();

        this.size = candidate.size().immutable();

        this.sizeX = size.getX();

        this.sizeY = size.getY();

        this.sizeZ = size.getZ();

        this.searchMode = searchMode;

        sparseOrigins =
                sparse && candidate.config().environment().requiresSnapshot()
                        ? new SparseOriginQueue(
                                sizeX, sizeZ, placementProfiles, () -> this.preferredOrigin)
                        : null;

        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {

            throw new IllegalArgumentException("Structure size must be positive: " + size);
        }

        final StructurePlacementSearchRegion initialRegion = toOriginRegion(searchVolume);

        if (initialRegion.isEmpty()) {

            exhausted = true;
            return;
        }

        coveredMinX = initialRegion.minX();
        hasCoveredRegion = true;

        coveredMaxX = initialRegion.maxX();

        coveredMinY = initialRegion.minY();

        coveredMaxY = initialRegion.maxY();

        coveredMinZ = initialRegion.minZ();

        coveredMaxZ = initialRegion.maxZ();

        // Preserve the native zero-work rejection for a single impossible height
        // restriction. General restriction lists are processed incrementally below.
        var heights = candidate.config().restrictions().heightRanges();
        if (heights.size() == 1
                && (heights.getFirst().min() > initialRegion.maxY()
                        || heights.getFirst().max() < initialRegion.minY())) {
            exhausted = true;
        } else {
            pendingRegions.addLast(initialRegion);
        }
    }

    public NaturalSpawnSearchResult step(
            final int maxOperations, @Nullable final BlockPos preferredOrigin) {

        if (exhausted) {

            return NaturalSpawnSearchResult.exhausted(0);
        }

        if (maxOperations <= 0) {

            return NaturalSpawnSearchResult.running(0);
        }

        if (completeDataAvailable && !availabilityChecked) {
            availabilityChecked = true;
            var environment = candidate.config().environment();
            if (missingSurfaceMaterial(environment.under())
                    || missingSurfaceMaterial(environment.above())) {
                // A necessary material is absent from the entire scanned snapshot.
                // Only conclude this once the active volume is complete, never from a partial scan.
                rejectCurrentTask();
                columnQueue.clear();
                queuedTasks.clear();
                pendingRegions.clear();
                regionPreparation = null;
                if (sparseOrigins != null) sparseOrigins.clear();
                exhausted = true;
                if (NaturalSpawnLimits.dev_diagnostics())
                    com.mojang.logging.LogUtils.getLogger()
                            .info(
                                    "[NPShips NaturalSpawn] Environment exhausted: structure={}, required surface material absent from scanned volume",
                                    candidate.commandName());
                return NaturalSpawnSearchResult.exhausted(1);
            }
        }

        if (!columnQueueInitialized) {

            if (this.preferredOrigin == null && preferredOrigin != null) {

                this.preferredOrigin = preferredOrigin.immutable();
            }

            columnQueueInitialized = true;
        }

        // Complete all pending insertions before comparing heap heads. Otherwise a
        // not-yet-created nearer origin could lose to an already inserted one.
        int operations = materializePendingRegions(maxOperations);
        if (operations == maxOperations) return NaturalSpawnSearchResult.running(operations);

        while (operations < maxOperations) {

            /*
             * -----------------------------------------------------
             * NEXT COLUMN TASK
             * -----------------------------------------------------
             */

            if (currentTask == null && currentIntervals == null) {

                if (sparseOrigins != null) {
                    long distance =
                            columnQueue.isEmpty()
                                    ? Long.MAX_VALUE
                                    : distanceToPreferredOrigin(columnQueue.peek());
                    if (sparseOrigins.precedes(distance)) {
                        var prepared =
                                sparseOrigins.prepare(
                                        distance,
                                        maxOperations - operations,
                                        searchMode == SearchMode.SNAPSHOT_ONLY,
                                        this::enqueueTask);
                        operations += prepared.operationsUsed();
                        if (prepared.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA)
                            return NaturalSpawnSearchResult.waitingForData(operations);
                        if (prepared.status() == ColumnEnvironmentProfile.Status.RUNNING)
                            return NaturalSpawnSearchResult.running(operations);
                        // Re-evaluate tile lower bounds after new origins were emitted.
                        continue;
                    }
                }

                if (!prepareNextColumn()) {

                    exhausted = true;

                    return NaturalSpawnSearchResult.exhausted(operations);
                }
            }

            if (completeProfile != null && currentIntervals == null) {
                var result = completeProfile.step(maxOperations - operations);
                operations += result.operationsUsed();
                if (result.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA) {
                    if (searchMode == SearchMode.SNAPSHOT_ONLY) {
                        rejectCurrentTask();
                        operations++;
                        continue;
                    }
                    return NaturalSpawnSearchResult.waitingForData(operations);
                }
                if (result.status() != ColumnEnvironmentProfile.Status.COMPLETE)
                    return NaturalSpawnSearchResult.running(operations);
                currentIntervals = completeProfile.intervals();
                if (currentIntervals.isEmpty()) {
                    rejectCurrentTask();
                    continue;
                }
                prioritizeYIntervals(preferredOrigin);
                currentIntervalIndex = 0;
                currentIntervalY = currentIntervals.getFirst().minY();
            }

            // Reject blocked origin columns before checking thousands of footprint columns.
            // This is a necessary condition only; the complete inside validation still follows.
            if (insideAnchor != null && anchorIntervals == null) {
                var result = insideAnchor.step(maxOperations - operations);
                operations += result.operationsUsed();
                if (result.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA) {
                    if (searchMode == SearchMode.WAIT_FOR_DATA)
                        return NaturalSpawnSearchResult.waitingForData(operations);
                    rejectCurrentTask();
                    continue;
                }
                if (result.status() != ColumnEnvironmentProfile.Status.COMPLETE)
                    return NaturalSpawnSearchResult.running(operations);
                anchorIntervals =
                        insideAnchor.intervals().stream()
                                .map(v -> new OriginYInterval(v.minY() - sizeY, v.maxY() - sizeY))
                                .toList();
                if (anchorIntervals.isEmpty()) {
                    rejectCurrentTask();
                    continue;
                }
            }

            if (operations >= maxOperations) return NaturalSpawnSearchResult.running(operations);
            /*
             * -----------------------------------------------------
             * UNDER
             * -----------------------------------------------------
             */

            if (underProfile != null && !underProfile.isComplete()) {

                final int remaining = maxOperations - operations;

                final ColumnEnvironmentProfile.StepResult result =
                        underProfile.step(Math.max(1, remaining));

                operations += result.operationsUsed();

                if (result.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA) {

                    if (searchMode == SearchMode.WAIT_FOR_DATA) {

                        return NaturalSpawnSearchResult.waitingForData(operations);
                    }

                    rejectCurrentTask();
                    continue;
                }

                if (result.status() == ColumnEnvironmentProfile.Status.RUNNING) {

                    return NaturalSpawnSearchResult.running(operations);
                }
            }

            /*
             * -----------------------------------------------------
             * ABOVE
             * -----------------------------------------------------
             */

            if (aboveProfile != null && !aboveProfile.isComplete()) {

                final int remaining = maxOperations - operations;

                if (remaining <= 0) {

                    return NaturalSpawnSearchResult.running(operations);
                }

                final ColumnEnvironmentProfile.StepResult result = aboveProfile.step(remaining);

                operations += result.operationsUsed();

                if (result.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA) {

                    if (searchMode == SearchMode.WAIT_FOR_DATA) {

                        return NaturalSpawnSearchResult.waitingForData(operations);
                    }

                    rejectCurrentTask();
                    continue;
                }

                if (result.status() == ColumnEnvironmentProfile.Status.RUNNING) {

                    return NaturalSpawnSearchResult.running(operations);
                }
            }

            /*
             * -----------------------------------------------------
             * INTERSECT ENVIRONMENT INTERVALS
             * -----------------------------------------------------
             */

            if (currentIntervals == null) {

                final List<OriginYInterval> underIntervals = safeIntervals(underProfile);

                final List<OriginYInterval> aboveIntervals = safeIntervals(aboveProfile);

                currentIntervals =
                        OriginYIntervals.intersectIntervals(underIntervals, aboveIntervals);

                if (anchorIntervals != null)
                    currentIntervals =
                            OriginYIntervals.intersectIntervals(currentIntervals, anchorIntervals);

                currentIntervalIndex = 0;

                if (currentIntervals.isEmpty()) {

                    rejectCurrentTask();
                    continue;
                }

                // Start near the preferred Y, but retain the lower part as well.
                // Otherwise a rejected origin could hide valid positions below it.
                prioritizeYIntervals(preferredOrigin);
                currentIntervalY = currentIntervals.getFirst().minY();
            }

            /*
             * -----------------------------------------------------
             * TEST EACH Y ORIGIN
             * -----------------------------------------------------
             */

            while (currentIntervalIndex < currentIntervals.size()) {

                final OriginYInterval interval = currentIntervals.get(currentIntervalIndex);

                if (currentIntervalY > interval.maxY()) {

                    currentIntervalIndex++;

                    if (currentIntervalIndex >= currentIntervals.size()) {

                        break;
                    }

                    currentIntervalY = currentIntervals.get(currentIntervalIndex).minY();

                    continue;
                }

                if (currentIntervalY < interval.minY()) {

                    currentIntervalY = interval.minY();
                }

                if (currentTask == null) {

                    throw new IllegalStateException(
                            "Current origin Y exists without " + "a current column task.");
                }

                final BlockPos origin =
                        new BlockPos(currentTask.x(), currentIntervalY, currentTask.z());

                if (!insideCheck.isActive()) {
                    if (operations >= maxOperations) {
                        return NaturalSpawnSearchResult.running(operations);
                    }
                    operations++;
                    if (!candidate.config().restrictions().allowsOriginY(origin.getY())
                            || overlapsExcludedBounds(origin)) {
                        currentIntervalY++;
                        continue;
                    }
                }

                prepareInsideCheckOrigin(origin);

                final InsideCheckResult insideResult =
                        processInsideCheck(origin, maxOperations - operations);

                operations += insideResult.operationsUsed();

                if (insideResult.status() == InsideCheckStatus.WAITING_FOR_DATA) {

                    if (searchMode == SearchMode.WAIT_FOR_DATA) {

                        return NaturalSpawnSearchResult.waitingForData(operations);
                    }

                    insideCheck.reset();

                    currentIntervalY++;

                    continue;
                }

                if (insideResult.status() == InsideCheckStatus.NEEDS_MORE_OPERATIONS) {

                    return NaturalSpawnSearchResult.running(operations);
                }

                insideCheck.reset();

                if (insideResult.valid()) {

                    return NaturalSpawnSearchResult.found(
                            new NaturalSpawnPlacement(origin), operations);
                }

                currentIntervalY++;
            }

            clearCurrentTaskState();
        }

        return NaturalSpawnSearchResult.running(operations);
    }

    private int materializePendingRegions(int budget) {
        int used = 0;
        while (used < budget && (regionPreparation != null || !pendingRegions.isEmpty())) {
            if (regionPreparation == null) {
                regionPreparation = new RegionPreparation(pendingRegions.removeFirst());
            } else if (regionPreparation.step()) {
                regionPreparation = null;
            }
            used++;
        }
        preparationOperations += used;
        return used;
    }

    private void enqueueTask(StructurePlacementSearchColumnTask task) {
        preparedTaskCount++;
        if (queuedTasks.add(task)) {
            columnQueue.add(task);
            peakQueuedTaskCount = Math.max(peakQueuedTaskCount, columnQueue.size());
        }
    }

    /** One unit initializes a region, clips/merges one height range, or inserts one task/tile. */
    private final class RegionPreparation {
        private final StructurePlacementSearchRegion region;
        private final PriorityQueue<OriginYInterval> clipped =
                new PriorityQueue<>(Comparator.comparingInt(OriginYInterval::minY));
        private final List<OriginYInterval> heights = new ArrayList<>();
        private int restrictionIndex, x, z, height;
        private boolean heightsReady;
        @Nullable private SparseOriginQueue.Addition tiles;

        RegionPreparation(StructurePlacementSearchRegion region) {
            this.region = region;
            x = region.minX();
            z = region.minZ();
        }

        boolean step() {
            if (!heightsReady) {
                var restrictions = candidate.config().restrictions().heightRanges();
                if (restrictions.isEmpty()) {
                    heights.add(new OriginYInterval(region.minY(), region.maxY()));
                    heightsReady = true;
                } else if (restrictionIndex < restrictions.size()) {
                    var range = restrictions.get(restrictionIndex++);
                    int lo = Math.max(region.minY(), range.min());
                    int hi = Math.min(region.maxY(), range.max());
                    if (lo <= hi) clipped.add(new OriginYInterval(lo, hi));
                    if (restrictionIndex == restrictions.size() && clipped.isEmpty()) return true;
                } else {
                    var range = clipped.remove();
                    if (!heights.isEmpty()
                            && (long) range.minY() <= (long) heights.getLast().maxY() + 1) {
                        var previous = heights.removeLast();
                        heights.add(
                                new OriginYInterval(
                                        previous.minY(), Math.max(previous.maxY(), range.maxY())));
                    } else heights.add(range);
                    heightsReady = clipped.isEmpty();
                }
                return false;
            }
            if (sparseOrigins != null) {
                if (tiles == null) tiles = sparseOrigins.add(region, heights);
                return tiles.step();
            }
            var y = heights.get(height);
            enqueueTask(new StructurePlacementSearchColumnTask(x, z, y.minY(), y.maxY()));
            if (++height == heights.size()) {
                height = 0;
                if (++z > region.maxZ()) {
                    z = region.minZ();
                    x++;
                }
            }
            return x > region.maxX();
        }
    }

    private long distanceToPreferredOrigin(final StructurePlacementSearchColumnTask task) {

        if (preferredOrigin == null) {
            return 0L;
        }

        final long dx = (long) task.x() - preferredOrigin.getX();

        final long dz = (long) task.z() - preferredOrigin.getZ();

        return dx * dx + dz * dz;
    }

    private boolean prepareNextColumn() {

        final StructurePlacementSearchColumnTask task = columnQueue.poll();

        if (task == null) {

            return false;
        }

        queuedTasks.remove(task);

        currentTask = task;

        completeProfile =
                (sparseOrigins != null
                                        || completeDataAvailable
                                        || searchMode == SearchMode.WAIT_FOR_DATA
                                                && (long) sizeX * sizeZ >= 4096
                                                && (candidate.config().environment().under() != null
                                                        || candidate.config().environment().above()
                                                                != null))
                                && candidate.config().environment().requiresSnapshot()
                        ? placementProfiles.task(task.x(), task.z(), task.minY(), task.maxY())
                        : null;
        if (completeProfile != null) {
            insideAnchor = null;
            anchorIntervals = null;
            underProfile = null;
            aboveProfile = null;
            currentIntervals = null;
            currentIntervalIndex = 0;
            currentIntervalY = 0;
            insideCheck.reset();
            return true;
        }

        anchorIntervals = null;
        var inside = candidate.config().environment().inside();
        insideAnchor =
                searchMode == SearchMode.SNAPSHOT_ONLY
                                || inside == null
                                || inside.type() == InsideRule.Type.IGNORE
                        ? null
                        : new ColumnEnvironmentProfile(
                                level,
                                snapshot,
                                task.x(),
                                task.z(),
                                new EnvironmentRule(
                                        inside.type() == InsideRule.Type.CLEAR
                                                ? EnvironmentRule.Type.AIR
                                                : EnvironmentRule.Type.FLUID,
                                        sizeY,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        false,
                                        false,
                                        inside.fluid()),
                                false,
                                sizeY,
                                task.minY() + sizeY,
                                task.maxY() + sizeY);

        // Do not turn an incremental search into a wait for an entire column.
        if (insideAnchor != null
                && !snapshot.isColumnRangeComplete(
                        task.x(), task.z(), task.minY(), task.maxY() + sizeY - 1))
            insideAnchor = null;
        underProfile = createEnvironmentProfile(false);

        aboveProfile = createEnvironmentProfile(true);

        currentIntervals = null;

        currentIntervalIndex = 0;

        currentIntervalY = 0;

        insideCheck.reset();

        return true;
    }

    private FootprintEnvironmentProfile createEnvironmentProfile(final boolean above) {

        if (currentTask == null) {

            throw new IllegalStateException(
                    "Cannot create environment profile without " + "a current column task.");
        }

        final var environment = candidate.config().environment();

        final EnvironmentRule rule = above ? environment.above() : environment.under();

        if (rule == null || rule.type() == EnvironmentRule.Type.NONE) return null;
        return new FootprintEnvironmentProfile(
                level,
                snapshot,
                currentTask.x(),
                currentTask.z(),
                rule,
                above,
                sizeY,
                currentTask.minY(),
                currentTask.maxY(),
                sizeX,
                sizeZ,
                profileCache);
    }

    private List<OriginYInterval> safeIntervals(
            @Nullable final FootprintEnvironmentProfile profile) {

        if (profile == null) {
            return List.of(new OriginYInterval(currentTask.minY(), currentTask.maxY()));
        }

        final List<OriginYInterval> intervals = profile.intervals();

        return intervals == null ? List.of() : intervals;
    }

    /**
     * Intersects under and above constraints.
     *
     * <p>Empty means there is no valid Y origin.
     *
     * <p>NONE does not produce empty intervals, because ColumnEnvironmentProfile returns the full
     * origin range for NONE.
     */
    private void rejectCurrentTask() {

        clearCurrentTaskState();
    }

    private void clearCurrentTaskState() {
        completeProfile = null;

        currentTask = null;

        underProfile = null;

        aboveProfile = null;

        currentIntervals = null;

        currentIntervalIndex = 0;

        currentIntervalY = 0;

        insideCheck.reset();
    }

    private void prepareInsideCheckOrigin(final BlockPos origin) {

        if (!insideCheck.isActive()) {

            insideCheck.start(origin);

            return;
        }

        final BlockPos previousOrigin = insideCheck.origin();

        if (previousOrigin == null || !previousOrigin.equals(origin)) {

            insideCheck.start(origin);
        }
    }

    private InsideCheckResult processInsideCheck(final BlockPos origin, final int maxOperations) {
        if (completeProfile != null)
            return new InsideCheckResult(InsideCheckStatus.COMPLETE, true, 0);

        final InsideRule inside = candidate.config().environment().inside();

        if (inside == null || inside.type() == InsideRule.Type.IGNORE) {

            return new InsideCheckResult(InsideCheckStatus.COMPLETE, true, 0);
        }

        if (maxOperations <= 0) {

            return new InsideCheckResult(InsideCheckStatus.NEEDS_MORE_OPERATIONS, false, 0);
        }

        int operations = 0;

        while (!insideCheck.isFinished(sizeX)) {

            if (operations >= maxOperations) {

                return new InsideCheckResult(
                        InsideCheckStatus.NEEDS_MORE_OPERATIONS, false, operations);
            }

            final BlockPos pos = insideCheck.currentPos(origin);

            if (!snapshot.isScanned(pos)) {

                if (searchMode == SearchMode.WAIT_FOR_DATA) {

                    return new InsideCheckResult(
                            InsideCheckStatus.WAITING_FOR_DATA, false, operations);
                }

                return new InsideCheckResult(InsideCheckStatus.COMPLETE, false, operations);
            }

            final BlockState state = snapshot.getState(pos);

            operations++;

            final boolean valid;

            switch (inside.type()) {
                case IGNORE -> {
                    return new InsideCheckResult(InsideCheckStatus.COMPLETE, true, operations);
                }

                case CLEAR -> {
                    valid = state.isAir();
                }

                case FLUID -> {
                    final var fluidState = state.getFluidState();

                    boolean fluidValid = !fluidState.isEmpty() && fluidState.isSource();

                    if (fluidValid && inside.fluid() != null) {

                        fluidValid =
                                fluidState
                                        .getType()
                                        .builtInRegistryHolder()
                                        .key()
                                        .location()
                                        .equals(inside.fluid());
                    }

                    valid = fluidValid;
                }

                default -> {
                    valid = false;
                }
            }

            if (!valid) {

                return new InsideCheckResult(InsideCheckStatus.COMPLETE, false, operations);
            }

            insideCheck.advance(sizeX, sizeY, sizeZ);
        }

        return new InsideCheckResult(InsideCheckStatus.COMPLETE, true, operations);
    }

    public void expandTo(final SpawnSearchVolume newVolume) {
        availabilityChecked = false;
        completeDataAvailable = snapshot.isComplete();

        if (newVolume == null) {

            throw new IllegalArgumentException("newVolume cannot be null.");
        }

        final StructurePlacementSearchRegion newBounds = toOriginRegion(newVolume);

        if (newBounds.isEmpty()) {
            return;
        }

        if (!hasCoveredRegion) {
            coveredMinX = newBounds.minX();
            coveredMaxX = newBounds.maxX();
            coveredMinY = newBounds.minY();
            coveredMaxY = newBounds.maxY();
            coveredMinZ = newBounds.minZ();
            coveredMaxZ = newBounds.maxZ();
            hasCoveredRegion = true;
            addRegion(coveredMinX, coveredMaxX, coveredMinY, coveredMaxY, coveredMinZ, coveredMaxZ);
            exhausted = false;
            return;
        }

        final int oldMinX = coveredMinX;

        final int oldMaxX = coveredMaxX;

        final int oldMinY = coveredMinY;

        final int oldMaxY = coveredMaxY;

        final int oldMinZ = coveredMinZ;

        final int oldMaxZ = coveredMaxZ;

        /*
         * No actual expansion.
         */
        if (newBounds.minX() >= oldMinX
                && newBounds.maxX() <= oldMaxX
                && newBounds.minY() >= oldMinY
                && newBounds.maxY() <= oldMaxY
                && newBounds.minZ() >= oldMinZ
                && newBounds.maxZ() <= oldMaxZ) {

            return;
        }

        /*
         * ---------------------------------------------------------
         * X SHELLS
         * ---------------------------------------------------------
         */

        if (newBounds.minX() < oldMinX) {

            addRegion(
                    newBounds.minX(),
                    oldMinX - 1,
                    newBounds.minY(),
                    newBounds.maxY(),
                    newBounds.minZ(),
                    newBounds.maxZ());
        }

        if (newBounds.maxX() > oldMaxX) {

            addRegion(
                    oldMaxX + 1,
                    newBounds.maxX(),
                    newBounds.minY(),
                    newBounds.maxY(),
                    newBounds.minZ(),
                    newBounds.maxZ());
        }

        final int overlapMinX = Math.max(newBounds.minX(), oldMinX);

        final int overlapMaxX = Math.min(newBounds.maxX(), oldMaxX);

        if (overlapMinX <= overlapMaxX) {

            /*
             * -----------------------------------------------------
             * Z SHELLS
             * -----------------------------------------------------
             */

            if (newBounds.minZ() < oldMinZ) {

                addRegion(
                        overlapMinX,
                        overlapMaxX,
                        newBounds.minY(),
                        newBounds.maxY(),
                        newBounds.minZ(),
                        oldMinZ - 1);
            }

            if (newBounds.maxZ() > oldMaxZ) {

                addRegion(
                        overlapMinX,
                        overlapMaxX,
                        newBounds.minY(),
                        newBounds.maxY(),
                        oldMaxZ + 1,
                        newBounds.maxZ());
            }

            final int overlapMinZ = Math.max(newBounds.minZ(), oldMinZ);

            final int overlapMaxZ = Math.min(newBounds.maxZ(), oldMaxZ);

            if (overlapMinZ <= overlapMaxZ) {

                /*
                 * -------------------------------------------------
                 * LOWER Y SHELL
                 * -------------------------------------------------
                 */

                if (newBounds.minY() < oldMinY) {

                    addRegion(
                            overlapMinX,
                            overlapMaxX,
                            newBounds.minY(),
                            oldMinY - 1,
                            overlapMinZ,
                            overlapMaxZ);
                }

                /*
                 * -------------------------------------------------
                 * UPPER Y SHELL
                 * -------------------------------------------------
                 */

                if (newBounds.maxY() > oldMaxY) {

                    addRegion(
                            overlapMinX,
                            overlapMaxX,
                            oldMaxY + 1,
                            newBounds.maxY(),
                            overlapMinZ,
                            overlapMaxZ);
                }
            }
        }

        /*
         * ---------------------------------------------------------
         * UPDATE COVERED ORIGIN SPACE
         * ---------------------------------------------------------
         */

        coveredMinX = Math.min(coveredMinX, newBounds.minX());

        coveredMaxX = Math.max(coveredMaxX, newBounds.maxX());

        coveredMinY = Math.min(coveredMinY, newBounds.minY());

        coveredMaxY = Math.max(coveredMaxY, newBounds.maxY());

        coveredMinZ = Math.min(coveredMinZ, newBounds.minZ());

        coveredMaxZ = Math.max(coveredMaxZ, newBounds.maxZ());

        /*
         * IMPORTANT:
         *
         * Keep the initialized reference and existing queue. New shells are
         * materialized under the next step's shared search budget.
         */
        exhausted = false;
    }

    private void addRegion(
            final int minX,
            final int maxX,
            final int minY,
            final int maxY,
            final int minZ,
            final int maxZ) {

        if (minX > maxX || minY > maxY || minZ > maxZ) {

            return;
        }

        final StructurePlacementSearchRegion region =
                new StructurePlacementSearchRegion(minX, maxX, minY, maxY, minZ, maxZ);

        pendingRegions.addLast(region);
    }

    private void prioritizeYIntervals(@Nullable final BlockPos preferredOrigin) {
        if (preferredOrigin == null) {
            return;
        }
        final int preferredY = preferredOrigin.getY();
        final List<OriginYInterval> ordered = new ArrayList<>(currentIntervals);
        ordered.sort(
                Comparator.comparingInt(
                        interval -> OriginYIntervals.distanceToInterval(preferredY, interval)));
        final OriginYInterval first = ordered.removeFirst();
        final int startY = first.clamp(preferredY);
        ordered.addFirst(new OriginYInterval(startY, first.maxY()));
        if (startY > first.minY()) {
            ordered.add(1, new OriginYInterval(first.minY(), startY - 1));
        }
        currentIntervals = ordered;
    }

    private StructurePlacementSearchRegion toOriginRegion(final SpawnSearchVolume volume) {

        final var environment = candidate.config().environment();

        final EnvironmentRule under = environment.under();

        final EnvironmentRule above = environment.above();

        final int underDepth = getRuleDepth(under);

        final int aboveDepth = getRuleDepth(above);

        /*
         * Origin Y must leave room for the environment checks.
         */
        final long maxX = (long) volume.maxX() - sizeX + 1;
        final long maxZ = (long) volume.maxZ() - sizeZ + 1;
        final long minY =
                Math.max((long) level.getMinBuildHeight(), (long) volume.minY() + underDepth);
        final long maxY =
                Math.min(
                        (long) level.getMaxBuildHeight() - sizeY,
                        (long) volume.maxY() - sizeY + 1 - aboveDepth);
        if (maxX < volume.minX() || maxZ < volume.minZ() || minY > maxY) {
            return new StructurePlacementSearchRegion(0, -1, 0, -1, 0, -1);
        }
        // Positive sizes and the non-empty comparisons prove these bounds fit int.
        return new StructurePlacementSearchRegion(
                volume.minX(), (int) maxX, (int) minY, (int) maxY, volume.minZ(), (int) maxZ);
    }

    private static int getRuleDepth(@Nullable final EnvironmentRule rule) {

        if (rule == null) {
            return 0;
        }

        return Math.max(0, rule.depth());
    }
}
