package com.eynny.npships.api.events;

import com.eynny.npships.api.SpawnResult;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Synchronous SERVER-thread observations on NeoForge.EVENT_BUS. Non-cancellable detached values: no
 * Level, server, body, entity, template or callback is retained. Event receipt is not a
 * disk-durability acknowledgement or a universal native-readiness signal.
 */
public final class NPShipsLifecycleEvent extends Event {
    private final Notification notification;

    /**
     * Wraps a non-null detached notification for NeoForge delivery.
     *
     * @param notification detached host observation
     */
    public NPShipsLifecycleEvent(Notification notification) {
        this.notification = Objects.requireNonNull(notification);
    }

    /**
     * Reads the detached observation carried by this event.
     *
     * @return detached notification carried by this event
     */
    public Notification notification() {
        return notification;
    }

    /** Detached host observation; delivery is not a durable event ledger. */
    public sealed interface Notification permits Spawn, Tracking {}

    /** Existing operation owner producing the observation. */
    public enum Route {
        /** Automatic search. */
        AUTOMATIC_SEARCH,
        /** Manual search. */
        MANUAL_SEARCH,
        /** External search. */
        EXTERNAL_SEARCH,
        /** Exact api. */
        EXACT_API,
        /** Direct command. */
        DIRECT_COMMAND,
        /** Direct internal. */
        DIRECT_INTERNAL
    }

    /** Existing host transition during a spawn operation. */
    public enum SpawnPhase {
        /** Started. */
        STARTED,
        /** Before placement. */
        BEFORE_PLACEMENT,
        /** Succeeded. */
        SUCCEEDED,
        /** An accepted operation failed; partial effects may remain. */
        FAILED,
        /** Cancelled. */
        CANCELLED,
        /** The operation was refused before admission. */
        REFUSED
    }

    /**
     * code is the unchanged originating typed outcome/status name, qualified by route+phase.
     * requestedOrigin is a search reference for search routes, never a promise of final placement.
     * Missing selectedStructure/finalOrigin is legitimate before selection or on refusal/failure.
     * Physical IDs are confirmed complete only on SUCCEEDED. placementStarted describes the host
     * clearing/placement boundary for managed/direct scopes, not arbitrary earlier provider world
     * effects. EXACT_API preserves ExactResult's conservative direct-call entry flag: a further
     * precommit refusal can leave it true. No rollback promise follows a failure.
     *
     * @param operationId originating host operation identity
     * @param route originating operation owner
     * @param phase observed host transition
     * @param dimension target dimension ID
     * @param selectedStructure selected canonical schematic ID, or null when unavailable
     * @param requestedOrigin requested minimum corner or search reference, or null
     * @param finalOrigin confirmed minimum corner, or null
     * @param placement confirmed placement kind
     * @param physicalIds observed body IDs, complete only on success
     * @param code originating typed status/outcome name
     * @param reason bounded originating outcome diagnostic
     * @param placementStarted whether the host mutation boundary has been entered; no rollback
     *     guarantee
     * @param gameTime target level game time at observation
     */
    public record Spawn(
            UUID operationId,
            Route route,
            SpawnPhase phase,
            ResourceLocation dimension,
            @Nullable ResourceLocation selectedStructure,
            @Nullable BlockPos requestedOrigin,
            @Nullable BlockPos finalOrigin,
            SpawnResult.Placement placement,
            List<UUID> physicalIds,
            String code,
            String reason,
            boolean placementStarted,
            long gameTime)
            implements Notification {
        /**
         * Copies mutable coordinates/body IDs and bounds diagnostic text.
         *
         * @param operationId originating host operation identity
         * @param route originating operation owner
         * @param phase observed host transition
         * @param dimension target dimension ID
         * @param selectedStructure selected canonical schematic ID, or null when unavailable
         * @param requestedOrigin requested minimum corner or search reference, or null
         * @param finalOrigin confirmed minimum corner, or null
         * @param placement confirmed placement kind
         * @param physicalIds observed body IDs, complete only on success
         * @param code originating typed status/outcome name
         * @param reason bounded originating outcome diagnostic
         * @param placementStarted whether the host mutation boundary has been entered; no rollback
         *     guarantee
         * @param gameTime target level game time at observation
         */
        public Spawn {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(route);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(placement);
            requestedOrigin = requestedOrigin == null ? null : requestedOrigin.immutable();
            finalOrigin = finalOrigin == null ? null : finalOrigin.immutable();
            physicalIds = List.copyOf(physicalIds);
            code = bounded(code, 96);
            reason = SpawnResult.bounded(reason);
        }
    }

    /** Existing tracking transition; native readiness remains separately qualified. */
    public enum TrackingPhase {
        /** Tracked. */
        TRACKED,
        /** The host observed a tracked native body load. */
        LOAD_OBSERVED,
        /** The host marked the tracked body unloaded. */
        UNLOAD_MARKED,
        /** The host no longer tracks this identity. */
        UNTRACKED,
        /** Existing tracking enrolled a native split child. */
        SPLIT_CHILD_TRACKED,
        /** Existing tracking transfer to a native merge target committed. */
        MERGE_TRANSFER_COMMITTED,
        /** Native deletion was submitted without a durable acknowledgement. */
        HOLDING_DELETE_SUBMITTED
    }

    /** NPShips tracking state at observation time. */
    public enum TrackingState {
        /** The identity is tracked and marked loaded. */
        TRACKED_LOADED,
        /** The identity is tracked and marked unloaded. */
        TRACKED_UNLOADED,
        /** The host no longer tracks this identity. */
        UNTRACKED
    }

    /**
     * State is NPShips tracking state, not proof of native body initialization or deletion.
     * relatedId is the parent for a split, target for merge; relatedRetained applies to merge only.
     * unloadDeadlineMs preserves the existing elapsed-time clock; -1 means no active unload timer.
     *
     * @param phase observed host transition
     * @param dimension target dimension ID
     * @param bodyId native body identity
     * @param relatedId split parent or merge target, or null
     * @param state NPShips tracking state at observation
     * @param unloadDeadlineMs existing elapsed-time unload deadline, or -1 when inactive
     * @param relatedRetained whether the merge-related identity remains tracked
     * @param source bounded native observation source
     * @param cause bounded native tracking cause
     * @param gameTime target level game time at observation
     */
    public record Tracking(
            TrackingPhase phase,
            ResourceLocation dimension,
            UUID bodyId,
            @Nullable UUID relatedId,
            TrackingState state,
            long unloadDeadlineMs,
            boolean relatedRetained,
            String source,
            String cause,
            long gameTime)
            implements Notification {
        /**
         * Checks required identities and bounds observation diagnostics.
         *
         * @param phase observed host transition
         * @param dimension target dimension ID
         * @param bodyId native body identity
         * @param relatedId split parent or merge target, or null
         * @param state NPShips tracking state at observation
         * @param unloadDeadlineMs existing elapsed-time unload deadline, or -1 when inactive
         * @param relatedRetained whether the merge-related identity remains tracked
         * @param source bounded native observation source
         * @param cause bounded native tracking cause
         * @param gameTime target level game time at observation
         */
        public Tracking {
            Objects.requireNonNull(phase);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(bodyId);
            Objects.requireNonNull(state);
            source = bounded(source, 256);
            cause = bounded(cause, 96);
        }
    }

    private static String bounded(String value, int limit) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
