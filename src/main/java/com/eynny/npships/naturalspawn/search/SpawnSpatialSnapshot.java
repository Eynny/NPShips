package com.eynny.npships.naturalspawn.search;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.BitSet;

/**
 * Runtime raw-world snapshot for one natural-spawn attempt.
 *
 * <p>Raw observations are structure-agnostic. Derived profiles are keyed separately by environment
 * and height, never by a candidate's pass/fail result.
 *
 * <p>Each block stores the observed Minecraft BlockState id.
 *
 * <p>Column completeness is determined by exact scanned bits, including holes.
 *
 * <p>The snapshot is immutable in size but can be incrementally filled as the active search volume
 * grows.
 */
public final class SpawnSpatialSnapshot implements AutoCloseable {

    private final com.eynny.npships.resources.ResourceMemoryBudget.Scope memory;
    private boolean closed;

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Spawn snapshot is closed");
    }

    private long profileCapacity;

    void reserveCapacity(long bytes) {
        if (memory != null) memory.reserve(bytes);
    }

    void releaseCapacity(long bytes) {
        if (memory != null) memory.release(bytes);
    }

    private static long arrayCapacity(long count, int width) {
        return com.eynny.npships.resources.ResourceMemoryBudget.arrayBytes(count, width);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        requester = null;
        // Existing external handles may still read the frozen observations. Keep their
        // capacity charged until the weak scope is collected; never mutate their data.
        if (memory != null) memory.retire();
    }

    private static final long MAX_BLOCKS = 64L * 1024L * 1024L;
    private static final int PAGE_SHIFT = 12;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    private final SpawnSearchVolume volume;

    // Keep the existing Y-contiguous index and exact scanned bitset. Missing pages
    // have no observations; allocating one never makes its zero-filled cells AIR.
    // A first write initializes at most 16 KiB, not the maximum search volume.
    private int[][] stateIds;

    private BitSet scannedBlocks;

    @FunctionalInterface
    interface ColumnRequester {
        void request(int x, int z, int minY, int maxY);
    }

    private ColumnRequester requester;

    void setRequester(ColumnRequester requester) {
        ensureOpen();
        this.requester = requester;
    }

    void requestColumn(int x, int z, int minY, int maxY) {
        ensureOpen();
        if (requester != null) requester.request(x, z, minY, maxY);
    }

    // Derived data are keyed by the complete environment and height, so fallback
    // candidates can share profiles without sharing conclusions for different rules.
    final java.util.Map<PlacementProfileCache.Key, java.util.List<OriginYInterval>>
            placementProfiles =
                    new java.util.LinkedHashMap<>(256, .75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<
                                                PlacementProfileCache.Key,
                                                java.util.List<OriginYInterval>>
                                        entry) {
                            if (size() <= 262144) return false;
                            long bytes = profileCapacity(entry.getValue());
                            profileCapacity -= bytes;
                            releaseCapacity(bytes);
                            return true;
                        }

                        @Override
                        public java.util.List<OriginYInterval> put(
                                PlacementProfileCache.Key key,
                                java.util.List<OriginYInterval> value) {
                            ensureOpen();
                            long bytes = profileCapacity(value);
                            reserveCapacity(bytes);
                            profileCapacity += bytes;
                            var previous = super.put(key, value);
                            if (previous != null) {
                                long old = profileCapacity(previous);
                                profileCapacity -= old;
                                releaseCapacity(old);
                            }
                            return previous;
                        }

                        @Override
                        public void clear() {
                            ensureOpen();
                            super.clear();
                            releaseCapacity(profileCapacity);
                            profileCapacity = 0;
                        }
                    };

    private static long profileCapacity(java.util.List<OriginYInterval> value) {
        // Key: seven ints + environment reference; linked entry: hash + five references;
        // list: one reference/interval; interval: two ints. Headers/native graphs are not inferred.
        return 8L * (6L + value.size()) + Integer.BYTES * (8L + 2L * value.size());
    }

    // Conservative presence index: overwritten states may remain, so it can only
    // prove absence, never prove that a placement is valid. No structure-specific data.
    private BitSet observedStates = new BitSet();

    public boolean hasObservedState(java.util.function.Predicate<BlockState> predicate) {
        for (int id = observedStates.nextSetBit(0);
                id >= 0;
                id = observedStates.nextSetBit(id + 1)) {
            if (predicate.test(Block.stateById(id))) return true;
        }
        return false;
    }

    private int[][] columnBounds;
    private static final int COLUMN_SHIFT = 8;
    private static final int COLUMN_SIZE = 1 << COLUMN_SHIFT;

    private int scannedCount;

    public SpawnSpatialSnapshot(final SpawnSearchVolume volume) {
        this(volume, null);
    }

    public SpawnSpatialSnapshot(
            final SpawnSearchVolume volume,
            com.eynny.npships.resources.ResourceMemoryBudget.Scope memory) {
        this.memory = memory;

        if (volume == null) {

            throw new IllegalArgumentException("volume cannot be null.");
        }

        final long blockCount = volume.blockCount();

        if (blockCount > MAX_BLOCKS) {

            throw new IllegalArgumentException(
                    "Search volume is too large: " + blockCount + " blocks.");
        }

        final long columnCount = (long) volume.sizeX() * volume.sizeZ();

        if (columnCount > Integer.MAX_VALUE) {

            throw new IllegalArgumentException(
                    "Search volume has too many columns: " + columnCount);
        }

        this.volume = volume;

        int statePages = (int) ((blockCount + PAGE_MASK) >>> PAGE_SHIFT);
        int boundPages = (int) ((columnCount + COLUMN_SIZE - 1) >>> COLUMN_SHIFT);
        try {
            reserveCapacity(
                    Math.addExact(
                            arrayCapacity(statePages, 8),
                            Math.addExact(arrayCapacity(boundPages, 8), 2 * arrayCapacity(1, 8))));
            this.stateIds = new int[statePages][];
            this.scannedBlocks = new BitSet();
            this.columnBounds = new int[boundPages][];
        } catch (RuntimeException | Error failure) {
            if (memory != null) memory.close();
            throw failure;
        }

        this.scannedCount = 0;
    }

    public SpawnSearchVolume volume() {
        return volume;
    }

    public int scannedCount() {
        return scannedCount;
    }

    public long totalBlockCount() {
        return volume.blockCount();
    }

    public long totalColumnCount() {
        return (long) volume.sizeX() * volume.sizeZ();
    }

    public boolean isComplete() {
        return scannedCount == volume.blockCount();
    }

    public boolean contains(final BlockPos pos) {

        return pos != null && volume.contains(pos);
    }

    public void put(final BlockPos pos, final BlockState state) {
        ensureOpen();

        if (!volume.contains(pos)) {

            throw new IllegalArgumentException("Position lies outside snapshot volume: " + pos);
        }

        if (state == null) {

            throw new IllegalArgumentException("state cannot be null.");
        }

        final int index = indexOf(pos);
        final int pageIndex = index >>> PAGE_SHIFT;
        int[] page = stateIds[pageIndex];
        if (page == null) {
            int length = Math.min(PAGE_SIZE, (int) volume.blockCount() - (pageIndex << PAGE_SHIFT));
            reserveCapacity(arrayCapacity(length, Integer.BYTES));
            page = new int[length];
            stateIds[pageIndex] = page;
        }
        final int offset = index & PAGE_MASK;
        final int stateId = Block.getId(state);
        // Extra conservative presence on a later refused scanned-bit growth is safe; a
        // committed scanned sample missing from presence could falsely prove absence.
        setAccountedBit(observedStates, stateId);

        // Readers never replace samples. Explicit replacements (e.g. a test or a
        // caller rebuilding a snapshot) must invalidate derived environment results.
        if (scannedBlocks.get(index) && page[offset] != stateId) placementProfiles.clear();

        if (!scannedBlocks.get(index)) {

            setAccountedBit(scannedBlocks, index);

            scannedCount++;
        }

        page[offset] = stateId;
    }

    private void setAccountedBit(BitSet bits, int index) {
        int oldWords = bits.size() >>> 6;
        int required = (index >>> 6) + 1;
        if (required <= oldWords) {
            bits.set(index);
            return;
        }
        int next = Math.max(required, Math.multiplyExact(oldWords, 2));
        long bytes = arrayCapacity(next, Long.BYTES);
        reserveCapacity(bytes); // Old and new backing arrays coexist during native copy.
        try {
            bits.set(index);
        } catch (RuntimeException | Error failure) {
            releaseCapacity(bytes);
            throw failure;
        }
        releaseCapacity(arrayCapacity(oldWords, Long.BYTES));
    }

    /** Marks a continuous Y range of one X/Z column as scanned. */
    public void markColumnRangeComplete(
            final int worldX, final int worldZ, final int minY, final int maxY) {
        ensureOpen();
        if (minY > maxY) {

            throw new IllegalArgumentException("minY cannot be greater than maxY.");
        }

        if (worldX < volume.minX()
                || worldX > volume.maxX()
                || worldZ < volume.minZ()
                || worldZ > volume.maxZ()) {

            throw new IllegalArgumentException("Column lies outside snapshot volume.");
        }

        final int index = columnIndex(worldX, worldZ);

        int[] page = columnBounds[index >>> COLUMN_SHIFT];
        if (page == null) {
            reserveCapacity(arrayCapacity(COLUMN_SIZE * 2, Integer.BYTES));
            page = new int[COLUMN_SIZE * 2];
            java.util.Arrays.fill(page, 0, COLUMN_SIZE, Integer.MAX_VALUE);
            java.util.Arrays.fill(page, COLUMN_SIZE, page.length, Integer.MIN_VALUE);
            columnBounds[index >>> COLUMN_SHIFT] = page;
        }
        int offset = index & (COLUMN_SIZE - 1);
        page[offset] = Math.min(page[offset], minY);
        page[COLUMN_SIZE + offset] = Math.max(page[COLUMN_SIZE + offset], maxY);
    }

    /** Marks the entire snapshot Y range as scanned for a column. */
    public void markColumnComplete(final int worldX, final int worldZ) {

        markColumnRangeComplete(worldX, worldZ, volume.minY(), volume.maxY());
    }

    public boolean isColumnRangeComplete(
            final int worldX, final int worldZ, final int minY, final int maxY) {

        if (minY > maxY) {
            return false;
        }

        if (worldX < volume.minX()
                || worldX > volume.maxX()
                || worldZ < volume.minZ()
                || worldZ > volume.maxZ()) {

            return false;
        }

        if (minY < volume.minY() || maxY > volume.maxY()) {

            return false;
        }

        final int index = columnIndex(worldX, worldZ);

        // Y is contiguous in the bitset. Bounds alone cannot prove completeness:
        // sparse under/above reads and rotation checks may leave holes between them.
        int start = index * volume.sizeY() + minY - volume.minY();
        return scannedBlocks.nextClearBit(start) > start + maxY - minY;
    }

    int firstUnscannedY(int x, int z, int minY, int maxY) {
        int start = columnIndex(x, z) * volume.sizeY() + minY - volume.minY();
        return Math.min(maxY + 1, minY + scannedBlocks.nextClearBit(start) - start);
    }

    public boolean isColumnComplete(final int worldX, final int worldZ) {

        return isColumnRangeComplete(worldX, worldZ, volume.minY(), volume.maxY());
    }

    public boolean isColumnComplete(final BlockPos pos) {

        if (pos == null) {
            return false;
        }

        return isColumnComplete(pos.getX(), pos.getZ());
    }

    public int scannedMinY(final int worldX, final int worldZ) {

        if (!isInsideColumn(worldX, worldZ)) {

            throw new IllegalArgumentException("Column lies outside snapshot volume.");
        }

        int index = columnIndex(worldX, worldZ);
        int[] page = columnBounds[index >>> COLUMN_SHIFT];
        return page == null ? Integer.MAX_VALUE : page[index & (COLUMN_SIZE - 1)];
    }

    public int scannedMaxY(final int worldX, final int worldZ) {

        if (!isInsideColumn(worldX, worldZ)) {

            throw new IllegalArgumentException("Column lies outside snapshot volume.");
        }

        int index = columnIndex(worldX, worldZ);
        int[] page = columnBounds[index >>> COLUMN_SHIFT];
        return page == null ? Integer.MIN_VALUE : page[COLUMN_SIZE + (index & (COLUMN_SIZE - 1))];
    }

    public BlockState getState(final BlockPos pos) {

        if (!volume.contains(pos)) {

            throw new IllegalArgumentException("Position lies outside snapshot volume: " + pos);
        }

        final int index = indexOf(pos);

        if (!scannedBlocks.get(index)) {

            throw new IllegalStateException("Block has not been scanned yet: " + pos);
        }

        return Block.stateById(stateIds[index >>> PAGE_SHIFT][index & PAGE_MASK]);
    }

    public BlockState getState(final int x, final int y, final int z) {

        return getState(new BlockPos(x, y, z));
    }

    public BlockState getStateIfScanned(final BlockPos pos) {

        if (!volume.contains(pos)) {
            return null;
        }

        final int index = indexOf(pos);

        if (!scannedBlocks.get(index)) {
            return null;
        }

        return Block.stateById(stateIds[index >>> PAGE_SHIFT][index & PAGE_MASK]);
    }

    public boolean isScanned(final int x, final int y, final int z) {

        if (x < volume.minX()
                || x > volume.maxX()
                || y < volume.minY()
                || y > volume.maxY()
                || z < volume.minZ()
                || z > volume.maxZ()) {

            return false;
        }

        return scannedBlocks.get(indexOf(new BlockPos(x, y, z)));
    }

    public boolean isScanned(final BlockPos pos) {

        if (pos == null) {
            return false;
        }

        return isScanned(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean areColumnsComplete(final BlockPos origin, final BlockPos size) {

        if (origin == null || size == null) {

            return false;
        }

        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {

            return false;
        }

        final int minX = origin.getX();

        final int maxX = origin.getX() + size.getX() - 1;

        final int minZ = origin.getZ();

        final int maxZ = origin.getZ() + size.getZ() - 1;

        if (minX < volume.minX()
                || maxX > volume.maxX()
                || minZ < volume.minZ()
                || maxZ > volume.maxZ()) {

            return false;
        }

        for (int x = minX; x <= maxX; x++) {

            for (int z = minZ; z <= maxZ; z++) {

                if (!isColumnComplete(x, z)) {

                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Checks whether every block inside the supplied volume has been scanned.
     *
     * <p>Each column checks the actual scanned Y bits, including gaps in sparse scans.
     */
    public boolean isVolumeScanned(final BlockPos min, final BlockPos size) {

        if (min == null || size == null) {

            return false;
        }

        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {

            return false;
        }

        final BlockPos max =
                new BlockPos(
                        min.getX() + size.getX() - 1,
                        min.getY() + size.getY() - 1,
                        min.getZ() + size.getZ() - 1);

        if (!volume.contains(min) || !volume.contains(max)) {

            return false;
        }

        for (int x = min.getX(); x <= max.getX(); x++) {

            for (int z = min.getZ(); z <= max.getZ(); z++) {

                if (!isColumnRangeComplete(x, z, min.getY(), max.getY())) {

                    return false;
                }
            }
        }

        return true;
    }

    private boolean isInsideColumn(final int x, final int z) {

        return x >= volume.minX() && x <= volume.maxX() && z >= volume.minZ() && z <= volume.maxZ();
    }

    private int indexOf(final BlockPos pos) {

        final int localX = pos.getX() - volume.minX();

        final int localY = pos.getY() - volume.minY();

        final int localZ = pos.getZ() - volume.minZ();

        return (localZ * volume.sizeX() + localX) * volume.sizeY() + localY;
    }

    private int columnIndex(final int worldX, final int worldZ) {

        final int localX = worldX - volume.minX();

        final int localZ = worldZ - volume.minZ();

        return localZ * volume.sizeX() + localX;
    }
}
