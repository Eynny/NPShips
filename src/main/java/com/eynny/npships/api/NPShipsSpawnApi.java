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

    /**
     * Admits construction at an exact minimum corner without searching for another location.
     *
     * <p>The same thread, admission, chunk-readiness and terminal rules as the observer overload
     * apply. STARTED means accepted work, never completed placement.
     *
     * @param level actual registered target level; null produces INVALID_REFERENCE
     * @param structureId canonical catalogue identifier
     * @param origin exact minimum-corner world position
     * @return admission and accepted managed job, if any
     * @throws IllegalStateException if a nonnull level is used off its server thread
     */
    public static StartResult requestExact(
            ServerLevel level, ResourceLocation structureId, BlockPos origin) {
        return requestExact(level, structureId, origin, null);
    }

    /**
     * Admits one EXTERNAL exact-origin construction to the existing shared server spawn owner.
     *
     * <p>Uses yaw zero and the selected base schematic, without natural search, player selection,
     * rarity, cooldown or deadline writes. The existing loader prepares and holds the complete
     * physical envelope and native margins. The authored AIR or specified-FLUID inside rule, world
     * bounds, foreign physical overlap and resource limits remain mandatory. No ordinary blocks are
     * cleared. Construction advances over server ticks under the shared spawn budget.
     *
     * <p>The existing SpawnJob owns cancellation and one optional terminal observer. STARTED is
     * admission only; successful result() confirms completion. Refused admission retains no
     * observer. Callbacks run once on the server thread after manager release; exceptions are
     * isolated. Exact requests report mode EXTERNAL and lifecycle route EXACT_API. Caller owns
     * authorization. Cancellation cleans exact owned unfinished bodies; it cannot undo arbitrary
     * effects produced by already-running native mechanisms or third-party code.
     *
     * @param level actual registered target level; null produces INVALID_REFERENCE
     * @param structureId canonical catalogue identifier
     * @param origin exact minimum-corner world position
     * @param observer optional terminal observer; no callback is guaranteed after process crash
     * @return admission and accepted managed job, if any
     * @throws IllegalStateException if a nonnull level is used off its server thread
     */
    public static StartResult requestExact(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            @Nullable Consumer<SpawnResult> observer) {
        return NPShipsRuntimeAccess.get().requestExact(level, structureId, origin, observer);
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
     * guarantees placement nor supports a selected source or fixed origin; use request/requestExact
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
