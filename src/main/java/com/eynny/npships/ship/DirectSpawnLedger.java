package com.eynny.npships.ship;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Exact native-body ownership. The attempt owns entity rollback and drives this cleanup cursor. */
final class DirectSpawnLedger implements AutoCloseable {
    private final ServerLevel level;
    private final LinkedHashMap<UUID, ServerSubLevel> bodies = new LinkedHashMap<>();
    private boolean committed, cleaning, stepping, closed;

    DirectSpawnLedger(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
        requireThread();
    }

    /** The caller registers only its new allocation, immediately before later callbacks. */
    void allocated(ServerSubLevel body) {
        requireMutable();
        Objects.requireNonNull(body);
        var id = body.getUniqueId();
        var container = SubLevelContainer.getContainer(level);
        if (body.getLevel() != level || body.isRemoved() || container.getSubLevel(id) != body)
            throw new IllegalArgumentException("Direct allocation is not the current body");
        var previous = bodies.putIfAbsent(id, body);
        if (previous != null && previous != body)
            throw new IllegalArgumentException("Direct allocation UUID changed owner: " + id);
    }

    List<UUID> ids() {
        requireOpen();
        return List.copyOf(bodies.keySet());
    }

    void commit() {
        requireMutable();
        committed = true;
    }

    private void requireThread() {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Direct spawn ownership requires the server thread");
    }

    private void requireOpen() {
        requireThread();
        if (closed) throw new IllegalStateException("Direct spawn ledger is closed");
    }

    private void requireMutable() {
        requireOpen();
        if (committed || cleaning)
            throw new IllegalStateException("Direct spawn ledger is committed or cleaning up");
    }

    void beginCleanup() {
        requireThread();
        cleaning = true;
    }

    boolean cleanupFinished() {
        requireThread();
        return closed;
    }

    private static final class Failures {
        private IllegalStateException failure;

        void run(Runnable action) {
            try {
                action.run();
            } catch (Throwable error) {
                if (error instanceof VirtualMachineError fatal) throw fatal;
                if (error instanceof ThreadDeath fatal) throw fatal;
                if (failure == null)
                    failure = new IllegalStateException("Direct spawn cleanup failed", error);
                else failure.addSuppressed(error);
            }
        }
    }

    /**
     * Attempts one native body only. A surviving exact owner remains here after failure; rotating
     * it behind the other entries lets the sole attempt owner clean them before retrying it. The
     * caller must check its shared budget and yield after an exception.
     */
    boolean stepCleanup(Consumer<ServerSubLevel> beforeRemoval) {
        requireThread();
        if (closed) return true;
        if (stepping) throw new IllegalStateException("Reentrant direct cleanup");
        beginCleanup();
        var entry = bodies.lastEntry();
        if (entry == null) {
            closed = true;
            return true;
        }
        if (committed) {
            bodies.pollLastEntry();
            closed = bodies.isEmpty();
            return closed;
        }
        var id = entry.getKey();
        var body = entry.getValue();
        var failures = new Failures();
        stepping = true;
        try {
            // Cancel the exact delayed-work owner even if native removal already happened.
            failures.run(() -> ShipKineticRepair.cancel(level, body));
            failures.run(() -> beforeRemoval.accept(body));
            failures.run(
                    () -> {
                        var container = SubLevelContainer.getContainer(level);
                        if (body.getLevel() != level || !id.equals(body.getUniqueId()))
                            throw new IllegalStateException(
                                    "Direct cleanup identity changed owner: " + id);
                        if (!body.isRemoved() && container.getSubLevel(id) == body)
                            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                        // A replacement UUID object is not owned by this ledger.
                        if (!body.isRemoved() && container.getSubLevel(id) == body)
                            throw new IllegalStateException(
                                    "Native cleanup retained the owned body: " + id);
                        if (container.getSubLevel(id) == null) {
                            failures.run(() -> NPShipsLifecycleManager.untrack(level, id));
                            failures.run(() -> NaturalSpawnPhysicalChunkManager.untrack(level, id));
                        }
                        bodies.remove(id);
                    });
            if (bodies.containsKey(id)) bodies.putFirst(id, body);
            closed = bodies.isEmpty();
        } finally {
            stepping = false;
        }
        if (failures.failure != null) throw failures.failure;
        return closed;
    }

    boolean stepCleanup() {
        return stepCleanup(ignored -> {});
    }

    /** Explicit synchronous drain for shutdown and isolated fixtures; runtime uses stepCleanup. */
    @Override
    public void close() {
        while (!stepCleanup()) {}
    }
}
