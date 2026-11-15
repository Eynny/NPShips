package com.eynny.npships.naturalspawn.search;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Exact necessary-condition filter. Each origin belongs to one anchored tile. */
final class SparseOriginQueue {
    private record Tile(int anchorX, int anchorZ, StructurePlacementSearchRegion origins) {}

    private final int stepX, stepZ;
    private final PlacementProfileCache profiles;
    private final Supplier<BlockPos> preferred;
    private final PriorityQueue<Tile> tiles;
    private Tile active;
    private PlacementProfileCache.Task profile;
    private boolean emitting;
    private int emitX, emitZ;
    private long tested, rejected;
    private long preparedTiles;

    SparseOriginQueue(
            int width, int depth, PlacementProfileCache profiles, Supplier<BlockPos> preferred) {
        stepX = Integer.highestOneBit(width);
        stepZ = Integer.highestOneBit(depth);
        this.profiles = profiles;
        this.preferred = preferred;
        tiles =
                new PriorityQueue<>(
                        Comparator.comparingLong(this::distance)
                                .thenComparingInt(t -> t.origins.minX())
                                .thenComparingInt(t -> t.origins.minZ())
                                .thenComparingInt(t -> t.origins.minY()));
    }

    static int anchor(int coordinate, int step) {
        return -Math.floorDiv(-coordinate, step) * step;
    }

    Addition add(StructurePlacementSearchRegion region, List<OriginYInterval> heights) {
        return new Addition(region, heights);
    }

    /** Resumes the original anchor-X/anchor-Z/height insertion order, one tile per operation. */
    final class Addition {
        private final StructurePlacementSearchRegion region;
        private final List<OriginYInterval> heights;
        private int ax, az, height;

        private Addition(StructurePlacementSearchRegion region, List<OriginYInterval> heights) {
            this.region = region;
            this.heights = heights;
            ax = anchor(region.minX(), stepX);
            az = anchor(region.minZ(), stepZ);
        }

        boolean step() {
            var y = heights.get(height);
            tiles.add(
                    new Tile(
                            ax,
                            az,
                            new StructurePlacementSearchRegion(
                                    Math.max(region.minX(), ax - stepX + 1),
                                            Math.min(region.maxX(), ax),
                                    y.minY(), y.maxY(),
                                    Math.max(region.minZ(), az - stepZ + 1),
                                            Math.min(region.maxZ(), az))));
            preparedTiles++;
            if (++height == heights.size()) {
                height = 0;
                az += stepZ;
                if (az > anchor(region.maxZ(), stepZ)) {
                    az = anchor(region.minZ(), stepZ);
                    ax += stepX;
                }
            }
            return ax > anchor(region.maxX(), stepX);
        }
    }

    private long distance(Tile tile) {
        BlockPos reference = preferred.get();
        if (reference == null) return 0;
        var r = tile.origins;
        long dx =
                Math.max(r.minX(), Math.min(r.maxX(), reference.getX())) - (long) reference.getX();
        long dz =
                Math.max(r.minZ(), Math.min(r.maxZ(), reference.getZ())) - (long) reference.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Resolve every tile which could contain an origin nearer than the next exact task. WAITING
     * retains the tile, so an unloaded nearer region cannot silently lose priority.
     */
    ColumnEnvironmentProfile.StepResult prepare(
            long nextOriginDistance,
            int budget,
            boolean snapshotOnly,
            Consumer<StructurePlacementSearchColumnTask> emit) {
        int used = 0;
        while (used < budget) {
            if (active == null) {
                if (tiles.isEmpty() || distance(tiles.peek()) > nextOriginDistance)
                    return new ColumnEnvironmentProfile.StepResult(
                            ColumnEnvironmentProfile.Status.COMPLETE, used);
                active = tiles.remove();
                var r = active.origins;
                profile = profiles.column(active.anchorX, active.anchorZ, r.minY(), r.maxY());
                emitting = false;
            }
            if (!emitting) {
                var result = profile.step(budget - used);
                used += result.operationsUsed();
                if (result.status() == ColumnEnvironmentProfile.Status.WAITING_FOR_DATA
                        && snapshotOnly) {
                    active = null;
                    used++;
                    continue;
                }
                if (result.status() != ColumnEnvironmentProfile.Status.COMPLETE)
                    return new ColumnEnvironmentProfile.StepResult(result.status(), used);
                tested++;
                if (profile.intervals().isEmpty()) {
                    rejected++;
                    active = null;
                    continue;
                }
                emitting = true;
                emitX = active.origins.minX();
                emitZ = active.origins.minZ();
            }
            var r = active.origins;
            while (used < budget && emitX <= r.maxX()) {
                emit.accept(
                        new StructurePlacementSearchColumnTask(emitX, emitZ, r.minY(), r.maxY()));
                used++;
                if (++emitZ > r.maxZ()) {
                    emitZ = r.minZ();
                    emitX++;
                }
            }
            if (emitX > r.maxX()) {
                active = null;
                // Let the caller recompute the nearest exact-task distance before
                // considering further tiles (initially the queue may have been empty).
                return new ColumnEnvironmentProfile.StepResult(
                        ColumnEnvironmentProfile.Status.COMPLETE, used);
            }
        }
        return new ColumnEnvironmentProfile.StepResult(
                ColumnEnvironmentProfile.Status.RUNNING, used);
    }

    boolean precedes(long originDistance) {
        return active != null || !tiles.isEmpty() && distance(tiles.peek()) <= originDistance;
    }

    void clear() {
        tiles.clear();
        active = null;
        profile = null;
    }

    String diagnosticState() {
        return "anchors="
                + tested
                + ", rejectedTiles="
                + rejected
                + ", pendingTiles="
                + tiles.size()
                + ", preparedTiles="
                + preparedTiles;
    }
}
