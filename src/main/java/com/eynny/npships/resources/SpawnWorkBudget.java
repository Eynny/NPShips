package com.eynny.npships.resources;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One server-owner cooperative spawn allowance shared by all jobs, phases and dimensions. The
 * existing manager starts each tick and owns this meter; this class schedules and retains nothing.
 * Only synchronous work intervals are charged. Native calls cannot be interrupted by this meter.
 */
public final class SpawnWorkBudget {
    public static final double DEFAULT_MILLISECONDS = 20.0;
    public static final double MIN_MILLISECONDS = Double.MIN_VALUE;
    public static final double MAX_MILLISECONDS = Double.MAX_VALUE;

    private final LongSupplier clock;
    private boolean initialized;
    private long tick, limit, used, started;
    private int depth;

    public SpawnWorkBudget() {
        this(System::nanoTime);
    }

    /** Package-local monotonic clock injection keeps elapsed-time tests independent of load. */
    SpawnWorkBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * The token must come from the SERVER tick, never a dimension clock or job cursor. Calling
     * again in the same tick applies a live limit change without granting fresh time or resetting
     * work.
     */
    public void beginTick(long serverTick, double milliseconds) {
        long nextLimit = nanos(milliseconds);
        if (!initialized || tick != serverTick) {
            if (depth != 0) throw new IllegalStateException("Spawn work crossed a server tick");
            tick = serverTick;
            used = 0;
            initialized = true;
        }
        limit = nextLimit;
    }

    public static long nanos(double milliseconds) {
        if (!Double.isFinite(milliseconds)
                || milliseconds < MIN_MILLISECONDS
                || milliseconds > MAX_MILLISECONDS)
            throw new IllegalArgumentException(
                    "Spawn budget must be a positive finite number of milliseconds");
        // Keep tiny positive budgets usable; Math.round saturates oversized budgets safely.
        return Math.max(1L, Math.round(milliseconds * 1_000_000.0));
    }

    /**
     * Returns null after exhaustion; the caller keeps its cursor and yields to its existing owner.
     */
    public Work tryStartWork() {
        requireTick();
        long now = clock.getAsLong();
        if (usedAt(now) >= limit) return null;
        if (depth++ == 0) started = now;
        return new Work();
    }

    /** Mandatory cancellation/release is measured even after exhaustion; it never seeks quota. */
    public Work measureRequiredWork() {
        requireTick();
        if (depth++ == 0) started = clock.getAsLong();
        return new Work();
    }

    public boolean hasRemaining() {
        return remainingNanos() > 0;
    }

    public long remainingNanos() {
        return Math.max(0, limitNanos() - usedNanos());
    }

    public long usedNanos() {
        requireTick();
        return usedAt(clock.getAsLong());
    }

    public long limitNanos() {
        requireTick();
        return limit;
    }

    /** Actual synchronous excess, including an active indivisible native call after it returns. */
    public long overrunNanos() {
        return Math.max(0, usedNanos() - limitNanos());
    }

    private long usedAt(long now) {
        return depth == 0 ? used : Math.addExact(used, elapsed(now));
    }

    private long elapsed(long now) {
        // Subtraction deliberately supports System.nanoTime's signed wraparound.
        long elapsed = now - started;
        if (elapsed < 0) throw new IllegalStateException("Spawn work clock moved backwards");
        return elapsed;
    }

    private void requireTick() {
        if (!initialized) throw new IllegalStateException("Spawn budget tick has not begun");
    }

    /** Nested provider calls share the outer interval and cannot double-charge elapsed time. */
    public final class Work implements AutoCloseable {
        private boolean closed;

        private Work() {}

        @Override
        public void close() {
            if (closed) return;
            if (depth == 1) used = Math.addExact(used, elapsed(clock.getAsLong()));
            depth--;
            closed = true;
        }
    }
}
