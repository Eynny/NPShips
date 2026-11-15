package com.eynny.npships.diagnostics;

import com.mojang.logging.LogUtils;

import java.util.EnumMap;
import java.util.function.Supplier;

/** Opt-in timings; bounded phase names, no document or world references retained. */
public final class EditorWorkMetrics {
    public enum Phase {
        QUEUE,
        BACKGROUND,
        APPLY,
        GEOMETRY,
        COMPOSE,
        MESH
    }

    private static final boolean ENABLED = Boolean.getBoolean("npships.profileEditor");
    private static final EnumMap<Phase, long[]> totals = new EnumMap<>(Phase.class);

    private EditorWorkMetrics() {}

    private static boolean enabled() {
        return ENABLED && com.eynny.npships.network.DevelopmentModeNetworking.clientEnabled();
    }

    public static long queuedAt() {
        return enabled() ? System.nanoTime() : 0;
    }

    /** Includes waiting behind an active calculation and waiting in the executor. */
    public static void started(long queuedAt) {
        if (enabled() && queuedAt != 0) record(Phase.QUEUE, System.nanoTime() - queuedAt);
    }

    public static <T> T measure(Phase phase, Supplier<T> work) {
        if (!enabled()) return work.get();
        long started = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(phase, System.nanoTime() - started);
        }
    }

    private static synchronized void record(Phase phase, long elapsed) {
        var sample = totals.computeIfAbsent(phase, ignored -> new long[3]);
        sample[0]++;
        sample[1] += elapsed;
        sample[2] = Math.max(sample[2], elapsed);
    }

    /** Phases may nest. Their totals must not be added to estimate frame time. */
    public static synchronized void report() {
        if (!enabled()) return;
        totals.forEach(
                (phase, sample) ->
                        LogUtils.getLogger()
                                .info(
                                        "[NPShips editor timing] {}: count={}, total={} ms, max={} ms",
                                        phase,
                                        sample[0],
                                        sample[1] / 1_000_000d,
                                        sample[2] / 1_000_000d));
        totals.clear();
    }
}
