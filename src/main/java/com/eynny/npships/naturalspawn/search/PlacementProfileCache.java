package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.InsideRule;
import com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact combined profiles shared by overlapping origins of one candidate. */
final class PlacementProfileCache {
    record Key(
            int x,
            int z,
            int width,
            int depth,
            int minY,
            int maxY,
            com.eynny.npships.config.NPShipsStructureConfig.EnvironmentConfig environment,
            int height) {}

    private final Map<Key, List<OriginYInterval>> cache;
    private final ServerLevel level;
    private final SpawnSpatialSnapshot snapshot;
    private final NaturalSpawnStructureCandidate candidate;

    PlacementProfileCache(
            ServerLevel level,
            SpawnSpatialSnapshot snapshot,
            NaturalSpawnStructureCandidate candidate) {
        this.level = level;
        this.snapshot = snapshot;
        this.candidate = candidate;
        this.cache = snapshot.placementProfiles;
    }

    private Key key(int x, int z, int width, int depth, int minY, int maxY) {
        return new Key(
                x,
                z,
                width,
                depth,
                minY,
                maxY,
                candidate.config().environment(),
                candidate.size().getY());
    }

    Task task(int x, int z, int minY, int maxY) {
        return new Task(x, z, candidate.size().getX(), candidate.size().getZ(), minY, maxY);
    }

    Task column(int x, int z, int minY, int maxY) {
        return new Task(x, z, 1, 1, minY, maxY);
    }

    // Canonically aligned dyadic rectangles share intersections between adjacent origins.
    // Cache only complete nodes; an unscanned leaf suspends without caching a rejection.
    final class Task {
        private final java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<>();
        private final int minY, maxY;
        private List<OriginYInterval> intervals;
        private boolean complete;

        private final class Node {
            final Key key;
            int stage;
            ColumnEnvironmentProfile column;
            List<OriginYInterval> value;

            Node(Key key) {
                this.key = key;
                value = full();
            }
        }

        Task(int x, int z, int width, int depth, int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
            intervals = full();
            var roots = new ArrayList<Key>();
            for (int a = x; a < x + width; ) {
                int w = span(a, x + width - a);
                for (int b = z; b < z + depth; ) {
                    int d = span(b, z + depth - b);
                    roots.add(key(a, b, w, d, minY, maxY));
                    b += d;
                }
                a += w;
            }
            // Each root is finished independently; no common parent outside the footprint.
            pending.addAll(roots);
        }

        private final java.util.ArrayDeque<Key> pending = new java.util.ArrayDeque<>();

        private int span(int start, int remaining) {
            return Math.min(
                    start == 0 ? Integer.highestOneBit(remaining) : Integer.lowestOneBit(start),
                    Integer.highestOneBit(remaining));
        }

        private List<OriginYInterval> full() {
            return List.of(new OriginYInterval(minY, maxY));
        }

        List<OriginYInterval> intervals() {
            return intervals;
        }

        private void accept(List<OriginYInterval> result) {
            stack.pop();
            if (stack.isEmpty()) {
                intervals = intersect(intervals, result);
                complete = intervals.isEmpty() || pending.isEmpty();
            } else {
                var parent = stack.peek();
                parent.value = intersect(parent.value, result);
            }
        }

        ColumnEnvironmentProfile.StepResult step(int budget) {
            int used = 0;
            var env = candidate.config().environment();
            int height = candidate.size().getY();
            while (!complete && used < budget) {
                if (stack.isEmpty()) stack.push(new Node(pending.removeFirst()));
                var n = stack.peek();
                var k = n.key;
                var known = cache.get(k);
                if (known != null) {
                    used++;
                    accept(known);
                    continue;
                }
                if (k.width > 1 || k.depth > 1) {
                    if (n.stage == 2 || n.value.isEmpty()) {
                        used++;
                        cache.put(k, n.value);
                        accept(n.value);
                        continue;
                    }
                    boolean splitX = k.width >= k.depth;
                    int w = splitX ? k.width / 2 : k.width, d = splitX ? k.depth : k.depth / 2;
                    var child =
                            key(
                                    k.x + (splitX ? n.stage * w : 0),
                                    k.z + (splitX ? 0 : n.stage * d),
                                    w,
                                    d,
                                    minY,
                                    maxY);
                    n.stage++;
                    used++;
                    stack.push(new Node(child));
                    continue;
                }
                if (n.column == null) {
                    EnvironmentRule rule = n.stage == 0 ? env.under() : env.above();
                    int shift = 0;
                    if (n.stage == 2) {
                        shift = height;
                        var inside = env.inside();
                        rule =
                                inside == null || inside.type() == InsideRule.Type.IGNORE
                                        ? null
                                        : new EnvironmentRule(
                                                inside.type() == InsideRule.Type.CLEAR
                                                        ? EnvironmentRule.Type.AIR
                                                        : EnvironmentRule.Type.FLUID,
                                                height,
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                false,
                                                false,
                                                inside.fluid());
                    }
                    n.column =
                            new ColumnEnvironmentProfile(
                                    level,
                                    snapshot,
                                    k.x,
                                    k.z,
                                    rule,
                                    n.stage == 1,
                                    height,
                                    minY + shift,
                                    maxY + shift);
                }
                var result = n.column.step(budget - used);
                used += result.operationsUsed();
                if (result.status() != ColumnEnvironmentProfile.Status.COMPLETE)
                    return new ColumnEnvironmentProfile.StepResult(result.status(), used);
                var next = n.column.intervals();
                if (n.stage == 2)
                    next =
                            next.stream()
                                    .map(
                                            v ->
                                                    new OriginYInterval(
                                                            v.minY() - height, v.maxY() - height))
                                    .toList();
                n.value = intersect(n.value, next);
                n.column = null;
                if (++n.stage == 3 || n.value.isEmpty()) {
                    cache.put(k, n.value);
                    accept(n.value);
                }
            }
            return new ColumnEnvironmentProfile.StepResult(
                    complete
                            ? ColumnEnvironmentProfile.Status.COMPLETE
                            : ColumnEnvironmentProfile.Status.RUNNING,
                    used);
        }
    }

    static List<OriginYInterval> intersect(List<OriginYInterval> a, List<OriginYInterval> b) {
        var result = new ArrayList<OriginYInterval>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            var left = a.get(i);
            var right = b.get(j);
            int min = Math.max(left.minY(), right.minY()),
                    max = Math.min(left.maxY(), right.maxY());
            if (min <= max) result.add(new OriginYInterval(min, max));
            if (left.maxY() < right.maxY()) i++;
            else j++;
        }
        return result;
    }
}
