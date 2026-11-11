package com.eynny.npships.api;

import com.eynny.npships.api.internal.NPShipsRuntimeAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

/** Server-thread entry point for other mods; no player or command source is required. */
public final class NPShipsSpawnApi {
    private NPShipsSpawnApi() {}

    /** Terminal outcome of one synchronous exact-placement call. */
    public enum ExactStatus {
        /** Placement completed at the supplied envelope origin. */
        SUCCESS,
        /** A managed attempt or another synchronous exact call owns spawning. */
        BUSY,
        /** The selected catalogue schematic is missing, invalid or unavailable. */
        INVALID_STRUCTURE,
        /** The level or complete envelope lies outside supported target bounds. */
        INVALID_ORIGIN,
        /** The target server or required catalogue lifecycle is unavailable. */
        SERVER_UNAVAILABLE,
        /** Some part of the full target envelope is not already loaded. */
        UNLOADED_REGION,
        /** A loaded physical body intersects the target envelope. */
        PHYSICAL_OVERLAP,
        /** Existing resource admission refused known owned preparation or placement growth. */
        RESOURCE_LIMIT,
        /** Source decoding, composition or provider preparation failed before direct placement. */
        PREPARATION_FAILED,
        /** Direct placement, assembly or restoration failed; partial effects may remain. */
        PLACEMENT_FAILED
    }

    /**
     * Detached synchronous result. The origin and body list may be retained without retaining a
     * world. The placement-started flag conservatively marks entry into direct placement; it does
     * not prove a durable commit, count mutations or promise provider rollback.
     *
     * @param status exact-placement outcome
     * @param structureId selected canonical identifier, nullable when unavailable from invalid
     *     input
     * @param dimension target dimension identifier, nullable when no valid level was supplied
     * @param origin requested minimum corner, defensively made immutable; nullable for invalid
     *     input
     * @param reason bounded diagnostic, empty when no diagnostic is needed
     * @param placement static, physical or unconfirmed placement classification
     * @param physicalIds complete body IDs on success, empty for a static success; failure does not
     *     promise a complete partial-assembly list
     * @param placementStarted whether the direct-placement call was entered
     */
    public record ExactResult(
            ExactStatus status,
            @Nullable ResourceLocation structureId,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos origin,
            String reason,
            SpawnResult.Placement placement,
            java.util.List<java.util.UUID> physicalIds,
            boolean placementStarted) {
        /**
         * Detaches the mutable-position input, bounds the diagnostic and copies body identities.
         *
         * @param status exact-placement outcome
         * @param structureId selected identifier, or null when unavailable
         * @param dimension target dimension, or null when unavailable
         * @param origin requested minimum corner, or null when unavailable
         * @param reason diagnostic to bound; null becomes the empty diagnostic
         * @param placement placement classification
         * @param physicalIds body identities to copy
         * @param placementStarted whether direct placement was entered
         * @throws NullPointerException if physicalIds or an element is null
         */
        public ExactResult {
            origin = origin == null ? null : origin.immutable();
            reason = SpawnResult.bounded(reason);
            physicalIds = java.util.List.copyOf(physicalIds);
        }

        /**
         * Tests whether direct placement completed successfully.
         *
         * @return whether status is SUCCESS
         */
        public boolean successful() {
            return status == ExactStatus.SUCCESS;
        }
    }

    /**
     * Synchronously places one existing catalogue schematic at the exact minimum-corner origin.
     * Never searches, selects players or changes automatic rarity/pressure/deadlines. Uses direct
     * command placement semantics: clears the authored envelope; natural environment/restriction
     * filters and natural fluid restoration are not applied. The complete envelope must already be
     * loaded, within height/border and free of loaded physical bodies. NPShips requests no target
     * chunks; native callbacks remain opaque.
     *
     * <p>Server-thread only; busy during any managed attempt or reentrant exact call. Native
     * preparation/assembly/providers are atomic and may block for seconds. Not cancellable or
     * rollback-capable. Only successful results promise all returned physical body IDs; failure
     * after commit can leave world/provider effects. Caller owns authorization; this is no RPC.
     *
     * @param level actual registered target level; null produces a typed refusal
     * @param structureId canonical catalogue identifier; null produces a typed refusal
     * @param origin exact minimum-corner world position; null produces a typed refusal
     * @return detached success, refusal or failure result from this synchronous call
     * @throws IllegalStateException if a nonnull level is used off its server thread
     */
    public static ExactResult spawnExact(
            ServerLevel level, ResourceLocation structureId, BlockPos origin) {
        return NPShipsRuntimeAccess.get().spawnExact(level, structureId, origin);
    }

    /** Policy for the recent successful-player cooldown; no other safety gate is bypassed. */
    public enum CooldownPolicy {
        /** Preserve ordinary automatic cooldown checks. */
        RESPECT,
        /** Trusted caller explicitly permits bypassing the recent-success cooldown. */
        BYPASS
    }

    /**
     * Options for one immediate automatic opportunity, using normal natural source/player
     * selection.
     *
     * @param cooldownPolicy explicit recent-success policy
     */
    public record AutomaticOptions(CooldownPolicy cooldownPolicy) {
        /**
         * Checks that the policy is explicit.
         *
         * @param cooldownPolicy recent-success policy
         * @throws NullPointerException when the policy is null
         */
        public AutomaticOptions {
            java.util.Objects.requireNonNull(cooldownPolicy);
        }
    }

    /**
     * Independent writes for one admitted automatic opportunity. Reading existing pressure,
     * checking cooldown, selecting players and ordinary C8/safety/resource rules are unchanged. The
     * immutable value is captured before lifecycle callbacks; it never pauses the server's
     * unrelated scheduler or promises rollback of native placement.
     *
     * @param recordCooldown whether success records the selected player's recent-success cooldown
     * @param recordRarity whether success records a rarity-history event and pressure contribution
     * @param reschedule whether admission initializes/expires the global deadline and terminal
     *     collection schedules its successor; false preserves deadline and initialization state
     */
    public record AutomaticEffects(
            boolean recordCooldown, boolean recordRarity, boolean reschedule) {
        /** Ordinary automatic, OP and existing API behavior. */
        public static final AutomaticEffects DEFAULT = new AutomaticEffects(true, true, true);

        /** Preserve all three write effects; existing selection checks still apply. */
        public static final AutomaticEffects PRESERVE_ALL =
                new AutomaticEffects(false, false, false);
    }

    /** Admission status, distinct from the later terminal spawn outcome. */
    public enum AutomaticStatus {
        /** The existing automatic manager accepted the opportunity. */
        STARTED,
        /** Another managed or synchronous operation owns spawning. */
        BUSY,
        /** Automatic spawning is disabled in this world. */
        DISABLED,
        /** No eligible online player is available. */
        NO_ELIGIBLE_PLAYER,
        /** The server is null, stopping, stopped or has no loaded overworld. */
        SERVER_UNAVAILABLE
    }

    /**
     * Automatic admission. Refused admissions never retain or invoke the observer.
     *
     * @param status admission outcome
     * @param job handle present exactly for STARTED
     * @param reason bounded refusal diagnostic
     */
    public record AutomaticStartResult(
            AutomaticStatus status, Optional<AutomaticSpawnJob> job, String reason) {
        /**
         * Validates handle presence and bounds the diagnostic.
         *
         * @param status admission outcome
         * @param job handle present exactly for STARTED
         * @param reason diagnostic, null becomes empty
         * @throws NullPointerException if status or job is null
         * @throws IllegalArgumentException if status disagrees with handle presence
         */
        public AutomaticStartResult {
            java.util.Objects.requireNonNull(status);
            java.util.Objects.requireNonNull(job);
            reason = SpawnResult.bounded(reason);
            if ((status == AutomaticStatus.STARTED) != job.isPresent())
                throw new IllegalArgumentException("Only STARTED can contain an automatic job.");
        }
    }

    /**
     * Detached terminal result of an admitted automatic opportunity.
     *
     * @param result ordinary result, always mode AUTOMATIC; early failure may have no
     *     source/dimension
     * @param recentCooldownBypassed whether a blocked reference actually used BYPASS, even if later
     *     placement failed
     */
    public record AutomaticResult(SpawnResult result, boolean recentCooldownBypassed) {
        /**
         * Requires an actual automatic terminal result.
         *
         * @param result terminal automatic result
         * @param recentCooldownBypassed observed bypass, not simply the requested policy
         * @throws NullPointerException if result is null
         * @throws IllegalArgumentException if result mode is not AUTOMATIC
         */
        public AutomaticResult {
            java.util.Objects.requireNonNull(result);
            if (result.mode() != SpawnResult.Mode.AUTOMATIC)
                throw new IllegalArgumentException("Automatic result requires AUTOMATIC mode.");
        }
    }

    /**
     * Requests one immediate AUTOMATIC opportunity through the existing scheduler owner. Uses
     * ordinary player selection, C8, rarity, pressure, resource controls and placement safety.
     * Acceptance expires the current deadline; terminal collection schedules the next one. Success
     * records normal pressure/history and the successful-player cooldown. This call neither
     * guarantees placement nor supports a selected source or fixed origin; use request/spawnExact
     * for their distinct contracts.
     *
     * <p>Server-thread only. Trusted Java callers own authorization; no command permission is
     * fabricated. At most one observer is retained by the active job. It runs once, synchronously
     * after manager release; exceptions are isolated. Cancellation is not world rollback. Do not
     * retain native world objects in callbacks. No notification is guaranteed after process crash.
     *
     * @param server running server, null produces SERVER_UNAVAILABLE
     * @param options explicit cooldown policy
     * @param observer optional terminal callback; refused admission never invokes it
     * @return typed admission and accepted job
     * @throws NullPointerException if options is null
     * @throws IllegalStateException if a nonnull server is used off its server thread
     */
    public static AutomaticStartResult requestAutomatic(
            net.minecraft.server.MinecraftServer server,
            AutomaticOptions options,
            @Nullable Consumer<AutomaticResult> observer) {
        return requestAutomatic(server, options, AutomaticEffects.DEFAULT, observer);
    }

    /**
     * Requests an ordinary AUTOMATIC opportunity with independently selected bookkeeping writes.
     * Cooldown checking is controlled by options independently from recording a new cooldown. The
     * effects value is captured before the first lifecycle notification. With reschedule false,
     * admission and success/failure/cancel/stop leave both global deadline and its initialization
     * state untouched. A naturally due scheduler may start a separate opportunity after this job
     * releases the manager; preserving writes does not freeze time or the server.
     *
     * <p>All ordinary enabled/player/C8/environment/safety/resource gates, server-thread and
     * exactly-once normal terminal callback contracts of the existing overload remain in force.
     * Refused admission never invokes or retains the observer.
     *
     * @param server running server, null produces SERVER_UNAVAILABLE
     * @param options explicit policy for checking an existing recent-success cooldown
     * @param effects immutable independent cooldown, rarity and scheduling writes
     * @param observer optional terminal callback after manager release
     * @return typed admission and accepted job
     * @throws NullPointerException if options or effects is null
     * @throws IllegalStateException if a nonnull server is used off its server thread
     */
    public static AutomaticStartResult requestAutomatic(
            net.minecraft.server.MinecraftServer server,
            AutomaticOptions options,
            AutomaticEffects effects,
            @Nullable Consumer<AutomaticResult> observer) {
        java.util.Objects.requireNonNull(options);
        java.util.Objects.requireNonNull(effects);
        return NPShipsRuntimeAccess.get().requestAutomatic(server, options, effects, observer);
    }

    /** Admission outcome before a managed selected-structure search begins. */
    public enum StartStatus {
        /** The existing manager accepted a new job. */
        STARTED,
        /** Another active or uncollected attempt owns the manager. */
        BUSY,
        /** No valid natural-placement schematic exists for the selected identifier. */
        INVALID_STRUCTURE,
        /** The level or world reference is missing, unregistered or outside supported bounds. */
        INVALID_REFERENCE,
        /** The target server is stopping or stopped. */
        SERVER_UNAVAILABLE
    }

    /**
     * Admission result. Refusals have no job and are not terminal attempt results.
     *
     * @param status admission status
     * @param job accepted handle, present exactly when status is STARTED
     * @param reason bounded admission diagnostic, empty when accepted
     */
    public record StartResult(StartStatus status, Optional<SpawnJob> job, String reason) {
        /**
         * Bounds the diagnostic and checks that accepted status and job presence agree.
         *
         * @param status admission status
         * @param job accepted handle, present exactly for STARTED
         * @param reason diagnostic to bound; null becomes the empty diagnostic
         * @throws IllegalArgumentException if job presence does not match STARTED
         * @throws NullPointerException if job is null
         */
        public StartResult {
            reason = SpawnResult.bounded(reason);
            if ((status == StartStatus.STARTED) != job.isPresent())
                throw new IllegalArgumentException("Only STARTED can contain a job.");
        }
    }

    /**
     * Starts a selected-structure search without a terminal observer.
     *
     * <p>The same search, thread and ownership rules as the observer overload apply. A refused
     * request creates no job; an accepted job advances only through the existing NPShips manager.
     *
     * @param level actual registered target level; null produces INVALID_REFERENCE
     * @param structureId canonical identifier of a valid natural-placement schematic
     * @param referencePoint world-space search reference, never an arbitrary native plot position
     * @return admission status and, only for STARTED, its managed job
     * @throws IllegalStateException if a nonnull level is used off its server thread
     */
    public static StartResult request(
            ServerLevel level, ResourceLocation structureId, BlockPos referencePoint) {
        return request(level, structureId, referencePoint, null);
    }

    /**
     * Starts one EXTERNAL selected-structure search on the actual target level. World coordinates
     * are never projected from arbitrary Sable plot coordinates. Uses native restrictions,
     * environment, height, border, overlap and bounded search/placement; no automatic rarity/C8,
     * player-selection/distance gates, pressure awards, cooldown or deadline changes.
     *
     * <p>At most one observer is held with this active job, invoked synchronously once at terminal
     * collection after manager release. It must return promptly, must not assume rollback and must
     * not retain world objects. Its exception is isolated. Cancellation is not world rollback.
     * Off-thread calls throw; dynamic restrictions/search failure are later terminal results.
     *
     * @param level actual registered target level; null produces INVALID_REFERENCE
     * @param structureId canonical identifier of a valid natural-placement schematic
     * @param referencePoint world-space search reference in the actual target dimension
     * @param observer optional terminal observer, invoked on the server thread after manager
     *     release; refused admissions never retain or invoke it
     * @return admission status and, only for STARTED, its managed job
     * @throws IllegalStateException if a nonnull level is used off its server thread
     */
    public static StartResult request(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos referencePoint,
            @Nullable Consumer<SpawnResult> observer) {
        return NPShipsRuntimeAccess.get().request(level, structureId, referencePoint, observer);
    }
}
