package com.eynny.npships.integration.events;

import com.eynny.npships.api.SpawnResult;
import com.eynny.npships.api.events.NPShipsLifecycleEvent;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.Route;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.Spawn;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.Tracking;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.TrackingPhase;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.TrackingState;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Existing operation boundaries only: no callback registry, event queue, scanner or ticker. */
public final class NPShipsEventBridge {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private NPShipsEventBridge() {}

    /** Detached immutable operation identity, separate from native body UUIDs. */
    public record Operation(
            UUID id,
            Route route,
            @Nullable ResourceLocation structureId,
            @Nullable BlockPos requestedOrigin) {
        public Operation {
            Objects.requireNonNull(id);
            Objects.requireNonNull(route);
            requestedOrigin = requestedOrigin == null ? null : requestedOrigin.immutable();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Operation operation;
        @Nullable private Scope previous;
        @Nullable private BooleanSupplier active;
        @Nullable private Runnable committing;
        private boolean accepted, before, placementStarted, closed;

        private Scope(Operation operation, BooleanSupplier active, Runnable committing) {
            this.operation = Objects.requireNonNull(operation);
            this.active = Objects.requireNonNull(active);
            this.committing = Objects.requireNonNull(committing);
            previous = CURRENT.get();
            CURRENT.set(this);
        }

        public boolean accepted() {
            return accepted;
        }

        public boolean placementStarted() {
            return placementStarted;
        }

        @Override
        public void close() {
            if (closed) return;
            if (CURRENT.get() != this)
                throw new IllegalStateException("Spawn notification scopes closed out of order");
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            previous = null;
            active = null;
            committing = null;
        }
    }

    public static Scope enter(Operation operation, BooleanSupplier active) {
        return new Scope(operation, active, () -> {});
    }

    public static Scope enter(Operation operation, BooleanSupplier active, Runnable committing) {
        return new Scope(operation, active, committing);
    }

    @Nullable
    public static Operation currentOperation() {
        var scope = CURRENT.get();
        return scope == null ? null : scope.operation;
    }

    /** Direct routes have no manager job/fence; guard only their existing scoped native owner. */
    public static boolean hasAcceptedDirectOperation() {
        for (var scope = CURRENT.get(); scope != null; scope = scope.previous)
            if (scope.accepted
                    && !scope.closed
                    && (scope.operation.route() == Route.DIRECT_INTERNAL
                            || scope.operation.route() == Route.DIRECT_COMMAND)) return true;
        return false;
    }

    /** Managed attempts already published their STARTED event at admission. */
    public static void acceptedScope() {
        var scope = CURRENT.get();
        if (scope != null) scope.accepted = true;
    }

    public static void accepted(ServerLevel level) {
        var scope = CURRENT.get();
        if (scope == null || scope.accepted) return;
        scope.accepted = true;
        started(level, scope.operation);
    }

    public static void started(ServerLevel level, Operation operation) {
        complete(
                level,
                operation,
                SpawnPhase.STARTED,
                "STARTED",
                "",
                SpawnResult.Placement.UNCONFIRMED,
                List.of(),
                null,
                false);
    }

    /** Called immediately before host clearing, not after native world mutation has begun. */
    public static void beforePlacement(ServerLevel level, ResourceLocation id, BlockPos origin) {
        var scope = CURRENT.get();
        if (scope == null) return;
        if (scope.active == null || !scope.active.getAsBoolean())
            throw new IllegalStateException("Spawn operation was cancelled before placement");
        if (!scope.accepted) accepted(level);
        if (!scope.before) {
            scope.before = true;
            var operation =
                    new Operation(
                            scope.operation.id,
                            scope.operation.route,
                            id,
                            scope.operation.requestedOrigin);
            complete(
                    level,
                    operation,
                    SpawnPhase.BEFORE_PLACEMENT,
                    "BEFORE_PLACEMENT",
                    "",
                    SpawnResult.Placement.UNCONFIRMED,
                    List.of(),
                    origin,
                    false);
        }
        if (scope.active == null || !scope.active.getAsBoolean())
            throw new IllegalStateException("Spawn operation was cancelled before placement");
        if (!scope.placementStarted) {
            scope.placementStarted = true;
            scope.committing.run();
        }
    }

    public static void complete(
            ServerLevel level,
            Operation operation,
            SpawnPhase phase,
            String code,
            String reason,
            SpawnResult.Placement placement,
            List<UUID> bodies,
            @Nullable BlockPos finalOrigin,
            boolean placementStarted) {
        emit(
                level.getServer(),
                new Spawn(
                        operation.id,
                        operation.route,
                        phase,
                        level.dimension().location(),
                        operation.structureId,
                        operation.requestedOrigin,
                        finalOrigin,
                        placement,
                        bodies,
                        code,
                        reason,
                        placementStarted,
                        level.getGameTime()));
    }

    public static void tracking(
            ServerLevel level,
            TrackingPhase phase,
            UUID id,
            @Nullable UUID related,
            TrackingState state,
            long deadline,
            boolean relatedRetained,
            String source,
            String cause) {
        emit(
                level.getServer(),
                new Tracking(
                        phase,
                        level.dimension().location(),
                        id,
                        related,
                        state,
                        deadline,
                        relatedRetained,
                        source,
                        cause,
                        level.getGameTime()));
    }

    public static void emit(
            MinecraftServer server, NPShipsLifecycleEvent.Notification notification) {
        if (!server.isSameThread())
            throw new IllegalStateException(
                    "NPShips lifecycle notifications require their server thread");
        try {
            // Pinned bus8.0.5 exposes no public listener-presence query. These are event-only
            // allocations.
            NeoForge.EVENT_BUS.post(new NPShipsLifecycleEvent(notification));
        } catch (Throwable failure) {
            rethrowFatal(failure);
            // Native bus logging remains intact; later listeners are not promised delivery.
            LogUtils.getLogger()
                    .warn(
                            "[NPShips API] Lifecycle notification failed ({}): {}",
                            notification.getClass().getSimpleName(),
                            SpawnResult.bounded(failure.toString()));
        }
    }

    private static void rethrowFatal(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        for (int inspected = 0; inspected < 32 && !pending.isEmpty(); inspected++) {
            var next = pending.removeFirst();
            if (next instanceof VirtualMachineError fatal) throw fatal;
            if (next instanceof ThreadDeath fatal) throw fatal;
            if (next.getCause() != null && next.getCause() != next) pending.add(next.getCause());
            var suppressed = next.getSuppressed();
            for (int i = 0; i < Math.min(4, suppressed.length); i++) pending.add(suppressed[i]);
        }
    }
}
