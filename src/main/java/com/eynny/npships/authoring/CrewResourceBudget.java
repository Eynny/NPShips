package com.eynny.npships.authoring;

import com.eynny.npships.resources.ResourceLimitException;
import com.eynny.npships.resources.ServerResourcePolicy;

/** One detached composition's crew admission and actual primitive submissions. */
final class CrewResourceBudget {
    enum Family {
        FACTORY_PROBE,
        GENERATED_FACTORY,
        NATIVE_LOAD,
        NATIVE_SAVE,
        POSITION_TRIAL,
        DOCUMENT_READ,
        COLLISION_QUERY,
        SHAPE_BOX,
        FLUID_TEST,
        SUPPORT_TEST,
        EQUIPMENT_DRAW
    }

    record Metrics(long requested, long plannedFactoryProbes, long work, long[] families) {
        Metrics {
            families = families.clone();
        }

        @Override
        public long[] families() {
            return families.clone();
        }

        long count(Family family) {
            return families[family.ordinal()];
        }
    }

    private static final int FAMILY_COUNT = Family.values().length;
    private final long maxGeneratedCrew;
    private final long maxFactoryProbes;
    private final long maxWork;
    private final long[] families = new long[FAMILY_COUNT];
    private long requested;
    private long plannedFactoryProbes;
    private long work;

    CrewResourceBudget(ServerResourcePolicy.Limits limits) {
        this(limits.maxGeneratedCrew(), limits.maxFactoryProbes(), limits.maxCrewWork());
    }

    CrewResourceBudget(long maxGeneratedCrew, long maxFactoryProbes, long maxWork) {
        if (maxGeneratedCrew < 0 || maxFactoryProbes < 0 || maxWork < 0)
            throw new IllegalArgumentException("Crew allowances must not be negative");
        this.maxGeneratedCrew = maxGeneratedCrew;
        this.maxFactoryProbes = maxFactoryProbes;
        this.maxWork = maxWork == 0 ? Long.MAX_VALUE : maxWork;
    }

    void planFactories(long count) {
        plannedFactoryProbes =
                add("crew factory probes", plannedFactoryProbes, count, maxFactoryProbes);
    }

    void request(long count) {
        requested = add("generated crew iterations", requested, count, maxGeneratedCrew);
    }

    void charge(Family family) {
        // A submitted native call counts once. Its uninterruptible internal work is not a time
        // unit.
        long next = add("crew preparation work", work, 1, maxWork);
        // Each family is a subset of checked work, so its increment cannot overflow first.
        families[family.ordinal()]++;
        work = next;
    }

    long requested() {
        return requested;
    }

    long factoryProbes() {
        return families[Family.FACTORY_PROBE.ordinal()];
    }

    long work() {
        return work;
    }

    Metrics metrics() {
        return new Metrics(requested, plannedFactoryProbes, work, families);
    }

    private static long add(String resource, long used, long count, long limit) {
        if (count < 0) throw new IllegalArgumentException("Negative crew resource request");
        if (count > limit || used > limit - count)
            throw new ResourceLimitException(resource, used, count, limit);
        return used + count;
    }
}
