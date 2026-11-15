package com.eynny.npships.integration.create;

import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.ship.DirectSpawnOwnership;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Native bearing collection waits only for an unfinished authored cell that its search reads. */
public final class PendingBearingAssembly {
    private static final ThreadLocal<Search> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private PendingBearingAssembly() {}

    /** Stores only one native position; it never retains a construction, template or world. */
    public interface Waiting {
        BlockPos npships$awaitedCell();

        void npships$awaitedCell(BlockPos position);

        void npships$retryAssembly();
    }

    public static Search begin(Level level, BlockPos controllerPosition) {
        var previous = ACTIVE.get() == 0 ? null : CURRENT.get();
        MechanicalBearingBlockEntity bearing = null;
        Predicate<BlockPos> missing = null;
        if (level instanceof ServerLevel server
                && NativeBlockAccess.at(server, controllerPosition)
                        instanceof MechanicalBearingBlockEntity found) {
            bearing = found;
            missing = DirectSpawnOwnership.unwrittenCells(found);
        }
        if (bearing instanceof Waiting waiting) {
            var dependency = waiting.npships$awaitedCell();
            if (dependency != null && missing != null && missing.test(dependency)) {
                waiting.npships$retryAssembly();
                return Search.DEFERRED;
            }
            waiting.npships$awaitedCell(null);
        }
        // An unrelated nested collection masks its caller's ownership, even in the same level.
        if (missing == null && previous == null) return null;
        return new Search(level, bearing, missing, previous);
    }

    /** Called before a real Level read, only while native source collection is on the stack. */
    public static void reading(Level level, BlockPos position) {
        if (ACTIVE.get() == 0) return;
        var search = CURRENT.get();
        if (search != null
                && search.level == level
                && search.missing != null
                && search.missing.test(position)) {
            search.dependency = position.immutable();
            throw search.wait;
        }
    }

    private static final class Unwritten extends RuntimeException {
        Unwritten() {
            super(null, null, false, false);
        }
    }

    public static final class Search implements AutoCloseable {
        private static final Search DEFERRED = new Search();
        private final Level level;
        private final MechanicalBearingBlockEntity bearing;
        private final Predicate<BlockPos> missing;
        private final Search previous;
        private final Unwritten wait = new Unwritten();
        private boolean closed;
        private BlockPos dependency;

        private Search() {
            level = null;
            bearing = null;
            missing = null;
            previous = null;
        }

        private Search(
                Level level,
                MechanicalBearingBlockEntity bearing,
                Predicate<BlockPos> missing,
                Search previous) {
            this.level = level;
            this.bearing = bearing;
            this.missing = missing;
            this.previous = previous;
            CURRENT.set(this);
            ACTIVE.incrementAndGet();
        }

        public boolean run(BooleanSupplier nativeSearch) {
            if (this == DEFERRED) return false;
            if (closed || CURRENT.get() != this)
                throw new IllegalStateException("Native bearing search scope is not current");
            try {
                return nativeSearch.getAsBoolean();
            } catch (Unwritten pending) {
                if (pending != wait) throw pending;
                // MechanicalBearing.tick clears this flag before assemble(). A deferred search
                // must keep that native retry, without replacing the BE or its live kinetic state.
                if (level instanceof ServerLevel server
                        && bearing != null
                        && NativeBlockAccess.at(server, bearing.getBlockPos()) == bearing) {
                    var waiting = (Waiting) bearing;
                    waiting.npships$awaitedCell(dependency);
                    waiting.npships$retryAssembly();
                }
                return false;
            }
        }

        @Override
        public void close() {
            if (this == DEFERRED) return;
            if (closed) return;
            if (CURRENT.get() != this)
                throw new IllegalStateException("Native bearing searches closed out of order");
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            ACTIVE.decrementAndGet();
        }
    }
}
