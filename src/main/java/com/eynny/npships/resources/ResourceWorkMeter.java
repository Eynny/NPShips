package com.eynny.npships.resources;

/** Owner-local, non-enforcing observations. Families are submissions, not time or native work. */
public final class ResourceWorkMeter {
    public enum Family {
        NBT_NODE_VISIT,
        NBT_COPY_INPUT_NODE,
        NATIVE_TEMPLATE_LOAD,
        TEMPLATE_BLOCK_ENTRY,
        TEMPLATE_ENTITY_ENTRY,
        CREW_PRIMITIVE,
        CLEAR_CELL_PLAN,
        STATIC_PLACEMENT_CALL,
        ASSEMBLY_CALL
    }

    private final long[] counts = new long[Family.values().length];
    private long searchCredits, scanCredits;
    private boolean saturated;

    private long add(long previous, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative work observation");
        if (amount > Long.MAX_VALUE - previous) {
            saturated = true;
            return Long.MAX_VALUE;
        }
        return previous + amount;
    }

    public void record(Family family, long amount) {
        counts[family.ordinal()] = add(counts[family.ordinal()], amount);
    }

    public long count(Family family) {
        return counts[family.ordinal()];
    }

    public boolean saturated() {
        return saturated;
    }

    public void search(long search, long scan) {
        searchCredits = add(searchCredits, search);
        scanCredits = add(scanCredits, scan);
    }

    public long searchCredits() {
        return searchCredits;
    }

    public long scanCredits() {
        return scanCredits;
    }
}
