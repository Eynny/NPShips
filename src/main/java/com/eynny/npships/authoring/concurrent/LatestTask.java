package com.eynny.npships.authoring.concurrent;

import com.eynny.npships.diagnostics.EditorWorkMetrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One immutable calculation at a time; intermediate requests/results are superseded, never queued
 * unboundedly. Submit, discard and poll belong to the owning thread; only calculation runs on the
 * executor. Superseding a task discards its result, not its running calculation, so callbacks must
 * use detached data and never mutate the active document or world.
 */
public final class LatestTask<T> {
    private final Executor executor;
    private CompletableFuture<T> running;
    private Supplier<T> pending;
    private long version, runningVersion;
    private long pendingQueuedAt;

    public LatestTask(Executor executor) {
        this.executor = executor;
    }

    public void submit(Supplier<T> calculation) {
        version++;
        pending = calculation;
        pendingQueuedAt = EditorWorkMetrics.queuedAt();
        start();
    }

    public void discard() {
        version++;
        pending = null;
    }

    public boolean busy() {
        return pending != null || running != null && runningVersion == version;
    }

    private void start() {
        if (running != null || pending == null) return;
        var next = pending;
        long queuedAt = pendingQueuedAt;
        pending = null;
        runningVersion = version;
        running =
                CompletableFuture.supplyAsync(
                        () -> {
                            EditorWorkMetrics.started(queuedAt);
                            return EditorWorkMetrics.measure(
                                    EditorWorkMetrics.Phase.BACKGROUND, next);
                        },
                        executor);
    }

    public void poll(Consumer<T> accept, Consumer<Throwable> failed) {
        if (running == null || !running.isDone()) {
            start();
            return;
        }
        var result = running;
        boolean current = runningVersion == version;
        running = null;
        if (current) {
            T value;
            try {
                value = result.join();
            } catch (java.util.concurrent.CompletionException e) {
                failed.accept(e.getCause());
                start();
                return;
            }
            try {
                EditorWorkMetrics.measure(
                        EditorWorkMetrics.Phase.APPLY,
                        () -> {
                            accept.accept(value);
                            return null;
                        });
            } catch (RuntimeException e) {
                failed.accept(e);
            }
        }
        start();
    }
}
