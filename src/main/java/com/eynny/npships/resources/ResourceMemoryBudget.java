package com.eynny.npships.resources;

/**
 * Server-owned admission for explicitly accounted allocation capacity. This is not a universal
 * Java-heap/native-memory estimator. Owners reserve before allocation, including replacement
 * overlap, and release only after dropping their owned reference. Cleanup never reserves.
 */
public final class ResourceMemoryBudget {
    private final java.lang.ref.ReferenceQueue<Scope> collected =
            new java.lang.ref.ReferenceQueue<>();
    private final java.util.Set<TrackedScope> scopes = new java.util.HashSet<>();

    private static final class TrackedScope extends java.lang.ref.WeakReference<Scope> {
        long bytes;

        TrackedScope(Scope scope, java.lang.ref.ReferenceQueue<Scope> queue) {
            super(scope, queue);
        }
    }

    private void prune() {
        TrackedScope reference;
        while ((reference = (TrackedScope) collected.poll()) != null) {
            if (scopes.remove(reference)) {
                used -= reference.bytes;
                reference.bytes = 0;
            }
        }
    }

    private final long limit;
    private long used, peak;

    public ResourceMemoryBudget(long limit) {
        if (limit < 1) throw new IllegalArgumentException("Memory limit must be positive");
        this.limit = limit;
    }

    public synchronized Scope scope(String owner) {
        prune();
        var scope = new Scope(this, owner);
        scope.tracked = new TrackedScope(scope, collected);
        scopes.add(scope.tracked);
        return scope;
    }

    public synchronized long used() {
        prune();
        return used;
    }

    public synchronized long peak() {
        return peak;
    }

    public long limit() {
        return limit;
    }

    /** Primitive payload/reference-slot capacity plus a declared 32-byte array-layout allowance. */
    public static long arrayBytes(long length, int elementBytes) {
        if (length < 0 || elementBytes < 1 || length > (Long.MAX_VALUE - 32) / elementBytes)
            throw new ResourceLimitException(
                    "working memory arithmetic", 0, Long.MAX_VALUE, Long.MAX_VALUE);
        long size = 32 + length * elementBytes;
        if (size > Long.MAX_VALUE - 7)
            throw new ResourceLimitException("working memory arithmetic", 0, size, Long.MAX_VALUE);
        return (size + 7) & ~7L;
    }

    public static final class Scope implements AutoCloseable {
        private final ResourceMemoryBudget budget;
        private final String owner;
        private long used;
        private boolean closed, retired;
        private TrackedScope tracked;

        private Scope(ResourceMemoryBudget budget, String owner) {
            this.budget = budget;
            this.owner = java.util.Objects.requireNonNull(owner);
        }

        public void reserve(long bytes) {
            synchronized (budget) {
                budget.prune();
                if (closed || retired)
                    throw new IllegalStateException("Memory scope closed/retired: " + owner);
                if (budget.used < 0
                        || bytes < 0
                        || bytes > Long.MAX_VALUE - budget.used
                        || bytes > budget.limit - budget.used)
                    throw new ResourceLimitException(
                            "working memory (" + owner + ")", budget.used, bytes, budget.limit);
                long total = budget.used + bytes;
                used = ResourceLimitException.add("working memory scope", used, bytes, 0);
                tracked.bytes = used;
                budget.used = total;
                budget.peak = Math.max(budget.peak, total);
            }
        }

        public void release(long bytes) {
            synchronized (budget) {
                if (bytes < 0 || bytes > used)
                    throw new IllegalArgumentException("Unowned memory release: " + owner);
                used -= bytes;
                budget.used -= bytes;
                tracked.bytes = used;
            }
        }

        public long used() {
            synchronized (budget) {
                return used;
            }
        }

        public boolean closed() {
            synchronized (budget) {
                return closed;
            }
        }

        /**
         * Seals growth but preserves the charge while an external snapshot still retains this
         * scope.
         */
        public void retire() {
            synchronized (budget) {
                if (!closed) retired = true;
            }
        }

        public boolean retired() {
            synchronized (budget) {
                return retired;
            }
        }

        @Override
        public void close() {
            synchronized (budget) {
                if (!closed) {
                    budget.used -= used;
                    used = 0;
                    tracked.bytes = 0;
                    budget.scopes.remove(tracked);
                    closed = true;
                }
            }
        }
    }
}
