package com.eynny.npships.ship;

import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Body-only rollback for one direct attempt. The pilot rejects entity records and handlers before
 * allocation; this ledger provides no entity ownership or entity-graph rollback support.
 */
final class DirectSpawnLedger implements AutoCloseable {
    private final ServerLevel level;
    private final LinkedHashMap<UUID, ServerSubLevel> bodies = new LinkedHashMap<>();
    private boolean committed, closed;

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
        if (committed) throw new IllegalStateException("Direct spawn ledger is committed");
    }

    private static void cleanup(Runnable action, List<Throwable> failures) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            failures.add(failure);
        }
    }

    private void remove(UUID id, ServerSubLevel body, List<Throwable> failures) {
        // Queue ownership is the exact allocated object, even if native removal already ran.
        cleanup(() -> ShipKineticRepair.cancel(level, body), failures);
        var container = SubLevelContainer.getContainer(level);
        if (body.getLevel() != level
                || body.isRemoved()
                || !id.equals(body.getUniqueId())
                || container.getSubLevel(id) != body) return;
        // Native REMOVED releases plot occupancy/storage and performs native plot cleanup.
        // NPShipsLifecycleManager.remove also applies a world-area entity deletion policy.
        cleanup(() -> container.removeSubLevel(body, SubLevelRemovalReason.REMOVED), failures);
        if (container.getSubLevel(id) == null) {
            cleanup(() -> NPShipsLifecycleManager.untrack(level, id), failures);
            cleanup(() -> NaturalSpawnPhysicalChunkManager.untrack(level, id), failures);
        }
    }

    @Override
    public void close() {
        requireThread();
        if (closed) return;
        closed = true;
        var failures = new ArrayList<Throwable>();
        try {
            if (!committed) {
                var allocated = new ArrayList<>(bodies.entrySet());
                for (int i = allocated.size() - 1; i >= 0; i--) {
                    var body = allocated.get(i);
                    cleanup(() -> remove(body.getKey(), body.getValue(), failures), failures);
                }
            }
        } finally {
            bodies.clear();
        }
        if (!failures.isEmpty()) {
            var failure =
                    new IllegalStateException("Direct spawn cleanup failed", failures.getFirst());
            for (int i = 1; i < failures.size(); i++) failure.addSuppressed(failures.get(i));
            throw failure;
        }
    }
}
