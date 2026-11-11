package com.eynny.npships.resources;

/** A qualified admission refusal; mandatory cleanup must never charge this limit. */
public final class ResourceLimitException extends IllegalArgumentException {
    private final String resource;
    private final long used, requested, limit;

    public ResourceLimitException(String resource, long used, long requested, long limit) {
        super(
                "NPShips resource limit: "
                        + resource
                        + " used="
                        + used
                        + " requested="
                        + requested
                        + " limit="
                        + limit
                        + ". Increase the corresponding SERVER resource allowance or reduce this workload.");
        this.resource = resource;
        this.used = used;
        this.requested = requested;
        this.limit = limit;
    }

    public String resource() {
        return resource;
    }

    public long used() {
        return used;
    }

    public long requested() {
        return requested;
    }

    public long limit() {
        return limit;
    }

    public static long add(String resource, long used, long requested, long limit) {
        if (used < 0 || requested < 0 || requested > Long.MAX_VALUE - used)
            throw new ResourceLimitException(resource, used, requested, limit);
        long next = used + requested;
        if (limit > 0 && next > limit)
            throw new ResourceLimitException(resource, used, requested, limit);
        return next;
    }
}
