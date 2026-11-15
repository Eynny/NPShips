package com.eynny.npships.ship;

import com.eynny.npships.integration.NativeBlockAccess;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Exact native fluid writes originating from this attempt's installed fluid-bearing cells. */
public final class DirectFluidProvenance implements AutoCloseable {
    /** Section-local attachment avoids a global strong registry retaining unloaded plots/worlds. */
    public interface WatchedSection {
        Watch npships$fluidWatch();

        void npships$fluidWatch(Watch watch);
    }

    public static final class Watch {
        final DirectFluidProvenance owner;
        final ServerSubLevel body;
        final LevelChunkSection section;
        final int x, y, z;

        Watch(
                DirectFluidProvenance owner,
                ServerSubLevel body,
                LevelChunkSection section,
                int x,
                int y,
                int z) {
            this.owner = owner;
            this.body = body;
            this.section = section;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Mark {
        final ServerSubLevel body;
        BlockState state;
        boolean authored;
        long mutation;

        Mark(ServerSubLevel body) {
            this.body = body;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Tick> TICK = new ThreadLocal<>();
    private static final ThreadLocal<Write> WRITE = new ThreadLocal<>();
    private static final ThreadLocal<Mutation> MUTATION = new ThreadLocal<>();
    private static final ThreadLocal<Palette> PALETTE = new ThreadLocal<>();
    private final ServerLevel level;
    private final LongConsumer reserve;
    // Invalid marks remain as charged tombstones: repeated flow into one cell cannot repeatedly
    // grow admission charges or accidentally revive provenance after a foreign mutation.
    private Map<BlockPos, Mark> marks = new HashMap<>();
    private ArrayList<Watch> watches = new ArrayList<>();
    private volatile RuntimeException failure;
    private volatile boolean closed;
    private boolean active;
    private int cleanupIndex;
    private long cleanupProgress;

    public DirectFluidProvenance(ServerLevel level, LongConsumer reserve) {
        this.level = Objects.requireNonNull(level);
        this.reserve = Objects.requireNonNull(reserve);
    }

    public void check() {
        if (failure != null) throw failure;
    }

    /** Called after the real authored cell and its initial native payload/hooks were installed. */
    public synchronized void authored(ServerSubLevel body, BlockPos pos, BlockState actual) {
        check();
        if (closed) throw new IllegalStateException("Fluid provenance is closed");
        var previous = marks.get(pos);
        if (actual.getFluidState().isEmpty() || level.getBlockState(pos) != actual) {
            if (previous != null) previous.state = null;
            return;
        }
        if (!NativeBlockAccess.current(level, body))
            throw new IllegalStateException("Authored fluid body disappeared");
        watch(body, pos);
        var mark = mark(body, pos);
        mark.state = actual;
        mark.authored = true;
        if (!active) {
            active = true;
            ACTIVE.incrementAndGet();
        }
    }

    /** Current palette identity must still be exactly the state from the proved native write. */
    public synchronized boolean mayReplace(ServerSubLevel body, BlockPos pos, BlockState actual) {
        check();
        if (closed || !NativeBlockAccess.current(level, body)) return false;
        var mark = marks.get(pos);
        return mark != null
                && mark.body == body
                && !mark.authored
                && mark.state == actual
                && pureFluid(actual);
    }

    private static boolean pureFluid(BlockState state) {
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.createLegacyBlock() == state;
    }

    private Mark mark(ServerSubLevel body, BlockPos pos) {
        var mark = marks.get(pos);
        if (mark != null) {
            if (mark.body != body) throw new IllegalStateException("Fluid plot owner changed");
            return mark;
        }
        reserve.accept(16L * Long.BYTES);
        mark = new Mark(body);
        marks.put(pos.immutable(), mark);
        return mark;
    }

    private Watch watch(ServerSubLevel body, BlockPos pos) {
        var chunk = NativeBlockAccess.chunk(level, body, pos.getX() >> 4, pos.getZ() >> 4);
        int index = chunk.getSectionIndex(pos.getY());
        var section = chunk.getSection(index);
        var attached = (WatchedSection) section;
        var existing = attached.npships$fluidWatch();
        if (existing != null) {
            if (existing.owner != this || existing.body != body)
                throw new IllegalStateException("Fluid section has another exact owner");
            return existing;
        }
        reserve.accept(12L * Long.BYTES);
        var watch =
                new Watch(
                        this,
                        body,
                        section,
                        chunk.getPos().getMinBlockX(),
                        chunk.getSectionYFromSectionIndex(index) << 4,
                        chunk.getPos().getMinBlockZ());
        watches.add(watch);
        attached.npships$fluidWatch(watch);
        return watch;
    }

    /**
     * Scope only the installed FlowingFluid tick; nested foreign tick origins do not inherit it.
     */
    public static Tick tick(Level level, BlockPos pos, FluidState state) {
        if (ACTIVE.get() == 0) return null;
        var previous = TICK.get();
        Tick current = null;
        if (level instanceof ServerLevel server
                && Sable.HELPER.getContaining(server, pos) instanceof ServerSubLevel body) {
            var owner = DirectSpawnOwnership.pendingOwner(body);
            var provenance = owner == null || owner.cancelling() ? null : owner.fluidProvenance();
            if (provenance != null) current = provenance.beginTick(body, pos, state, previous);
        }
        // Even an unproved nested tick masks the enclosing authority until it returns.
        if (current == null && previous == null) return null;
        if (current == null) current = new Tick(null, null, null, previous);
        TICK.set(current);
        return current;
    }

    private synchronized Tick beginTick(
            ServerSubLevel body, BlockPos pos, FluidState state, Tick previous) {
        if (closed || failure != null) return null;
        var mark = marks.get(pos);
        if (mark == null || mark.body != body || mark.state == null) return null;
        var actual = level.getBlockState(pos);
        if (actual != mark.state || !actual.getFluidState().equals(state)) {
            mark.state = null;
            return null;
        }
        return new Tick(this, body, state.getType(), previous);
    }

    public static final class Tick implements AutoCloseable {
        final DirectFluidProvenance owner;
        final ServerSubLevel body;
        final Fluid fluid;
        final Tick previous;

        Tick(DirectFluidProvenance owner, ServerSubLevel body, Fluid fluid, Tick previous) {
            this.owner = owner;
            this.body = body;
            this.fluid = fluid;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) TICK.remove();
            else TICK.set(previous);
        }
    }

    /** Called around only native FlowingFluid's exact setBlock invocation, not its callbacks. */
    public static Write write(LevelAccessor level, BlockPos pos, BlockState state) {
        if (ACTIVE.get() == 0) return null;
        var previous = WRITE.get();
        Write current = null;
        var tick = TICK.get();
        if (tick != null && tick.owner != null && tick.owner.level == level)
            current = tick.owner.beginWrite(tick, pos, state, previous);
        if (current == null && previous == null) return null;
        if (current == null) current = new Write(null, null, null, null, previous);
        WRITE.set(current);
        return current;
    }

    private synchronized Write beginWrite(
            Tick tick, BlockPos pos, BlockState state, Write previous) {
        if (closed
                || failure != null
                || !pureFluid(state)
                || !tick.fluid.isSame(state.getFluidState().getType())) return null;
        if (Sable.HELPER.getContaining(level, pos) != tick.body
                || !NativeBlockAccess.current(level, tick.body)) return null;
        // Do not load/create a chunk merely to account for a native write. A target outside the
        // current exact plot's existing storage receives no overwrite authority.
        var chunk = new ChunkPos(pos);
        if (!tick.body.getPlot().contains(chunk)
                || tick.body.getPlot().getChunk(tick.body.getPlot().toLocal(chunk)) == null)
            return null;
        try {
            watch(tick.body, pos);
            var mark = mark(tick.body, pos);
            return new Write(this, mark, pos.immutable(), state, previous);
        } catch (RuntimeException error) {
            failure = error;
            return null; // Native fluid work remains live; the construction observes check().
        }
    }

    public static final class Write implements AutoCloseable {
        final DirectFluidProvenance owner;
        final Mark mark;
        final BlockPos pos;
        final BlockState state;
        final Write previous;
        final Mutation parentMutation = MUTATION.get();
        Mutation mutation;
        boolean consumed;
        long candidateVersion = -1;
        boolean returned;

        Write(
                DirectFluidProvenance owner,
                Mark mark,
                BlockPos pos,
                BlockState state,
                Write previous) {
            this.owner = owner;
            this.mark = mark;
            this.pos = pos;
            this.state = state;
            this.previous = previous;
        }

        /** A candidate is published only after the exact native call returns successfully. */
        public void completed(boolean result) {
            returned = true;
            if (owner == null) return;
            synchronized (owner) {
                if (!owner.closed
                        && result
                        && mutation != null
                        && mutation.succeeded
                        && candidateVersion >= 0
                        && mark.mutation == candidateVersion) {
                    mark.state = state;
                    mark.authored = false;
                } else {
                    mark.state = null;
                }
            }
        }

        @Override
        public void close() {
            if (!returned) completed(false);
            if (previous == null) WRITE.remove();
            else WRITE.set(previous);
        }
    }

    /** Bind the native four-argument Level.setBlock invocation; nested mutations mask it. */
    public static Mutation mutation(Level level, BlockPos pos, BlockState state) {
        if (ACTIVE.get() == 0) return null;
        var write = WRITE.get();
        var previous = MUTATION.get();
        if (write == null && previous == null) return null;
        boolean binds =
                write != null
                        && write.owner != null
                        && write.mutation == null
                        && write.parentMutation == previous
                        && write.owner.level == level
                        && write.pos.equals(pos)
                        && write.state == state;
        var current = new Mutation(binds ? write : null, previous);
        if (binds) write.mutation = current;
        MUTATION.set(current);
        return current;
    }

    public static final class Mutation implements AutoCloseable {
        final Write write;
        final Mutation previous;
        boolean succeeded;

        Mutation(Write write, Mutation previous) {
            this.write = write;
            this.previous = previous;
        }

        public void completed(boolean result) {
            succeeded = result;
        }

        @Override
        public void close() {
            if (previous == null) MUTATION.remove();
            else MUTATION.set(previous);
        }
    }

    /** Only the native chunk's immediate palette call can consume the bound write. */
    public static Palette palette(
            LevelChunkSection section, int x, int y, int z, BlockState state) {
        if (ACTIVE.get() == 0) return null;
        var mutation = MUTATION.get();
        var previous = PALETTE.get();
        Write write = mutation == null ? null : mutation.write;
        var watch = ((WatchedSection) section).npships$fluidWatch();
        boolean binds =
                write != null
                        && WRITE.get() == write
                        && !write.consumed
                        && watch != null
                        && watch.owner == write.owner
                        && write.state == state
                        && write.pos.getX() == watch.x + x
                        && write.pos.getY() == watch.y + y
                        && write.pos.getZ() == watch.z + z;
        if (!binds && previous == null) return null;
        var current = new Palette(binds ? write : null, previous);
        PALETTE.set(current);
        return current;
    }

    public static final class Palette implements AutoCloseable {
        final Write write;
        final Palette previous;

        Palette(Write write, Palette previous) {
            this.write = write;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) PALETTE.remove();
            else PALETTE.set(previous);
        }
    }

    /** Every palette mutation invalidates the prior proof unless it consumes this exact write. */
    public static void changed(
            LevelChunkSection section, int x, int y, int z, BlockState before, BlockState after) {
        var watch = ((WatchedSection) section).npships$fluidWatch();
        if (watch != null)
            watch.owner.changed(
                    watch, new BlockPos(watch.x + x, watch.y + y, watch.z + z), before, after);
    }

    private synchronized void changed(
            Watch watch, BlockPos pos, BlockState before, BlockState after) {
        if (closed) return;
        var mark = marks.get(pos);
        if (mark == null || mark.body != watch.body) return;
        var write = WRITE.get();
        var palette = PALETTE.get();
        boolean accepted =
                write != null
                        && write.owner == this
                        && write.mark == mark
                        && !write.consumed
                        && palette != null
                        && palette.write == write
                        && MUTATION.get() == write.mutation
                        && write.pos.equals(pos)
                        && write.state == after
                        && before != after
                        && (before.isAir() || mark.state == before && pureFluid(before));
        mark.mutation++;
        if (write != null && write.owner == this && write.pos.equals(pos)) {
            write.consumed = true;
            if (accepted) write.candidateVersion = mark.mutation;
        }
        // A later nested/foreign write changes the version and invalidates the provisional proof.
        // Even a matching palette no-op is not a successful native fluid mutation.
        mark.state = null;
    }

    /** Drop one section attachment per grant; closed watches ignore notifications immediately. */
    public synchronized boolean stepCleanup(BooleanSupplier allowed) {
        closed = true;
        if (active) {
            active = false;
            ACTIVE.decrementAndGet();
        }
        if (watches == null) return true;
        while (cleanupIndex < watches.size()) {
            if (!allowed.getAsBoolean()) return false;
            var watch = watches.get(cleanupIndex++);
            var section = (WatchedSection) watch.section;
            if (section.npships$fluidWatch() == watch) section.npships$fluidWatch(null);
            cleanupProgress++;
        }
        if (!allowed.getAsBoolean()) return false;
        marks = null;
        watches = null;
        cleanupProgress++;
        return true;
    }

    public long cleanupProgress() {
        return cleanupProgress;
    }

    /** Synchronous drain for explicitly synchronous callers/fixtures only. */
    @Override
    public void close() {
        while (!stepCleanup(() -> true)) {}
    }
}
