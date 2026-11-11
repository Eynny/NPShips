package com.eynny.npships.naturalspawn.search;

import java.util.ArrayList;
import java.util.List;

/** Operations on sorted, disjoint, inclusive origin-height intervals. */
final class OriginYIntervals {
    private OriginYIntervals() {}

    static List<OriginYInterval> intersectIntervals(
            final List<OriginYInterval> under, final List<OriginYInterval> above) {
        if (under.isEmpty() || above.isEmpty()) {
            return List.of();
        }
        final List<OriginYInterval> result = new ArrayList<>();
        int underIndex = 0;
        int aboveIndex = 0;
        while (underIndex < under.size() && aboveIndex < above.size()) {
            final OriginYInterval a = under.get(underIndex);
            final OriginYInterval b = above.get(aboveIndex);
            final int min = Math.max(a.minY(), b.minY());
            final int max = Math.min(a.maxY(), b.maxY());
            if (min <= max) {
                result.add(new OriginYInterval(min, max));
            }
            if (a.maxY() < b.maxY()) {
                underIndex++;
            } else {
                aboveIndex++;
            }
        }
        return result;
    }

    static int distanceToInterval(final int y, final OriginYInterval interval) {
        if (y < interval.minY()) {
            return interval.minY() - y;
        }
        if (y > interval.maxY()) {
            return y - interval.maxY();
        }
        return 0;
    }
}
