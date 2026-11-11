package com.eynny.npships.naturalspawn;

import com.eynny.npships.resources.ResourceLimitException;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Attempt-owned cumulative geometry; release of a loader never erases opportunity accounting. */
public final class ChunkResourceLedger implements AutoCloseable {
    private static final int HALO = ChunkLevel.RADIUS_AROUND_FULL_CHUNK;

    private static final class Dimension {
        final LongOpenHashSet targets = new LongOpenHashSet();
        final LongOpenHashSet everUnready = new LongOpenHashSet();
        final NavigableMap<Long, NavigableMap<Long, Long>> dependencyRows = new TreeMap<>();
    }

    private record Row(long z, long from, long through, long intervalChange, boolean newRow) {}

    private final ServerResourcePolicy.Limits limits;
    private final ResourceMemoryBudget memory;
    private final ResourceMemoryBudget.Scope metadata;
    private boolean closed;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new HashMap<>();
    private long targets, dependencies, everUnready;
    private int ownedTickets, peakOwnedTickets;

    public ChunkResourceLedger(ServerResourcePolicy.Limits limits) {
        this(limits, new ResourceMemoryBudget(limits.maxWorkingBytes()));
    }

    public ChunkResourceLedger(ServerResourcePolicy.Limits limits, ResourceMemoryBudget memory) {
        this.limits = java.util.Objects.requireNonNull(limits);
        this.memory = java.util.Objects.requireNonNull(memory);
        this.metadata = memory.scope("cumulative chunk metadata");
    }

    ResourceMemoryBudget memory() {
        return memory;
    }

    @Override
    public void close() {
        if (closed) return;
        if (ownedTickets != 0) throw new IllegalStateException("Chunk ledger still owns tickets");
        dimensions.clear();
        metadata.close();
        closed = true;
    }

    public ServerResourcePolicy.Limits limits() {
        return limits;
    }

    public long targetCount() {
        return targets;
    }

    public long dependencyCount() {
        return dependencies;
    }

    public long everUnreadyCount() {
        return everUnready;
    }

    public int ownedTicketCount() {
        return ownedTickets;
    }

    public int peakOwnedTicketCount() {
        return peakOwnedTickets;
    }

    /** Geometric union, not a promise about native generated chunks or future cancellation. */
    public void admit(ResourceKey<Level> dimension, int x, int z) {
        admitRectangle(dimension, x, x, z, z);
    }

    /**
     * Reject oversized rectangles before a target collection, row staging, or ticket is allocated.
     */
    public void admitRectangle(
            ResourceKey<Level> dimension, long minX, long maxX, long minZ, long maxZ) {
        if (closed) throw new IllegalStateException("Chunk resource ledger is closed");
        validateCoordinates(minX, maxX, minZ, maxZ);
        long width = maxX - minX + 1, height = maxZ - minZ + 1;
        long targetArea = product(width, height);
        long haloArea = product(width + 2L * HALO, height + 2L * HALO);
        check("chunk_target_index_capacity", 0, targetArea, Integer.MAX_VALUE);
        // A retained target needs at least its long key payload. This no-allocation witness
        // rejects extreme administrator values before even the exact union's counting loops.
        check("chunk geometry key payload", 0, Math.multiplyExact(targetArea, 8L), memory.limit());
        // Every new rectangle is a subset of the eventual union. These checks also bound loops.
        check("target_chunks", 0, targetArea, limits.maxTargetChunks());
        check("dependency_chunks", 0, haloArea, limits.maxDependencyChunks());
        var existing = dimensions.get(dimension);
        long additionalTargets = 0;
        for (long z = minZ; z <= maxZ; z++)
            for (long x = minX; x <= maxX; x++)
                if (existing == null
                        || !existing.targets.contains(ChunkPos.asLong((int) x, (int) z)))
                    additionalTargets++;
        check("target_chunks", targets, additionalTargets, limits.maxTargetChunks());
        long additionalDependencies = 0;
        // Staging holds at most one row per admitted dependency Z, never the halo's cell objects.
        var rows = new ArrayList<Row>();
        for (long z = minZ - HALO; z <= maxZ + HALO; z++) {
            long from = minX - HALO, through = maxX + HALO;
            var row = existing == null ? null : existing.dependencyRows.get(z);
            long missing = uncovered(row, from, through);
            additionalDependencies = Math.addExact(additionalDependencies, missing);
            check(
                    "dependency_chunks",
                    dependencies,
                    additionalDependencies,
                    limits.maxDependencyChunks());
            if (missing > 0)
                rows.add(
                        new Row(z, from, through, intervalChange(row, from, through), row == null));
        }
        // Known coordinate payload only; boxed nodes/object headers remain a declared exposure.
        long metadataGrowth = Math.multiplyExact(additionalTargets, 8L);
        for (var row : rows)
            metadataGrowth =
                    Math.addExact(
                            metadataGrowth,
                            Math.addExact(
                                    row.newRow() ? 8 : 0,
                                    Math.multiplyExact(row.intervalChange(), 16)));
        if (metadataGrowth > 0) metadata.reserve(metadataGrowth);
        // No state changes above: a refusal cannot poison fallback or an already-owned lease.
        var state = dimensions.computeIfAbsent(dimension, ignored -> new Dimension());
        for (long z = minZ; z <= maxZ; z++)
            for (long x = minX; x <= maxX; x++)
                state.targets.add(ChunkPos.asLong((int) x, (int) z));
        for (var row : rows) {
            var intervals =
                    state.dependencyRows.computeIfAbsent(row.z(), ignored -> new TreeMap<>());
            merge(intervals, row.from(), row.through());
        }
        if (metadataGrowth < 0) metadata.release(-metadataGrowth);
        targets += additionalTargets;
        dependencies += additionalDependencies;
    }

    public void observedUnready(ResourceKey<Level> dimension, long chunk) {
        var state = dimensions.get(dimension);
        if (state == null || !state.targets.contains(chunk))
            throw new IllegalStateException("Unready chunk has not been admitted");
        if (state.everUnready.contains(chunk)) return;
        if (limits.maxEverUnreadyChunks() > 0)
            check("ever_unready_chunks", everUnready, 1, limits.maxEverUnreadyChunks());
        metadata.reserve(8);
        state.everUnready.add(chunk);
        everUnready++;
    }

    public void acquireTicket() {
        check("owned_chunk_tickets", ownedTickets, 1, limits.maxOwnedTickets());
        ownedTickets++;
        peakOwnedTickets = Math.max(peakOwnedTickets, ownedTickets);
    }

    /** Mandatory release is never quota checked. */
    public void releaseTicket() {
        if (ownedTickets <= 0) throw new IllegalStateException("Chunk ticket owner underflow");
        ownedTickets--;
    }

    private static void validateCoordinates(long minX, long maxX, long minZ, long maxZ) {
        if (minX > maxX
                || minZ > maxZ
                || minX < (long) Integer.MIN_VALUE + HALO
                || minZ < (long) Integer.MIN_VALUE + HALO
                || maxX > (long) Integer.MAX_VALUE - HALO
                || maxZ > (long) Integer.MAX_VALUE - HALO)
            throw new IllegalArgumentException(
                    "Chunk coordinates/dependency halo exceed the native integer range");
    }

    private static long product(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Chunk envelope exceeds the supported numeric range", overflow);
        }
    }

    private static void check(String resource, long used, long requested, long limit) {
        if (requested < 0 || used > limit || requested > limit - used)
            throw new ResourceLimitException(resource, used, requested, limit);
    }

    private static long uncovered(NavigableMap<Long, Long> intervals, long from, long through) {
        long count = through - from + 1;
        if (intervals == null) return count;
        var first = intervals.floorEntry(from);
        if (first == null || first.getValue() < from) first = intervals.ceilingEntry(from);
        for (var entry = first;
                entry != null && entry.getKey() <= through;
                entry = intervals.higherEntry(entry.getKey()))
            count -=
                    Math.max(
                            0,
                            Math.min(through, entry.getValue())
                                    - Math.max(from, entry.getKey())
                                    + 1);
        return count;
    }

    private static long intervalChange(
            NavigableMap<Long, Long> intervals, long from, long through) {
        if (intervals == null) return 1;
        var first = intervals.floorEntry(from);
        if (first == null || first.getValue() < from - 1) first = intervals.ceilingEntry(from);
        long removed = 0;
        for (var entry = first;
                entry != null && entry.getKey() <= through + 1;
                entry = intervals.higherEntry(entry.getKey())) {
            removed++;
            through = Math.max(through, entry.getValue());
        }
        return 1 - removed;
    }

    private static void merge(NavigableMap<Long, Long> intervals, long from, long through) {
        var previous = intervals.floorEntry(from);
        if (previous != null && previous.getValue() >= from - 1) {
            from = previous.getKey();
            through = Math.max(through, previous.getValue());
            intervals.remove(previous.getKey());
        }
        var next = intervals.ceilingEntry(from);
        while (next != null && next.getKey() <= through + 1) {
            through = Math.max(through, next.getValue());
            intervals.remove(next.getKey());
            next = intervals.ceilingEntry(from);
        }
        intervals.put(from, through);
    }
}
