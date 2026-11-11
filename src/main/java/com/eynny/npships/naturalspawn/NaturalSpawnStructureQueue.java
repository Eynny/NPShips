package com.eynny.npships.naturalspawn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Frozen C8 draws; duplicate slots consume probability/work budget without another search. */
public final class NaturalSpawnStructureQueue {
    private final List<NaturalSpawnStructureCandidate> drawn;
    private final int drawSlots;
    private int cursor;

    public NaturalSpawnStructureQueue(
            List<NaturalSpawnStructureCandidate> candidates, Random random) {
        this(candidates, random, NaturalSpawnLimits.max_structures_per_attempt());
    }

    public NaturalSpawnStructureQueue(
            List<NaturalSpawnStructureCandidate> candidates, Random random, int slots) {
        if (slots < 1 || slots > 8)
            throw new IllegalArgumentException("C8 draw slots must be 1..8");
        var source = List.copyOf(candidates);
        var selected =
                new ArrayList<NaturalSpawnStructureCandidate>(Math.min(slots, source.size()));
        var seen = new HashSet<net.minecraft.resources.ResourceLocation>();
        double maximum = Double.NEGATIVE_INFINITY;
        for (var c : source) maximum = Math.max(maximum, logWeight(c));
        double[] cumulative = new double[source.size()];
        double total = 0;
        for (int i = 0; i < source.size(); i++) {
            // Log scaling supports tiny absolute probabilities and finite addon weights.
            total += Math.exp(logWeight(source.get(i)) - maximum);
            cumulative[i] = total;
        }
        drawSlots = source.isEmpty() ? 0 : slots;
        for (int slot = 0; slot < drawSlots; slot++) {
            double draw = Math.min(Math.nextDown(total), random.nextDouble() * total);
            int low = 0, high = cumulative.length - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (draw < cumulative[middle]) high = middle;
                else low = middle + 1;
            }
            var c = source.get(low);
            if (seen.add(c.structureId())) selected.add(c);
        }
        drawn = List.copyOf(selected);
    }

    private static double logWeight(NaturalSpawnStructureCandidate c) {
        return c.raritySelection() == null
                ? Math.log(c.config().selectionWeight())
                : c.raritySelection().logWeight();
    }

    public NaturalSpawnStructureCandidate pollRandom() {
        return isEmpty() ? null : drawn.get(cursor++);
    }

    public boolean isEmpty() {
        return cursor == drawn.size();
    }

    public int remainingCount() {
        return drawn.size() - cursor;
    }

    public int attemptedCount() {
        return cursor;
    }

    public int drawSlots() {
        return drawSlots;
    }
}
