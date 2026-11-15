package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.config.EnvironmentRule;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/** Incrementally intersects the valid origin heights of every footprint column. */
public final class FootprintEnvironmentProfile {
    private final ServerLevel level;
    private final SpawnSpatialSnapshot snapshot;
    private final int x, z, sizeX, sizeZ, sizeY, minY, maxY;
    private final EnvironmentRule rule;
    private final boolean above;
    private int offsetX, offsetZ;
    private ColumnEnvironmentProfile column;
    private List<OriginYInterval> intervals;
    private boolean complete;

    public record ColumnKey(
            int x, int z, EnvironmentRule rule, boolean above, int height, int minY, int maxY) {}

    private final java.util.Map<ColumnKey, List<OriginYInterval>> cache;
    private ColumnKey columnKey;

    public FootprintEnvironmentProfile(
            ServerLevel level,
            SpawnSpatialSnapshot snapshot,
            int x,
            int z,
            EnvironmentRule rule,
            boolean above,
            int sizeY,
            int minY,
            int maxY,
            int sizeX,
            int sizeZ) {
        this(
                level,
                snapshot,
                x,
                z,
                rule,
                above,
                sizeY,
                minY,
                maxY,
                sizeX,
                sizeZ,
                new java.util.HashMap<>());
    }

    public FootprintEnvironmentProfile(
            ServerLevel level,
            SpawnSpatialSnapshot snapshot,
            int x,
            int z,
            EnvironmentRule rule,
            boolean above,
            int sizeY,
            int minY,
            int maxY,
            int sizeX,
            int sizeZ,
            java.util.Map<ColumnKey, List<OriginYInterval>> cache) {
        this.cache = cache;
        this.level = level;
        this.snapshot = snapshot;
        this.x = x;
        this.z = z;
        this.rule = rule;
        this.above = above;
        this.sizeY = sizeY;
        this.minY = minY;
        this.maxY = maxY;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        if (sizeX <= 0 || sizeZ <= 0 || minY > maxY) {
            throw new IllegalArgumentException("Invalid footprint or origin heights.");
        }
        intervals = List.of(new OriginYInterval(minY, maxY));
    }

    public boolean isComplete() {
        return complete;
    }

    public List<OriginYInterval> intervals() {
        return List.copyOf(intervals);
    }

    public ColumnEnvironmentProfile.StepResult step(int budget) {
        int used = 0;
        while (!complete && used < budget) {
            if (rule == null || rule.type() == EnvironmentRule.Type.NONE) {
                complete = true;
                used++;
                break;
            }
            columnKey = new ColumnKey(x + offsetX, z + offsetZ, rule, above, sizeY, minY, maxY);
            var known = cache.get(columnKey);
            if (known == null) {
                if (column == null)
                    column =
                            new ColumnEnvironmentProfile(
                                    level,
                                    snapshot,
                                    x + offsetX,
                                    z + offsetZ,
                                    rule,
                                    above,
                                    sizeY,
                                    minY,
                                    maxY);
                var result = column.step(budget - used);
                used += result.operationsUsed();
                if (result.status() != ColumnEnvironmentProfile.Status.COMPLETE)
                    return new ColumnEnvironmentProfile.StepResult(result.status(), used);
                known = column.intervals();
                if (cache.size() >= 32768) cache.clear();
                cache.put(columnKey, known);
            } else used++; // Cached intersection still consumes work budget.
            intervals = intersect(intervals, known);
            column = null;
            if (++offsetZ == sizeZ) {
                offsetZ = 0;
                offsetX++;
            }
            complete = intervals.isEmpty() || offsetX == sizeX;
        }
        return new ColumnEnvironmentProfile.StepResult(
                complete
                        ? ColumnEnvironmentProfile.Status.COMPLETE
                        : ColumnEnvironmentProfile.Status.RUNNING,
                used);
    }

    private static List<OriginYInterval> intersect(
            List<OriginYInterval> a, List<OriginYInterval> b) {
        final var result = new ArrayList<OriginYInterval>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            final var left = a.get(i);
            final var right = b.get(j);
            int min = Math.max(left.minY(), right.minY());
            int max = Math.min(left.maxY(), right.maxY());
            if (min <= max) {
                result.add(new OriginYInterval(min, max));
            }
            if (left.maxY() < right.maxY()) i++;
            else j++;
        }
        return result;
    }
}
