package com.eynny.npships.integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/** Internal native read boundary shared by mapping and the common executor; not an addon API. */
public final class NativeBlockAccess {
    private NativeBlockAccess() {}

    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();

    /** Checks the captured object as well as its UUID; a reused address is never a new owner. */
    public static boolean current(ServerLevel level, ServerSubLevel owner) {
        requireThread(level);
        return !owner.isRemoved() && registered(level, owner);
    }

    private static boolean registered(ServerLevel level, ServerSubLevel owner) {
        var container = SubLevelContainer.getContainer(level);
        return owner.getLevel() == level
                && container != null
                && container.getSubLevel(owner.getUniqueId()) == owner;
    }

    public static LevelChunk chunk(ServerLevel level, ServerSubLevel owner, int x, int z) {
        return chunk(level, owner, x, z, false);
    }

    private static LevelChunk chunk(
            ServerLevel level, ServerSubLevel owner, int x, int z, boolean retiringSource) {
        requireThread(level);
        if (!(retiringSource ? registered(level, owner) : current(level, owner)))
            throw new IllegalStateException(
                    "Captured native placement owner is no longer current: " + owner.getUniqueId());
        var pos = new ChunkPos(x, z);
        if (!owner.getPlot().contains(pos))
            throw new IllegalStateException(
                    "Position is outside captured native placement owner: " + pos);
        var chunk = owner.getPlot().getChunk(owner.getPlot().toLocal(pos));
        if (chunk == null || !chunk.getPos().equals(pos))
            throw new IllegalStateException(
                    "Captured native placement chunk is unavailable: " + pos);
        return chunk;
    }

    /** Existing native BE only. Reading never creates a replacement for failed native insertion. */
    public static BlockEntity existing(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        requireThread(level);
        var be = chunk.getBlockEntities().get(pos);
        if (be == null) return null;
        if (be.isRemoved()
                || be.getLevel() != level
                || !be.getBlockPos().equals(pos)
                || !be.getType().isValid(chunk.getBlockState(pos)))
            throw new IllegalStateException(
                    "Invalid existing native placement block entity: " + pos);
        return be;
    }

    /** Resolve a newly authorized native block interaction; never borrow an evicted plot chunk. */
    public static BlockEntity at(ServerLevel level, BlockPos pos) {
        requireThread(level);
        if (level.isOutsideBuildHeight(pos)) return null;
        var owner = Sable.HELPER.getContaining(level, pos);
        LevelChunk chunk;
        if (owner instanceof ServerSubLevel body)
            chunk = chunk(level, body, pos.getX() >> 4, pos.getZ() >> 4);
        else chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : existing(level, chunk, pos);
    }

    /** Whether this thread already owns a read/transfer window for this exact level. */
    public static boolean active(ServerLevel level) {
        if (ACTIVE_COUNT.get() == 0) return false;
        var scope = ACTIVE.get();
        return scope != null && scope.level == level;
    }

    /**
     * A derived per-placement chunk index; no world scan, native cache change or persistent cache.
     */
    public static Scope open(ServerLevel level, LongFunction<ServerSubLevel> owners) {
        requireThread(level);
        return new Scope(level, Objects.requireNonNull(owners), null, false);
    }

    /**
     * The native transfer can mark its emptied source removed before its final callbacks finish.
     * Only the source captured while live may complete that synchronous cleanup; container/object
     * identity and chunk ownership remain mandatory. Ordinary restoration never gets this
     * allowance.
     */
    public static Scope openTransfer(
            ServerLevel level, LongFunction<ServerSubLevel> owners, ServerSubLevel source) {
        requireThread(level);
        if (source != null && !current(level, source))
            throw new IllegalStateException("Native transfer source is no longer current");
        return new Scope(level, Objects.requireNonNull(owners), source, true);
    }

    /** One authorized synchronous source capture freezes its native owner before provider code. */
    public static Scope capture(ServerLevel level, BlockPos origin) {
        requireThread(level);
        var found = Sable.HELPER.getContaining(level, origin);
        if (!(found instanceof ServerSubLevel body)) return null;
        if (!current(level, body))
            throw new IllegalStateException("Capture native owner is unavailable");
        return open(level, key -> body.getPlot().contains(new ChunkPos(key)) ? body : null);
    }

    public static LevelChunk scopedChunk(ServerLevel level, int x, int z) {
        // No ThreadLocal map allocation on ordinary world reads with no patch callback active.
        if (ACTIVE_COUNT.get() == 0) return null;
        var scope = ACTIVE.get();
        if (scope == null || scope.level != level) return null;
        var owner = scope.owners.apply(ChunkPos.asLong(x, z));
        return owner == null ? null : chunk(level, owner, x, z, owner == scope.retiringSource);
    }

    public static LevelChunk scopedChunk(ServerLevel level, BlockPos pos) {
        var chunk = scopedChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            var scope = ACTIVE.get();
            var owner = scope.owners.apply(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (Sable.HELPER.getContaining(level, pos) != owner)
                throw new IllegalStateException(
                        "Native placement target ownership changed: " + pos);
        }
        return chunk;
    }

    /**
     * Final restoration reads only existing objects. Native transfer keeps its ordinary BE
     * construction/removal lifecycle, including onRemove reads of the previous BE after state swap;
     * its chunks are still qualified by the shared transfer scope.
     */
    public static LevelChunk scopedExistingChunk(ServerLevel level, BlockPos pos) {
        if (ACTIVE_COUNT.get() == 0) return null;
        var scope = ACTIVE.get();
        if (scope == null || scope.level != level || scope.nativeTransfer) return null;
        return scopedChunk(level, pos);
    }

    private static void requireThread(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Native placement reads require the server thread");
    }

    public static final class Scope implements AutoCloseable {
        private ServerLevel level;
        private LongFunction<ServerSubLevel> owners;
        private Scope previous;
        private ServerSubLevel retiringSource;
        private final boolean nativeTransfer;
        private final Thread thread = Thread.currentThread();

        private Scope(
                ServerLevel level,
                LongFunction<ServerSubLevel> owners,
                ServerSubLevel source,
                boolean nativeTransfer) {
            this.nativeTransfer = nativeTransfer;
            this.level = level;
            this.owners = owners;
            retiringSource = source;
            previous = ACTIVE.get();
            ACTIVE.set(this);
            ACTIVE_COUNT.incrementAndGet();
        }

        @Override
        public void close() {
            if (level == null) return;
            if (thread != Thread.currentThread() || ACTIVE.get() != this)
                throw new IllegalStateException(
                        "Native placement read scopes must close on their owner in reverse order");
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            ACTIVE_COUNT.decrementAndGet();
            level = null;
            owners = null;
            retiringSource = null;
            previous = null;
        }
    }
}
