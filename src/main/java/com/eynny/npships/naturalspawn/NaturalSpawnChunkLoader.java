package com.eynny.npships.naturalspawn;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;
import com.eynny.npships.resources.ResourceLimitException;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Incremental temporary chunk loader.
 *
 * <p>Required chunks are processed from nearest to the preferred reference point outward.
 */
public final class NaturalSpawnChunkLoader {

    private static final int TICKET_TIMEOUT_TICKS = 20;

    private static final TicketType<ChunkPos> NATURAL_SPAWN_TICKET =
            TicketType.create(
                    "npships_natural_spawn",
                    (a, b) -> {
                        final int xCompare = Integer.compare(a.x, b.x);

                        if (xCompare != 0) {
                            return xCompare;
                        }

                        return Integer.compare(a.z, b.z);
                    },
                    TICKET_TIMEOUT_TICKS);

    public enum Status {
        RUNNING,
        COMPLETE,
        FAILED
    }

    public record StepResult(Status status, int chunksProcessed, int chunksLoaded) {}

    private final ServerLevel level;

    private final ServerChunkCache chunkCache;

    private final ChunkResourceLedger resources;
    private final ResourceMemoryBudget.Scope targetMemory;
    private final boolean ownsResources;
    private long targetCapacityBytes;

    private final LongOpenHashSet unreadyTickets = new LongOpenHashSet();
    private final Long2LongOpenHashMap waitingSince = new Long2LongOpenHashMap();

    private final int horizontalChunkMargin;

    @Nullable private final ChunkPos preferredChunk;

    private SpawnSearchVolume targetVolume;

    private List<Long> targetChunks = new ArrayList<>();

    private Set<Long> targetChunkSet = new HashSet<>();

    private Set<Long> ticketedChunks = new HashSet<>();

    private Set<Long> loadedChunks = new HashSet<>();

    private int cursor;
    private int unreadyCursor;
    private final java.util.NavigableSet<Integer> activeUnreadyRequests = new java.util.TreeSet<>();

    private boolean released;
    private boolean requestedOnly;
    private final java.util.NavigableSet<Integer> pendingRequests = new java.util.TreeSet<>();
    private long operationsPerformed;
    @Nullable private SpawnTimingProfiler timing;

    public void timing(@Nullable SpawnTimingProfiler timing) {
        this.timing = timing;
    }

    long operationsPerformed() {
        return operationsPerformed;
    }

    private long lastRefreshTick = Long.MIN_VALUE;

    /** Search requests exact chunks; placement switches back to a bounded volume. */
    public void useRequestedChunks() {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.RELEASE);
        try {
            ensureActive();
            if (requestedOnly) return;
            for (long chunk : ticketedChunks) removeTicket(new ChunkPos(chunk));
            ticketedChunks = new HashSet<>();
            unreadyTickets.clear();
            activeUnreadyRequests.clear();
            waitingSince.clear();
            loadedChunks = new HashSet<>();
            targetChunks = new ArrayList<>();
            targetChunkSet = new HashSet<>();
            targetMemory.release(targetCapacityBytes);
            targetCapacityBytes = 0;
            pendingRequests.clear();
            cursor = 0;
            requestedOnly = true;

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public void requestChunk(int x, int z) {
        ensureActive();
        if (!requestedOnly)
            throw new IllegalStateException("Chunk requests require sparse loading mode");
        long chunk = ChunkPos.asLong(x, z);
        if (!targetChunkSet.contains(chunk)) {
            long nextBytes = targetBytes((long) targetChunks.size() + 1);
            long growth = Math.max(0, nextBytes - targetCapacityBytes);
            targetMemory.reserve(growth);
            try {
                resources.admit(level.dimension(), x, z);
            } catch (RuntimeException | Error failure) {
                targetMemory.release(growth);
                throw failure;
            }
            targetCapacityBytes += growth;
            targetChunkSet.add(chunk);
            cursor = targetChunks.size();
            targetChunks.add(chunk);
            pendingRequests.add(cursor);
        }
    }

    /** Search immediately uses sparse requests; do not first build and discard a dense envelope. */
    public static NaturalSpawnChunkLoader requested(
            ServerLevel level, SpawnSearchVolume volume, int margin, @Nullable BlockPos preferred) {
        return new NaturalSpawnChunkLoader(
                level, volume, margin, preferred, true, ownedLedger(level), true);
    }

    public static NaturalSpawnChunkLoader requested(
            ServerLevel level,
            SpawnSearchVolume volume,
            int margin,
            @Nullable BlockPos preferred,
            ChunkResourceLedger resources) {
        return new NaturalSpawnChunkLoader(
                level, volume, margin, preferred, true, resources, false);
    }

    public NaturalSpawnChunkLoader(
            final ServerLevel level,
            final SpawnSearchVolume initialVolume,
            final int horizontalChunkMargin,
            @Nullable final BlockPos preferredOrigin) {
        this(
                level,
                initialVolume,
                horizontalChunkMargin,
                preferredOrigin,
                false,
                ownedLedger(level),
                true);
    }

    public NaturalSpawnChunkLoader(
            ServerLevel level,
            SpawnSearchVolume volume,
            int margin,
            @Nullable BlockPos preferred,
            ChunkResourceLedger resources) {
        this(level, volume, margin, preferred, false, resources, false);
    }

    private NaturalSpawnChunkLoader(
            ServerLevel level,
            SpawnSearchVolume initialVolume,
            int horizontalChunkMargin,
            @Nullable BlockPos preferredOrigin,
            boolean sparse,
            ChunkResourceLedger resources,
            boolean ownsResources) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (initialVolume == null) {
            throw new IllegalArgumentException("initialVolume cannot be null.");
        }

        if (horizontalChunkMargin < 0) {
            throw new IllegalArgumentException("horizontalChunkMargin cannot be negative.");
        }

        this.level = level;

        this.chunkCache = level.getChunkSource();
        this.resources = java.util.Objects.requireNonNull(resources);
        this.targetMemory = resources.memory().scope("chunk loader target capacity");
        this.ownsResources = ownsResources;

        this.horizontalChunkMargin = horizontalChunkMargin;

        this.preferredChunk = preferredOrigin == null ? null : new ChunkPos(preferredOrigin);

        this.targetVolume = initialVolume;

        this.cursor = 0;

        this.released = false;

        try {
            if (sparse) requestedOnly = true;
            else rebuildTargetChunks();
        } catch (RuntimeException | Error failure) {
            targetMemory.close();
            if (ownsResources) resources.close();
            throw failure;
        }
    }

    public NaturalSpawnChunkLoader(
            final ServerLevel level,
            final SpawnSearchVolume initialVolume,
            final int horizontalChunkMargin) {
        this(level, initialVolume, horizontalChunkMargin, null);
    }

    public NaturalSpawnChunkLoader(final ServerLevel level, final SpawnSearchVolume initialVolume) {
        this(level, initialVolume, 1, null);
    }

    public ServerLevel level() {
        return level;
    }

    public SpawnSearchVolume targetVolume() {
        return targetVolume;
    }

    public int horizontalChunkMargin() {
        return horizontalChunkMargin;
    }

    public int targetChunkCount() {
        return targetChunks.size();
    }

    public ChunkResourceLedger resources() {
        return resources;
    }

    public int unreadyTicketCount() {
        return unreadyTickets.size();
    }

    public int ticketedChunkCount() {
        return ticketedChunks.size();
    }

    public int loadedChunkCount() {
        return loadedChunks.size();
    }

    public boolean isReleased() {
        return released;
    }

    public boolean isComplete() {
        return !released && loadedChunks.size() == targetChunks.size();
    }

    public void setTargetVolume(final SpawnSearchVolume newVolume) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.ENVELOPE);
        try {
            ensureActive();

            if (newVolume == null) {
                throw new IllegalArgumentException("newVolume cannot be null.");
            }

            if (!requestedOnly && newVolume.equals(targetVolume)) {
                return;
            }

            // Geometry admission must precede changes to the still-valid active loader.
            rebuildTargetChunks(newVolume);
            targetVolume = newVolume;
            requestedOnly = false;
            pendingRequests.clear();

            cursor = 0;

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public void setStructureArea(final SpawnSearchVolume structureArea) {
        setTargetVolume(structureArea);
    }

    public StepResult step(final int maxChunks) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CHUNKS);
        try {
            ensureActive();

            if (maxChunks <= 0) {
                throw new IllegalArgumentException("maxChunks must be greater than zero.");
            }

            if (targetChunks.isEmpty()) {
                return new StepResult(Status.COMPLETE, 0, 0);
            }

            refreshTickets();

            int processed = 0;

            int loadedThisStep = 0;

            while (processed < maxChunks) {

                if (isComplete()) {
                    return new StepResult(Status.COMPLETE, processed, loadedThisStep);
                }

                int requestIndex;
                if (unreadyTickets.size() >= resources.limits().maxConcurrentUnreadyChunks()
                        && !activeUnreadyRequests.isEmpty()) {
                    // Poll the bounded native submissions, rather than spending the budget
                    // walking every unsent target before observing generation completion.
                    var next = activeUnreadyRequests.ceiling(unreadyCursor);
                    requestIndex = next == null ? activeUnreadyRequests.first() : next;
                    unreadyCursor = requestIndex + 1;
                } else {
                    if (requestedOnly) {
                        var next = pendingRequests.ceiling(cursor);
                        cursor = next == null ? pendingRequests.first() : next;
                    } else if (cursor >= targetChunks.size()) {
                        cursor = 0;
                    }
                    requestIndex = cursor++;
                }
                final long chunkLong = targetChunks.get(requestIndex);

                processed++;
                operationsPerformed++;

                final ChunkPos chunkPos = new ChunkPos(chunkLong);

                // One readiness observation per charged request. A full unready window is
                // backpressure, not refusal; admitted ready tickets remain held for placement.
                boolean ready = isChunkReady(chunkPos);
                if (ready) {
                    ensureTicket(chunkPos);
                    unreadyTickets.remove(chunkLong);
                    activeUnreadyRequests.remove(requestIndex);
                    waitingSince.remove(chunkLong);
                    if (loadedChunks.add(chunkLong)) {
                        if (requestedOnly) pendingRequests.remove(requestIndex);
                        loadedThisStep++;
                    }
                } else {
                    resources.observedUnready(level.dimension(), chunkLong);
                    loadedChunks.remove(chunkLong);
                    if (ticketedChunks.contains(chunkLong)
                            || unreadyTickets.size()
                                    < resources.limits().maxConcurrentUnreadyChunks()) {
                        ensureTicket(chunkPos);
                        unreadyTickets.add(chunkLong);
                        activeUnreadyRequests.add(requestIndex);
                        observeWaiting(chunkLong);
                    }
                }
            }

            return new StepResult(
                    isComplete() ? Status.COMPLETE : Status.RUNNING, processed, loadedThisStep);

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public void release() {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.RELEASE);
        try {

            if (released) {
                return;
            }

            for (Long chunkLong : new ArrayList<>(ticketedChunks)) {

                removeTicket(new ChunkPos(chunkLong));
            }

            ticketedChunks = new HashSet<>();
            unreadyTickets.clear();
            activeUnreadyRequests.clear();
            waitingSince.clear();

            loadedChunks = new HashSet<>();
            targetChunks = new ArrayList<>();
            targetChunkSet = new HashSet<>();
            pendingRequests.clear();

            targetMemory.close();
            targetCapacityBytes = 0;
            if (ownsResources) resources.close();
            released = true;

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void rebuildTargetChunks() {
        rebuildTargetChunks(targetVolume);
    }

    private void rebuildTargetChunks(SpawnSearchVolume volume) {
        int[] bounds = volumeBounds(volume);
        long count =
                Math.multiplyExact(
                        (long) bounds[1] - bounds[0] + 1, (long) bounds[3] - bounds[2] + 1);
        long newCapacityBytes = targetBytes(count);
        // Keep the old target structures charged until the new collection replaces them.
        targetMemory.reserve(newCapacityBytes);
        try {
            resources.admitRectangle(level.dimension(), bounds[0], bounds[1], bounds[2], bounds[3]);
        } catch (RuntimeException | Error failure) {
            targetMemory.release(newCapacityBytes);
            throw failure;
        }
        final int minChunkX = bounds[0], maxChunkX = bounds[1];
        final int minChunkZ = bounds[2], maxChunkZ = bounds[3];

        final Set<Long> newTargetSet = new HashSet<>();

        final List<Long> newTargetList = new ArrayList<>((int) count);

        for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {

            for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {

                final ChunkPos chunkPos = new ChunkPos((int) chunkX, (int) chunkZ);

                final long chunkLong = chunkPos.toLong();

                if (newTargetSet.add(chunkLong)) {

                    newTargetList.add(chunkLong);
                }
            }
        }

        /*
         * Remove no-longer-required tickets.
         */
        for (Long oldChunk : new ArrayList<>(ticketedChunks)) {

            if (!newTargetSet.contains(oldChunk)) {

                removeTicket(new ChunkPos(oldChunk));

                ticketedChunks.remove(oldChunk);

                loadedChunks.remove(oldChunk);
                unreadyTickets.remove(oldChunk.longValue());
                waitingSince.remove(oldChunk.longValue());
            }
        }

        /*
         * Nearest chunks first.
         */
        newTargetList.sort(
                Comparator.comparingLong(this::distanceToPreferredChunk)
                        .thenComparingLong(Long::longValue));

        targetChunkSet = newTargetSet;
        targetChunks = newTargetList;
        loadedChunks.retainAll(targetChunkSet);
        loadedChunks = new HashSet<>(loadedChunks);
        ticketedChunks = new HashSet<>(ticketedChunks);
        targetMemory.release(targetCapacityBytes);
        targetCapacityBytes = newCapacityBytes;
        activeUnreadyRequests.clear();
        for (int index = 0; index < targetChunks.size(); index++)
            if (unreadyTickets.contains(targetChunks.get(index).longValue()))
                activeUnreadyRequests.add(index);
    }

    private static ChunkResourceLedger ownedLedger(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("level cannot be null.");
        return new ChunkResourceLedger(
                ServerResourcePolicy.current(level.getServer()),
                ServerResourcePolicy.memory(level.getServer()));
    }

    private static long targetBytes(long count) {
        if (count < 0 || count > Integer.MAX_VALUE)
            throw new ResourceLimitException(
                    "chunk_target_index_capacity", 0, count, Integer.MAX_VALUE);
        // Known Long payload and collection reference slots; object headers/native state are not
        // a universal heap estimate. Capacity covers target list/set and ready/ticket ownership.
        long slots = 16;
        long required = (count * 4 + 2) / 3;
        while (slots < required) slots <<= 1;
        return Math.addExact(
                ResourceMemoryBudget.arrayBytes(count + count / 2 + 1, 8),
                Math.addExact(
                        Math.multiplyExact(ResourceMemoryBudget.arrayBytes(slots, 8), 3),
                        Math.multiplyExact(count, 12)));
    }

    private int[] volumeBounds(SpawnSearchVolume volume) {
        long minX = (long) Math.floorDiv(volume.minX(), 16) - horizontalChunkMargin;
        long maxX = (long) Math.floorDiv(volume.maxX(), 16) + horizontalChunkMargin;
        long minZ = (long) Math.floorDiv(volume.minZ(), 16) - horizontalChunkMargin;
        long maxZ = (long) Math.floorDiv(volume.maxZ(), 16) + horizontalChunkMargin;
        try {
            return new int[] {
                Math.toIntExact(minX),
                Math.toIntExact(maxX),
                Math.toIntExact(minZ),
                Math.toIntExact(maxZ)
            };
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Chunk envelope exceeds the native coordinate range", overflow);
        }
    }

    private void observeWaiting(long chunk) {
        long limit = resources.limits().chunkStallTicks();
        if (limit == 0) return;
        long now = level.getGameTime();
        if (!waitingSince.containsKey(chunk)) waitingSince.put(chunk, now);
        long since = waitingSince.get(chunk);
        long elapsed = now >= since ? now - since : 0;
        if (elapsed < 0 || elapsed > limit)
            throw new ResourceLimitException(
                    "chunk_stall_ticks", 0, elapsed < 0 ? Long.MAX_VALUE : elapsed, limit);
    }

    private long distanceToPreferredChunk(final long chunkLong) {
        if (preferredChunk == null) {
            return 0L;
        }

        final ChunkPos chunk = new ChunkPos(chunkLong);

        final long dx = (long) chunk.x - preferredChunk.x;

        final long dz = (long) chunk.z - preferredChunk.z;

        return dx * dx + dz * dz;
    }

    private void ensureTicket(final ChunkPos chunkPos) {
        final long chunkLong = chunkPos.toLong();

        if (!ticketedChunks.contains(chunkLong)) {
            resources.acquireTicket();
            try {
                chunkCache.addRegionTicket(NATURAL_SPAWN_TICKET, chunkPos, 0, chunkPos);
            } catch (RuntimeException | Error failure) {
                resources.releaseTicket();
                throw failure;
            }
            ticketedChunks.add(chunkLong);
            // Diagnostics do not roll back a ticket whose native/local ownership has committed.
            if (timing != null) timing.ticketAdded(chunkLong);
        }
    }

    /** Retain the existing lease while preparation yields; never requests additional chunks. */
    void refreshTickets() {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.RENEW);
        try {

            long now = level.getGameTime();
            if (lastRefreshTick == now) return;
            lastRefreshTick = now;

            for (Long chunkLong : ticketedChunks) {

                final ChunkPos chunkPos = new ChunkPos(chunkLong);

                chunkCache.addRegionTicket(NATURAL_SPAWN_TICKET, chunkPos, 0, chunkPos);
                if (timing != null) timing.renewed();
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private boolean isChunkReady(final ChunkPos chunkPos) {
        if (timing != null) timing.readinessProbe();
        return readyForPlacement(level, chunkPos.x, chunkPos.z);
    }

    /** A FULL chunk object can precede native entity loading and visibility publication. */
    public static boolean readyForPlacement(ServerLevel level, int x, int z) {
        final LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        return chunk != null
                && chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)
                && level.areEntitiesLoaded(ChunkPos.asLong(x, z))
                && ((com.eynny.npships.mixin.NPShipsEntityVisibilityAccessor)
                                ((com.eynny.npships.mixin.NPShipsLevelEntityManagerAccessor) level)
                                        .npships$entityManager())
                        .npships$chunkVisibility()
                        .get(ChunkPos.asLong(x, z))
                        .isAccessible();
    }

    private void removeTicket(final ChunkPos chunkPos) {
        chunkCache.removeRegionTicket(NATURAL_SPAWN_TICKET, chunkPos, 0, chunkPos);
        resources.releaseTicket();
        if (timing != null) timing.ticketRemoved();
    }

    private void ensureActive() {

        if (released) {

            throw new IllegalStateException("NaturalSpawnChunkLoader has already been released.");
        }
    }
}
