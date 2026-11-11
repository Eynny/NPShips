package com.eynny.npships.lifecycle;

import com.eynny.npships.api.events.NPShipsLifecycleEvent.TrackingPhase;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.TrackingState;
import com.eynny.npships.integration.events.NPShipsEventBridge;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Global NPShips lifecycle observer.
 *
 * <p>Responsibilities:
 *
 * <p>- tracking / untracking UUIDs; - detecting Sable load / unload / removal; - handling unload
 * deadlines; - inheriting tracking across splits; - manually removing loaded SubLevels.
 *
 * <p>Committed Simulated merges are observed by NPShipsMergeMixin.
 */
public final class NPShipsLifecycleManager implements SubLevelObserver {

    /** Five minutes in milliseconds. */
    public static final long UNLOAD_TIMEOUT_MS = 5L * 60L * 1000L;

    private static final NPShipsLifecycleManager INSTANCE = new NPShipsLifecycleManager();

    /**
     * Server dimensions where the observer has already been attached.
     *
     * <p>Weak references prevent the manager from keeping dimensions alive by itself.
     */
    private final Set<ServerLevel> attachedLevels = Collections.newSetFromMap(new WeakHashMap<>());

    private NPShipsLifecycleManager() {}

    public static void attach(final ServerLevel level) {
        INSTANCE.attachInternal(level);
    }

    private synchronized void attachInternal(final ServerLevel level) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) attach(container);
    }

    /** The container may not yet be published on the level during initialize(). */
    public static void attach(final ServerSubLevelContainer container) {
        INSTANCE.attachContainer(container);
    }

    private synchronized void attachContainer(final ServerSubLevelContainer container) {
        final ServerLevel level = container.getLevel();
        if (this.attachedLevels.contains(level)) {
            return;
        }

        container.addObserver(this);

        this.attachedLevels.add(level);
    }

    /** Once after Sable restores its bodies, never a per-tick scan or a forced unload. */
    public static void reconcile(final ServerSubLevelContainer container) {
        final ServerLevel level = container.getLevel();
        final NPShipsSavedData data = NPShipsSavedData.peek(level);
        if (data == null) return;
        for (var entry : data.getTrackedShips().entrySet()) {
            if (container.getSubLevel(entry.getKey()) instanceof ServerSubLevel body
                    && !body.isRemoved()) {
                INSTANCE.markLoadedInternal(level, entry.getKey());
            } else if (entry.getValue() < 0L) {
                // A saved 'loaded' flag is not evidence that a body survived a restart.
                // Give it a fresh grace period; never extend an existing unload deadline.
                INSTANCE.markUnloadedInternal(level, entry.getKey());
            }
        }
    }

    /**
     * Adds a UUID to NPShips tracking.
     *
     * <p>If the corresponding SubLevel is already loaded, it is immediately marked as loaded.
     */
    public static void track(final ServerLevel level, final UUID uuid) {
        INSTANCE.trackInternal(level, uuid);
    }

    private void trackInternal(final ServerLevel level, final UUID uuid) {
        this.attachInternal(level);

        final NPShipsSavedData data = NPShipsSavedData.get(level);

        boolean wasTracked = data.isTracked(uuid);
        data.track(uuid);
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.getSubLevel(uuid) instanceof ServerSubLevel)
            data.markLoaded(uuid);
        if (!wasTracked) notifyTracking(level, uuid, TrackingPhase.TRACKED, "TRACKING_ADDED");
    }

    public static boolean isTracked(final ServerLevel level, final UUID uuid) {
        return NPShipsSavedData.get(level).isTracked(uuid);
    }

    /** Both inputs must be ours; a foreign component makes the merged body player-owned. */
    public static void finishMerge(ServerLevel level, UUID source, UUID target, boolean keep) {
        var data = NPShipsSavedData.get(level);
        boolean relevant = data.isTracked(source) || data.isTracked(target);
        String label = data.source(source);
        boolean fragment = data.fragment(source) || data.fragment(target);
        data.remove(source);
        if (keep) {
            data.markLoaded(target);
            data.fragment(target, fragment);
        } else data.remove(target);
        if (relevant)
            NPShipsEventBridge.tracking(
                    level,
                    TrackingPhase.MERGE_TRANSFER_COMMITTED,
                    source,
                    target,
                    TrackingState.UNTRACKED,
                    -1L,
                    keep,
                    label,
                    "NATIVE_BLOCK_TRANSFER_RETURNED");
    }

    /**
     * Stops tracking a UUID.
     *
     * <p>Does not remove the actual Sable SubLevel.
     */
    public static void untrack(final ServerLevel level, final UUID uuid) {
        INSTANCE.untrackInternal(level, uuid);
    }

    private void untrackInternal(final ServerLevel level, final UUID uuid) {
        untrackInternal(level, uuid, "TRACKING_RELEASED");
    }

    private void untrackInternal(ServerLevel level, UUID uuid, String cause) {
        var data = NPShipsSavedData.get(level);
        if (!data.isTracked(uuid)) return;
        String source = data.source(uuid);
        data.remove(uuid);
        com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager.untrack(level, uuid);
        NPShipsEventBridge.tracking(
                level,
                TrackingPhase.UNTRACKED,
                uuid,
                null,
                TrackingState.UNTRACKED,
                -1L,
                false,
                source,
                cause);
    }

    private static void notifyTracking(
            ServerLevel level, UUID uuid, TrackingPhase phase, String cause) {
        var data = NPShipsSavedData.get(level);
        long deadline = data.getUnloadDeadline(uuid);
        NPShipsEventBridge.tracking(
                level,
                phase,
                uuid,
                null,
                deadline < 0 ? TrackingState.TRACKED_LOADED : TrackingState.TRACKED_UNLOADED,
                deadline,
                false,
                data.source(uuid),
                cause);
    }

    /** Submission observation only; native void storage provides no durable acknowledgement. */
    public static void holdingDeletionSubmitted(
            ServerLevel level, UUID uuid, String source, long deadline) {
        NPShipsEventBridge.tracking(
                level,
                TrackingPhase.HOLDING_DELETE_SUBMITTED,
                uuid,
                null,
                TrackingState.UNTRACKED,
                deadline,
                false,
                source,
                "NATIVE_VOID_DELETE_SUBMITTED_NOT_ACKNOWLEDGED");
    }

    /** Completely forgets a UUID from NPShips tracking. */
    public static void forget(final ServerLevel level, final UUID uuid) {
        INSTANCE.untrackInternal(level, uuid, "TRACKING_FORGOTTEN");
    }

    /**
     * Marks a tracked structure as loaded.
     *
     * <p>-1 means currently loaded.
     */
    public static void markLoaded(final ServerLevel level, final UUID uuid) {
        INSTANCE.markLoadedInternal(level, uuid);
    }

    private void markLoadedInternal(final ServerLevel level, final UUID uuid) {
        final NPShipsSavedData data = NPShipsSavedData.get(level);

        if (!data.isTracked(uuid)) {
            return;
        }

        boolean changed = data.getUnloadDeadline(uuid) >= 0L;
        data.markLoaded(uuid);
        if (changed)
            notifyTracking(level, uuid, TrackingPhase.LOAD_OBSERVED, "NATIVE_LOAD_OBSERVED");
    }

    /** Marks a tracked structure as unloaded and starts its configured deletion countdown. */
    public static void markUnloaded(final ServerLevel level, final UUID uuid) {
        INSTANCE.markUnloadedInternal(level, uuid);
    }

    private void markUnloadedInternal(final ServerLevel level, final UUID uuid) {
        final NPShipsSavedData data = NPShipsSavedData.get(level);

        if (!data.isTracked(uuid)) {
            return;
        }

        // Both the holding hook and the observer can report the same transition.
        if (data.getUnloadDeadline(uuid) >= 0L) return;
        final long now = System.currentTimeMillis();
        final long deadline =
                now
                        + Math.min(
                                Long.MAX_VALUE - now,
                                com.eynny.npships.config.NPShipsRuntimeConfig.unloadMs());

        data.markUnloaded(uuid, deadline);
        notifyTracking(
                level, uuid, TrackingPhase.UNLOAD_MARKED, "NATIVE_UNLOAD_MARKED_NOT_COMMITTED");
    }

    public static boolean isExpired(final ServerLevel level, final UUID uuid) {
        final long deadline = NPShipsSavedData.get(level).getUnloadDeadline(uuid);

        return deadline >= 0L && deadline <= System.currentTimeMillis();
    }

    public static long getUnloadDeadline(final ServerLevel level, final UUID uuid) {
        return NPShipsSavedData.get(level).getUnloadDeadline(uuid);
    }

    /**
     * Removes only a currently loaded Sable SubLevel.
     *
     * <p>Holding storage is deliberately not touched.
     */
    public static boolean remove(final ServerLevel level, final UUID uuid) {
        return INSTANCE.removeInternal(level, uuid);
    }

    private boolean removeInternal(final ServerLevel level, final UUID uuid) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);

        if (container == null) {
            return false;
        }

        final SubLevel subLevel = container.getSubLevel(uuid);

        if (!(subLevel instanceof final ServerSubLevel serverSubLevel)) {
            return false;
        }

        /*
         * Delete entities before removing the physical
         * structure.
         */
        NPShipsRemovalDiagnostics.log(level, uuid, "REMOVE_REQUEST", "NPShips loaded-body removal");
        NPShipsEntityCleaner.deleteEntitiesInSubLevel(level, serverSubLevel);

        container.removeSubLevel(serverSubLevel, SubLevelRemovalReason.REMOVED);

        return true;
    }

    /**
     * Called by NPShipsSableAssemblyMixin after Sable has created a split child and assigned
     * splitFromSubLevel().
     *
     * <p>The child inherits tracking only when its parent was already tracked by NPShips.
     */
    public static void onSplitChild(
            final ServerLevel level, final ServerSubLevel child, final UUID parentUUID) {
        INSTANCE.handleSplitChild(level, child, parentUUID);
    }

    private void handleSplitChild(
            final ServerLevel level, final ServerSubLevel child, final UUID parentUUID) {
        final NPShipsSavedData data = NPShipsSavedData.get(level);

        /*
         * Parent is not tracked.
         *
         * Therefore this split is irrelevant to NPShips.
         */
        if (!data.isTracked(parentUUID)) {
            return;
        }

        final UUID childUUID = child.getUniqueId();

        /*
         * The newly created child inherits tracking
         * from its tracked parent.
         */
        boolean wasTracked = data.isTracked(childUUID);
        if (!wasTracked) {
            data.track(childUUID);
            data.source(childUUID, data.source(parentUUID));
            data.fragment(childUUID, true);
        }
        data.markLoaded(childUUID);
        if (!wasTracked)
            NPShipsEventBridge.tracking(
                    level,
                    TrackingPhase.SPLIT_CHILD_TRACKED,
                    childUUID,
                    parentUUID,
                    TrackingState.TRACKED_LOADED,
                    -1L,
                    false,
                    data.source(childUUID),
                    "NATIVE_ASSEMBLY_RETURN_CHILD");
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        if (!(subLevel instanceof final ServerSubLevel serverSubLevel)) {
            return;
        }

        final ServerLevel level = serverSubLevel.getLevel();

        this.attachInternal(level);

        final UUID uuid = serverSubLevel.getUniqueId();

        final NPShipsSavedData data = NPShipsSavedData.get(level);

        /*
         * Untracked SubLevels are completely ignored.
         *
         * Split inheritance is handled separately through
         * onSplitChild().
         */
        if (!data.isTracked(uuid)) {
            return;
        }

        /*
         * A tracked SubLevel was added/loaded again.
         */
        this.markLoadedInternal(level, uuid);
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        if (!(subLevel instanceof final ServerSubLevel serverSubLevel)) {
            return;
        }

        final ServerLevel level = serverSubLevel.getLevel();

        final UUID uuid = serverSubLevel.getUniqueId();

        NPShipsRemovalDiagnostics.log(
                level,
                uuid,
                "SABLE_REMOVAL_EVENT",
                reason.name() + "; splitFrom=" + serverSubLevel.getSplitFromSubLevel());

        final NPShipsSavedData data = NPShipsSavedData.get(level);

        switch (reason) {
            case UNLOADED -> {
                /*
                 * Sable moved the SubLevel into holding storage.
                 *
                 * Keep tracking it and start its configured
                 * deletion countdown.
                 */
                if (!data.isTracked(uuid)) {
                    return;
                }

                data.unloadedBounds(uuid, NPShipsLifecycleQuery.worldBounds(serverSubLevel));
                this.markUnloadedInternal(level, uuid);
            }

            case REMOVED -> {
                /*
                 * This exact UUID no longer exists as a
                 * Sable SubLevel.
                 *
                 * Stop tracking only THIS UUID.
                 *
                 * Merge ownership is resolved at the block transfer, before this notification.
                 * Other tracked SubLevels are completely
                 * unaffected.
                 */
                if (!data.isTracked(uuid)) {
                    return;
                }

                this.untrackInternal(level, uuid, "NATIVE_REMOVAL_OBSERVED_BEFORE_COMPLETION");
            }

            default -> {
                /*
                 * Unknown future Sable removal reason.
                 *
                 * Do nothing.
                 */
            }
        }
    }
}
