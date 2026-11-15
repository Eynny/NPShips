package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Incremental raw-world scanner.
 *
 * <p>Natural spawn uses demand-driven column requests ordered by the placement search. The dense
 * mode remains available for explicit full-volume consumers.
 *
 * <p>Inside one column, Y is scanned continuously.
 *
 * <p>Already scanned blocks are never read from the world again.
 */
public final class SpawnAreaScanner implements AutoCloseable {
    @Override
    public void close() {
        snapshot.close();
    }

    public enum Status {
        RUNNING,
        COMPLETE,
        WAITING_FOR_UNLOADED_CHUNK
    }

    public record StepResult(Status status, int checksPerformed) {}

    private record ColumnTask(int x, int z) {}

    private final ServerLevel level;

    private final SpawnSpatialSnapshot snapshot;

    private final PriorityQueue<ColumnTask> columnQueue;

    private final Set<ColumnTask> queuedColumns = new HashSet<>();

    private SpawnSearchVolume activeVolume;

    @Nullable private final BlockPos preferredOrigin;

    @Nullable private ColumnTask currentColumn;

    private int currentY;
    private final SpawnColumnReader requestedReader;
    @Nullable private SpawnTimingProfiler timing;

    public void timing(@Nullable SpawnTimingProfiler timing) {
        this.timing = timing;
        if (requestedReader != null) requestedReader.timing = timing;
    }

    public SpawnAreaScanner(
            final ServerLevel level,
            final SpawnSearchVolume maximumVolume,
            final SpawnSearchVolume initialActiveVolume) {
        this(level, maximumVolume, initialActiveVolume, null);
    }

    public SpawnAreaScanner(
            final ServerLevel level,
            final SpawnSearchVolume maximumVolume,
            final SpawnSearchVolume initialActiveVolume,
            @Nullable final BlockPos preferredOrigin) {
        this(level, maximumVolume, initialActiveVolume, preferredOrigin, false);
    }

    public SpawnAreaScanner(
            ServerLevel level,
            SpawnSearchVolume maximumVolume,
            SpawnSearchVolume initialActiveVolume,
            @Nullable BlockPos preferredOrigin,
            boolean demandDriven) {
        this(level, maximumVolume, initialActiveVolume, preferredOrigin, demandDriven, null);
    }

    public SpawnAreaScanner(
            ServerLevel level,
            SpawnSearchVolume maximumVolume,
            SpawnSearchVolume initialActiveVolume,
            @Nullable BlockPos preferredOrigin,
            boolean demandDriven,
            com.eynny.npships.resources.ResourceMemoryBudget.Scope memory) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (maximumVolume == null) {
            throw new IllegalArgumentException("maximumVolume cannot be null.");
        }

        if (initialActiveVolume == null) {
            throw new IllegalArgumentException("initialActiveVolume cannot be null.");
        }

        if (!containsVolume(maximumVolume, initialActiveVolume)) {
            throw new IllegalArgumentException(
                    "Initial active volume must be inside maximum volume.");
        }

        this.level = level;

        this.snapshot = new SpawnSpatialSnapshot(maximumVolume, memory);

        this.activeVolume = initialActiveVolume;

        this.preferredOrigin = preferredOrigin == null ? null : preferredOrigin.immutable();

        this.columnQueue =
                new PriorityQueue<>(
                        Comparator.comparingLong(this::distanceToPreferredOrigin)
                                .thenComparingInt(ColumnTask::x)
                                .thenComparingInt(ColumnTask::z));

        this.currentColumn = null;

        this.currentY = initialActiveVolume.minY();

        requestedReader = demandDriven ? new SpawnColumnReader(level, snapshot) : null;
        if (!demandDriven) enqueueActiveColumns();
    }

    public void requestChunks(NaturalSpawnChunkLoader loader) {
        if (requestedReader != null) requestedReader.requestChunks(loader);
    }

    public SpawnSpatialSnapshot snapshot() {
        return snapshot;
    }

    public SpawnSearchVolume activeVolume() {
        return activeVolume;
    }

    public SpawnSearchVolume maximumVolume() {
        return snapshot.volume();
    }

    public void setActiveVolume(final SpawnSearchVolume newActiveVolume) {
        if (newActiveVolume == null) {
            throw new IllegalArgumentException("newActiveVolume cannot be null.");
        }

        if (!containsVolume(snapshot.volume(), newActiveVolume)) {
            throw new IllegalArgumentException("Active volume must be inside maximum volume.");
        }

        this.activeVolume = newActiveVolume;

        if (requestedReader != null) return;

        /*
         * The current column can contain partially scanned data.
         *
         * Return it to the queue. The snapshot will skip everything
         * already scanned and continue from the first missing block.
         */
        if (currentColumn != null) {

            final ColumnTask oldCurrent = currentColumn;

            if (queuedColumns.add(oldCurrent)) {
                columnQueue.add(oldCurrent);
            }

            currentColumn = null;
        }

        currentY = newActiveVolume.minY();

        /*
         * Rebuild the priority queue for the expanded volume.
         *
         * Already fully scanned columns are skipped.
         */
        columnQueue.clear();

        queuedColumns.clear();

        enqueueActiveColumns();
    }

    public StepResult step(final int maxChecks) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.OBSERVE);
        try {
            if (maxChecks <= 0) {
                throw new IllegalArgumentException("maxChecks must be greater than zero.");
            }

            if (requestedReader != null) return requestedReader.step(maxChecks);

            int checks = 0;
            net.minecraft.world.level.chunk.LevelChunkSection lastSection = null;
            BlockState uniformState = null;

            while (checks < maxChecks) {

                /*
                 * -----------------------------------------------------
                 * SELECT NEXT COLUMN
                 * -----------------------------------------------------
                 */

                if (currentColumn == null) {

                    currentColumn = columnQueue.poll();

                    if (currentColumn == null) {

                        return new StepResult(Status.COMPLETE, checks);
                    }

                    queuedColumns.remove(currentColumn);

                    /*
                     * A column may have been partially scanned before
                     * being requeued. Start at the beginning and skip
                     * blocks already present in the snapshot.
                     */
                    currentY = activeVolume.minY();
                }

                /*
                 * -----------------------------------------------------
                 * CURRENT COLUMN COMPLETE
                 * -----------------------------------------------------
                 */

                if (currentY > activeVolume.maxY()) {

                    snapshot.markColumnRangeComplete(
                            currentColumn.x(),
                            currentColumn.z(),
                            activeVolume.minY(),
                            activeVolume.maxY());

                    currentColumn = null;

                    continue;
                }

                final int x = currentColumn.x();

                final int y = currentY;

                final int z = currentColumn.z();

                /*
                 * Already known data costs no scan operation.
                 */
                if (snapshot.isScanned(x, y, z)) {

                    currentY++;

                    continue;
                }

                final BlockPos pos = new BlockPos(x, y, z);

                /*
                 * Do not read an unloaded chunk.
                 *
                 * The loader is independently responsible for making
                 * the chunk available.
                 */
                final var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {

                    final ColumnTask waitingColumn = currentColumn;

                    currentColumn = null;

                    if (queuedColumns.add(waitingColumn)) {

                        columnQueue.add(waitingColumn);
                    }

                    return new StepResult(Status.WAITING_FOR_UNLOADED_CHUNK, checks);
                }

                if (level.isOutsideBuildHeight(pos)) {
                    snapshot.put(
                            pos,
                            net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState());
                    checks++;
                    currentY++;
                    continue;
                }
                final var section = chunk.getSection(chunk.getSectionIndex(y));
                if (section != lastSection) {
                    lastSection = section;
                    final BlockState sample = section.getBlockState(x & 15, y & 15, z & 15);
                    if (timing != null) timing.nativeRead();
                    // Exact uniform palette, not hasOnlyAir(): cave/void air and modded
                    // air must keep their original states in the raw snapshot.
                    uniformState =
                            section.getStates().maybeHas(state -> state != sample) ? null : sample;
                }
                if (uniformState != null) {
                    int end = Math.min(activeVolume.maxY(), y | 15);
                    for (; currentY <= end; currentY++) {
                        if (!snapshot.isScanned(x, currentY, z))
                            snapshot.put(new BlockPos(x, currentY, z), uniformState);
                    }
                    // One palette query copies at most 16 states. Budget stays bounded;
                    // known blocks are preserved and unloaded chunks are never requested.
                    checks++;
                    continue;
                }
                final BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                if (timing != null) timing.nativeRead();

                snapshot.put(pos, state);

                checks++;

                currentY++;
            }

            return new StepResult(Status.RUNNING, checks);

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void enqueueActiveColumns() {

        for (int x = activeVolume.minX(); x <= activeVolume.maxX(); x++) {

            for (int z = activeVolume.minZ(); z <= activeVolume.maxZ(); z++) {

                /*
                 * Completely scanned columns do not need to be
                 * queued again.
                 */
                if (snapshot.isColumnRangeComplete(
                        x, z, activeVolume.minY(), activeVolume.maxY())) {

                    continue;
                }

                final ColumnTask task = new ColumnTask(x, z);

                if (queuedColumns.add(task)) {

                    columnQueue.add(task);
                }
            }
        }
    }

    private long distanceToPreferredOrigin(final ColumnTask task) {
        if (preferredOrigin == null) {
            return 0L;
        }

        final long dx = (long) task.x() - preferredOrigin.getX();

        final long dz = (long) task.z() - preferredOrigin.getZ();

        return dx * dx + dz * dz;
    }

    private static boolean containsVolume(
            final SpawnSearchVolume outer, final SpawnSearchVolume inner) {
        return inner.minX() >= outer.minX()
                && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY()
                && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ()
                && inner.maxZ() <= outer.maxZ();
    }
}
