package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.PatchedTemplate;
import com.eynny.npships.integration.NativeBlockAccess;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/** Synchronous allocation authority. The owner survives yields; these scopes never do. */
public final class DirectSpawnOwnership implements AutoCloseable {
    interface Owner {
        boolean owns(UUID id);

        void reserve(UUID id);

        void allocated(ServerSubLevel body);

        default DirectFluidProvenance fluidProvenance() {
            return null;
        }

        default boolean cancelling() {
            return false;
        }

        default AutoCloseable nativeTickContext() {
            return null;
        }

        default void nativeTickChildReady(ServerSubLevel body) {}

        default void nativeCallbackChildReady(ServerSubLevel body) {}

        default boolean mapsNativeCells() {
            return false;
        }

        default boolean awaitingNativeCell(ServerSubLevel body, BlockPos position) {
            return false;
        }

        default void nativeCellTransferred(
                ServerSubLevel source, BlockPos from, ServerSubLevel target, BlockPos to) {}

        default void nativeTickFailed(Throwable failure) {}
    }

    // Neither value retains a native body, a construction, or a world across integrated-server
    // stops.
    // Explicit per-body retirement avoids relying on a weak-map reference-queue sweep in a tick.
    private record Registration(
            java.lang.ref.WeakReference<ServerSubLevel> body,
            java.lang.ref.WeakReference<Owner> owner) {}

    private static final java.util.Map<UUID, Registration> REGISTERED = new java.util.HashMap<>();

    private static synchronized void remember(ServerSubLevel body, Owner owner) {
        var old = REGISTERED.get(body.getUniqueId());
        if (old != null && old.body().get() != null && old.body().get() != body)
            throw new IllegalStateException("Native ownership UUID collision");
        REGISTERED.put(
                body.getUniqueId(),
                new Registration(
                        new java.lang.ref.WeakReference<>(body),
                        new java.lang.ref.WeakReference<>(owner)));
    }

    static synchronized void forget(UUID id, Owner owner) {
        var registered = REGISTERED.get(id);
        if (registered != null && registered.owner().get() == owner) REGISTERED.remove(id);
    }

    private static synchronized Owner exactOwner(ServerSubLevel parent) {
        var registered = REGISTERED.get(parent.getUniqueId());
        if (registered == null) return null;
        var body = registered.body().get();
        var owner = registered.owner().get();
        if (body == null || owner == null) {
            REGISTERED.remove(parent.getUniqueId());
            return null;
        }
        if (body != parent || !owner.owns(parent.getUniqueId())) return null;
        var level = parent.getLevel();
        return NativeBlockAccess.current(level, parent)
                        && DirectSpawnJournal.pending(level, parent.getUniqueId())
                ? owner
                : null;
    }

    /** Sable calls its removal observers while this exact native object is still current. */
    public static void removedPendingBody(ServerSubLevel body, SubLevelRemovalReason reason) {
        var owner = exactOwner(body);
        if (owner == null || owner.cancelling()) return;
        // Earlier native transfers retain their existing validation. Only a RELEASE readiness
        // promise can become stale here; published bodies and cancellation never revoke it.
        if (DirectSpawnJournal.invalidateReady(body.getLevel(), body.getUniqueId()))
            owner.nativeTickFailed(
                    new IllegalStateException(
                            "Ready construction body was removed before publication: "
                                    + body.getUniqueId()
                                    + " ("
                                    + reason
                                    + ")"));
    }

    static Owner pendingOwner(ServerSubLevel body) {
        return exactOwner(body);
    }

    /** Callback-local planned-cell query; it never infers ownership from a later UUID lookup. */
    public static java.util.function.Predicate<BlockPos> unwrittenCells(BlockEntity controller) {
        synchronized (DirectSpawnOwnership.class) {
            if (REGISTERED.isEmpty()) return null;
        }
        if (!(controller.getLevel() instanceof ServerLevel level)
                || !(Sable.HELPER.getContaining(controller) instanceof ServerSubLevel body))
            return null;
        var owner = exactOwner(body);
        if (owner == null
                || owner.cancelling()
                || NativeBlockAccess.at(level, controller.getBlockPos()) != controller) return null;
        return position ->
                exactOwner(body) == owner
                        && !owner.cancelling()
                        && owner.awaitingNativeCell(body, position);
    }

    /** Exact current pending native object, including the owner's cancellation boundary. */
    public static boolean pendingBody(ServerSubLevel body) {
        return exactOwner(body) != null;
    }

    public static boolean activePendingBody(ServerSubLevel body) {
        var owner = exactOwner(body);
        return owner != null && !owner.cancelling();
    }

    /** Only the exact current pending native object may defer a structural split commit. */
    public static boolean pendingHeatMapSplit(ServerSubLevel body) {
        return pendingBody(body);
    }

    /** Active construction is still adding cells; cancellation never restarts native queues. */
    public static boolean deferHeatMapSplit(ServerSubLevel body) {
        return activePendingBody(body);
    }

    /** Verified provider callback, including a mask for unrelated reentrant native work. */
    public static NativeSplit nativeCallback(BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level)) return null;
        var parent = Sable.HELPER.getContaining(entity);
        Owner owner =
                parent instanceof ServerSubLevel body
                                && NativeBlockAccess.at(level, entity.getBlockPos()) == entity
                        ? exactOwner(body)
                        : null;
        if (owner == null && CURRENT.get() == null) return null;
        if (owner != null && owner.cancelling())
            return new NativeSplit(owner, null, null, true, null, false);
        var scope = new DirectSpawnOwnership(level, owner);
        scope.callback = true;
        var suspended = ALLOCATION.get();
        ALLOCATION.remove();
        try {
            return new NativeSplit(
                    owner,
                    scope,
                    owner == null ? PatchedTemplate.observe(null) : owner.nativeTickContext(),
                    false,
                    suspended,
                    true);
        } catch (Throwable failure) {
            try {
                scope.close();
            } finally {
                restoreAllocation(suspended);
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (owner == null)
                throw new IllegalStateException("Foreign callback masking failed", failure);
            owner.nativeTickFailed(failure);
            return new NativeSplit(owner, null, null, true, null, false);
        }
    }

    public static boolean mapsNativeCells(ServerLevel level) {
        var scope = CURRENT.get();
        return scope != null
                && scope.level == level
                && scope.owner != null
                && scope.owner.mapsNativeCells();
    }

    public static void nativeCellTransferred(
            ServerLevel level,
            dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform transform,
            BlockPos to) {
        if (!mapsNativeCells(level)) return;
        try {
            var offset = transform.apply(BlockPos.ZERO);
            if (transform.getLevel() != level
                    || !transform.apply(new BlockPos(1, 0, 0)).equals(offset.east())
                    || !transform.apply(new BlockPos(0, 1, 0)).equals(offset.above())
                    || !transform.apply(new BlockPos(0, 0, 1)).equals(offset.south()))
                throw new IllegalStateException(
                        "Native transfer changed direct coordinate orientation or level");
            nativeCellTransferred(level, to.subtract(offset), to);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            CURRENT.get().owner.nativeTickFailed(failure);
        }
    }

    /** A caught native load error must still abort the exact pending target owner. */
    public static void nativeTransferFailed(BlockEntity target, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (!(target.getLevel() instanceof ServerLevel level)
                || !(Sable.HELPER.getContaining(target) instanceof ServerSubLevel body)) return;
        var owner = exactOwner(body);
        if (owner == null) return;
        var pos = target.getBlockPos();
        // Unlike a normal read, a failed load may leave this exact object invalid/removed. The
        // native chunk's retained identity still proves which owner must cancel, without giving
        // a detached or stale object at the same coordinate authority over that owner.
        var chunk = NativeBlockAccess.chunk(level, body, pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk.getBlockEntities().get(pos) == target) owner.nativeTickFailed(failure);
    }

    /** One native copied cell, never a scan over source membership or all tracked positions. */
    public static void nativeCellTransferred(ServerLevel level, BlockPos from, BlockPos to) {
        var scope = CURRENT.get();
        if (!mapsNativeCells(level)) return;
        var source = Sable.HELPER.getContaining(level, from);
        var target = Sable.HELPER.getContaining(level, to);
        if (!(source instanceof ServerSubLevel oldBody) || !scope.owner.owns(oldBody.getUniqueId()))
            return;
        try {
            if (!(target instanceof ServerSubLevel newBody)
                    || !scope.owner.owns(newBody.getUniqueId()))
                throw new IllegalStateException("Native transfer target escaped exact ownership");
            scope.owner.nativeCellTransferred(oldBody, from, newBody, to);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            scope.owner.nativeTickFailed(failure);
        }
    }

    /**
     * Actual native parent identity, never its bounds, supplies authority to a later native tick.
     */
    public static NativeSplit nativeSplit(ServerSubLevel parent) {
        var owner = exactOwner(parent);
        if (owner == null) return null;
        if (owner.cancelling()) return new NativeSplit(owner, null, null, true, null, false);
        var current = CURRENT.get();
        if (current != null && current.owner == owner && current.level == parent.getLevel())
            return null; // Already part of this owner's normal synchronous placement callback.
        var scope = new DirectSpawnOwnership(parent.getLevel(), owner);
        scope.nativeTick = true;
        // Only the proven native parent may suspend an unrelated allocation token. Restore it
        // even when native observers or context teardown fail; never clear arbitrary callbacks.
        var suspendedAllocation = ALLOCATION.get();
        ALLOCATION.remove();
        try {
            return new NativeSplit(
                    owner, scope, owner.nativeTickContext(), false, suspendedAllocation, true);
        } catch (Throwable failure) {
            try {
                scope.close();
            } finally {
                restoreAllocation(suspendedAllocation);
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            owner.nativeTickFailed(failure);
            return new NativeSplit(owner, null, null, true, null, false);
        }
    }

    /**
     * Called after the native transfer/mass initialization and before its caller regains control.
     */
    public static void nativeAssemblyFinished(ServerLevel level, ServerSubLevel child) {
        var scope = CURRENT.get();
        if (scope == null
                || !scope.nativeTick
                || scope.owner == null
                || scope.level != level
                || !scope.owner.owns(child.getUniqueId())) return;
        try {
            scope.owner.nativeTickChildReady(child);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            scope.owner.nativeTickFailed(failure);
        }
    }

    private static void restoreAllocation(Allocation suspended) {
        if (suspended == null) ALLOCATION.remove();
        else ALLOCATION.set(suspended);
    }

    public static final class NativeSplit implements AutoCloseable {
        private final Owner owner;
        private final DirectSpawnOwnership scope;
        private final AutoCloseable context;
        private final boolean suppressed;
        private final Allocation suspendedAllocation;
        private final boolean allocationSuspended;
        private boolean closed;

        private NativeSplit(
                Owner owner,
                DirectSpawnOwnership scope,
                AutoCloseable context,
                boolean suppressed,
                Allocation suspendedAllocation,
                boolean allocationSuspended) {
            this.owner = owner;
            this.scope = scope;
            this.context = context;
            this.suppressed = suppressed;
            this.suspendedAllocation = suspendedAllocation;
            this.allocationSuspended = allocationSuspended;
        }

        public boolean owned() {
            return owner != null;
        }

        public void completed(UUID attached) {
            if (scope == null || !scope.callback || owner == null || scope.callbackChild == null)
                return;
            try {
                if (!scope.callbackChild.getUniqueId().equals(attached))
                    throw new IllegalStateException(
                            "Native provider did not retain its allocated child");
                owner.nativeCallbackChildReady(scope.callbackChild);
            } catch (Throwable failure) {
                failed(failure);
            }
        }

        public boolean suppressed() {
            return suppressed;
        }

        public void failed(Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            owner.nativeTickFailed(failure);
        }

        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (context != null) context.close();
            } catch (Throwable failure) {
                failed(failure);
            } finally {
                try {
                    if (scope != null) scope.close();
                } finally {
                    if (allocationSuspended) restoreAllocation(suspendedAllocation);
                }
            }
        }
    }

    private static final ThreadLocal<DirectSpawnOwnership> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Allocation> ALLOCATION = new ThreadLocal<>();
    private final ServerLevel level;
    private final Owner owner;
    private final DirectSpawnOwnership previous;
    private boolean closed, nativeTick, callback;
    private ServerSubLevel callbackChild;

    private DirectSpawnOwnership(ServerLevel level, Owner owner) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Allocation authority requires server thread");
        this.level = level;
        this.owner = owner;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static DirectSpawnOwnership open(ServerLevel level, Owner owner) {
        return new DirectSpawnOwnership(level, owner);
    }

    static Allocation root(UUID id) {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No construction allocation owner");
        return new Allocation(scope, id);
    }

    /** Only an actual native transfer out of our own plot can create an owned child. */
    public static Allocation nativeChild(ServerLevel level, BlockPos anchor) {
        var scope = CURRENT.get();
        if (scope == null
                || scope.owner == null
                || scope.level != level
                || ALLOCATION.get() != null) return null;
        var parent = Sable.HELPER.getContaining(level, anchor);
        return parent != null && scope.owner.owns(parent.getUniqueId())
                ? new Allocation(scope, null)
                : null;
    }

    public static void beforeAllocation(SubLevelContainer container, UUID id) {
        var allocation = ALLOCATION.get();
        if (allocation == null
                || allocation.scope.level != container.getLevel()
                || allocation.reserved != null) return;
        if (allocation.expected != null && !allocation.expected.equals(id))
            throw new IllegalStateException("Native allocation changed the reserved UUID");
        allocation.scope.owner.reserve(id);
        allocation.reserved = id;
    }

    /** Before native observers can throw or allocate unrelated bodies. */
    public static void registered(SubLevelContainer container, UUID id) {
        var allocation = ALLOCATION.get();
        if (allocation == null
                || allocation.scope.level != container.getLevel()
                || !id.equals(allocation.reserved)
                || allocation.captured) return;
        var nativeBody = container.getSubLevel(id);
        if (!(nativeBody instanceof ServerSubLevel body))
            throw new IllegalStateException("Allocated native body is missing");
        try {
            allocation.scope.owner.allocated(body);
        } finally {
            // Keep exact cleanup authority even if the owner's post-capture bookkeeping throws.
            if (allocation.scope.owner.owns(id)) {
                remember(body, allocation.scope.owner);
                if (allocation.scope.callback) {
                    if (allocation.scope.callbackChild != null
                            && allocation.scope.callbackChild != body)
                        allocation.scope.owner.nativeTickFailed(
                                new IllegalStateException(
                                        "Native provider allocated multiple unexpected direct children"));
                    else allocation.scope.callbackChild = body;
                }
            }
        }
        allocation.captured = true;
    }

    public static final class Allocation implements AutoCloseable {
        private final DirectSpawnOwnership scope;
        private final UUID expected;
        private final Allocation previous;
        private UUID reserved;
        private boolean captured, closed;

        private Allocation(DirectSpawnOwnership scope, UUID expected) {
            this.scope = scope;
            this.expected = expected;
            previous = ALLOCATION.get();
            ALLOCATION.set(this);
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (ALLOCATION.get() != this)
                throw new IllegalStateException("Unbalanced allocation authority");
            if (previous == null) ALLOCATION.remove();
            else ALLOCATION.set(previous);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (CURRENT.get() != this)
            throw new IllegalStateException("Unbalanced construction authority");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
