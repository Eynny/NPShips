package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicRegions;

import net.minecraft.core.BlockPos;

import java.util.BitSet;

/** Thin a connected shell without removing its outer surface or breaking face connections. */
public final class HollowShell {
    private HollowShell() {}

    public static BitSet build(SchematicRegions.Shape shape, BlockPos size) {
        return build(shape::includes, size);
    }

    public static BitSet build(BitSet solid, BlockPos size) {
        return build(
                (x, y, z, s) ->
                        x >= 0
                                && y >= 0
                                && z >= 0
                                && x < s.getX()
                                && y < s.getY()
                                && z < s.getZ()
                                && solid.get((y * s.getZ() + z) * s.getX() + x),
                size);
    }

    @FunctionalInterface
    private interface Volume {
        boolean includes(int x, int y, int z, BlockPos size);

        default boolean shell(int x, int y, int z, BlockPos size) {
            if (!includes(x, y, z, size)) return false;
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++)
                        if (!includes(x + dx, y + dy, z + dz, size)) return true;
            return false;
        }
    }

    private static BitSet build(Volume shape, BlockPos size) {
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        var shell = new BitSet(sx * sy * sz);
        var removable = new BitSet(sx * sy * sz);
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    if (!shape.shell(x, y, z, size)) continue;
                    int i = (y * sz + z) * sx + x;
                    shell.set(i);
                    if (shape.includes(x - 1, y, z, size)
                            && shape.includes(x + 1, y, z, size)
                            && shape.includes(x, y - 1, z, size)
                            && shape.includes(x, y + 1, z, size)
                            && shape.includes(x, y, z - 1, size)
                            && shape.includes(x, y, z + 1, size)) removable.set(i);
                }
        // The six-face outer surface is immutable. Extra diagonal bridges are
        // removed only if neighboring blocks retain their local face paths.
        // Keeping every outer face voxel also prevents any face-connected air leak.
        boolean mirrorX = true, mirrorY = true, mirrorZ = true;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    boolean in = shape.includes(x, y, z, size);
                    mirrorX &= in == shape.includes(sx - 1 - x, y, z, size);
                    mirrorY &= in == shape.includes(x, sy - 1 - y, z, size);
                    mirrorZ &= in == shape.includes(x, y, sz - 1 - z, size);
                }
        // Thin the deepest interior bridges first. A coordinate-ordered sweep
        // can remove an outer bridge first and make a more visible inner tooth
        // essential afterwards. This order changes only which safe bridge wins;
        // every removal below still proves face connectivity and symmetry.
        record Bridge(int index, int neighbors, double radius) {}
        var candidates =
                removable.stream()
                        .mapToObj(
                                i ->
                                        new Bridge(
                                                i,
                                                solidNeighbors(shape, i, sx, sy, sz, size),
                                                radius(i, sx, sy, sz)))
                        .sorted(
                                java.util.Comparator.comparingInt(Bridge::neighbors)
                                        .reversed()
                                        .thenComparingDouble(Bridge::radius)
                                        .thenComparingInt(Bridge::index))
                        .mapToInt(Bridge::index)
                        .toArray();
        int[] orbit = new int[8];
        boolean changed;
        do {
            changed = false;
            for (int i : candidates) {
                if (!removable.get(i)) continue;
                int x = i % sx, z = i / sx % sz, y = i / (sx * sz), count = 0;
                for (int my = 0; my <= (mirrorY ? 1 : 0); my++)
                    for (int mz = 0; mz <= (mirrorZ ? 1 : 0); mz++)
                        for (int mx = 0; mx <= (mirrorX ? 1 : 0); mx++) {
                            int index =
                                    (((my == 0 ? y : sy - 1 - y) * sz + (mz == 0 ? z : sz - 1 - z))
                                                    * sx
                                            + (mx == 0 ? x : sx - 1 - x));
                            boolean duplicate = false;
                            for (int j = 0; j < count; j++) duplicate |= orbit[j] == index;
                            if (!duplicate) orbit[count++] = index;
                        }
                java.util.Arrays.sort(orbit, 0, count);
                if (orbit[0] != i) continue;
                int removed = 0;
                for (; removed < count; removed++) {
                    int index = orbit[removed];
                    if (!removable.get(index)
                            || !connectedFaces(neighborhood(shell, index, sx, sy, sz))) break;
                    shell.clear(index);
                }
                // Commit complete symmetry orbits only. Each individual removal
                // preserves a face path; rolling back a rejected orbit is lossless.
                if (removed == count) {
                    for (int j = 0; j < count; j++) removable.clear(orbit[j]);
                    changed = true;
                } else for (int j = 0; j < removed; j++) shell.set(orbit[j]);
            }
        } while (changed);
        // Local paths are sufficient, but not necessary: a stair-step can already
        // connect through the opposite wall. Remove those remaining redundant
        // mirror groups only after proving connectivity of the complete shell.
        int[] queue = new int[shell.cardinality()];
        var visited = new BitSet(sx * sy * sz);
        for (int i : candidates) {
            if (!removable.get(i)) continue;
            int x = i % sx, z = i / sx % sz, y = i / (sx * sz), count = 0;
            for (int my = 0; my <= (mirrorY ? 1 : 0); my++)
                for (int mz = 0; mz <= (mirrorZ ? 1 : 0); mz++)
                    for (int mx = 0; mx <= (mirrorX ? 1 : 0); mx++) {
                        int index =
                                (((my == 0 ? y : sy - 1 - y) * sz + (mz == 0 ? z : sz - 1 - z)) * sx
                                        + (mx == 0 ? x : sx - 1 - x));
                        boolean duplicate = false;
                        for (int j = 0; j < count; j++) duplicate |= orbit[j] == index;
                        if (!duplicate) orbit[count++] = index;
                    }
            java.util.Arrays.sort(orbit, 0, count);
            if (orbit[0] != i) continue;
            for (int j = 0; j < count; j++) shell.clear(orbit[j]);
            if (connected(shell, sx, sy, sz, queue, visited)) {
                for (int j = 0; j < count; j++) removable.clear(orbit[j]);
            } else for (int j = 0; j < count; j++) shell.set(orbit[j]);
        }
        return shell;
    }

    private static int solidNeighbors(Volume shape, int i, int sx, int sy, int sz, BlockPos size) {
        int x = i % sx, z = i / sx % sz, y = i / (sx * sz), n = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++)
                    if (shape.includes(x + dx, y + dy, z + dz, size)) n++;
        return n;
    }

    private static double radius(int i, int sx, int sy, int sz) {
        double x = (2.0 * (i % sx) + 1 - sx) / sx,
                y = (2.0 * (i / (sx * sz)) + 1 - sy) / sy,
                z = (2.0 * (i / sx % sz) + 1 - sz) / sz;
        return x * x + y * y + z * z;
    }

    private static boolean connected(
            BitSet shell, int sx, int sy, int sz, int[] queue, BitSet visited) {
        int first = shell.nextSetBit(0);
        if (first < 0) return false;
        visited.clear();
        visited.set(first);
        queue[0] = first;
        int head = 0, tail = 1;
        while (head < tail) {
            int i = queue[head++], x = i % sx, z = i / sx % sz, y = i / (sx * sz);
            if (x > 0) tail = visit(i - 1, shell, visited, queue, tail);
            if (x + 1 < sx) tail = visit(i + 1, shell, visited, queue, tail);
            if (z > 0) tail = visit(i - sx, shell, visited, queue, tail);
            if (z + 1 < sz) tail = visit(i + sx, shell, visited, queue, tail);
            if (y > 0) tail = visit(i - sx * sz, shell, visited, queue, tail);
            if (y + 1 < sy) tail = visit(i + sx * sz, shell, visited, queue, tail);
        }
        return tail == shell.cardinality();
    }

    private static int visit(int i, BitSet shell, BitSet seen, int[] queue, int tail) {
        if (shell.get(i) && !seen.get(i)) {
            seen.set(i);
            queue[tail++] = i;
        }
        return tail;
    }

    private static long neighborhood(BitSet shell, int i, int sx, int sy, int sz) {
        int x = i % sx, z = i / sx % sz, y = i / (sx * sz);
        long occupied = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    int a = x + dx, b = y + dy, c = z + dz;
                    if (a >= 0
                            && a < sx
                            && b >= 0
                            && b < sy
                            && c >= 0
                            && c < sz
                            && shell.get((b * sz + c) * sx + a))
                        occupied |= 1L << ((dy + 1) * 9 + (dz + 1) * 3 + dx + 1);
                }
        return occupied & ~CENTER;
    }

    private static final long CENTER = 1L << 13;
    private static final long FACES =
            (1L << 4) | (1L << 10) | (1L << 12) | (1L << 14) | (1L << 16) | (1L << 22);
    private static final long[] ADJACENT = new long[27];

    static {
        for (int i = 0; i < 27; i++) {
            int x = i % 3, z = i / 3 % 3, y = i / 9;
            if (x > 0) ADJACENT[i] |= 1L << (i - 1);
            if (x < 2) ADJACENT[i] |= 1L << (i + 1);
            if (z > 0) ADJACENT[i] |= 1L << (i - 3);
            if (z < 2) ADJACENT[i] |= 1L << (i + 3);
            if (y > 0) ADJACENT[i] |= 1L << (i - 9);
            if (y < 2) ADJACENT[i] |= 1L << (i + 9);
        }
    }

    private static boolean connectedFaces(long mask) {
        long required = mask & FACES;
        if (required == 0) return false;
        long frontier = Long.lowestOneBit(required), seen = frontier;
        while (frontier != 0) {
            int i = Long.numberOfTrailingZeros(frontier);
            frontier &= frontier - 1;
            long next = ADJACENT[i] & mask & ~seen;
            seen |= next;
            frontier |= next;
        }
        return (seen & required) == required;
    }
}
