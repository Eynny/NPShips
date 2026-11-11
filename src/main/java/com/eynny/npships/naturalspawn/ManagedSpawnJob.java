package com.eynny.npships.naturalspawn;

import com.eynny.npships.api.AutomaticSpawnJob;
import com.eynny.npships.api.NPShipsSpawnApi;
import com.eynny.npships.api.SpawnResult;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** One optional callback per active job; sever native state and callback before notifying. */
final class ManagedSpawnJob implements AutomaticSpawnJob {
    private final UUID id;
    private final WeakReference<MinecraftServer> server;
    @Nullable private NaturalSpawnAttempt attempt;
    @Nullable private Consumer<SpawnResult> observer;
    @Nullable private SpawnResult result;

    @Nullable private Consumer<NPShipsSpawnApi.AutomaticResult> automaticObserver;

    @Nullable private NPShipsSpawnApi.AutomaticResult automaticResult;

    static ManagedSpawnJob automatic(
            NaturalSpawnAttempt attempt,
            @Nullable Consumer<NPShipsSpawnApi.AutomaticResult> observer) {
        var job = new ManagedSpawnJob(attempt, null);
        job.automaticObserver = observer;
        return job;
    }

    @Override
    public Optional<NPShipsSpawnApi.AutomaticResult> automaticResult() {
        requireThread();
        return Optional.ofNullable(automaticResult);
    }

    ManagedSpawnJob(NaturalSpawnAttempt attempt, @Nullable Consumer<SpawnResult> observer) {
        this.id = attempt.successEventId;
        this.attempt = attempt;
        this.server = new WeakReference<>(attempt.server);
        this.observer = observer;
    }

    @Override
    public UUID id() {
        return id;
    }

    private void requireThread() {
        var owner = server.get();
        if (owner == null || !owner.isSameThread())
            throw new IllegalStateException("SpawnJob requires its server thread.");
    }

    @Override
    public boolean isFinished() {
        requireThread();
        return result != null;
    }

    @Override
    public Optional<SpawnResult> result() {
        requireThread();
        return Optional.ofNullable(result);
    }

    @Override
    public boolean cancel() {
        requireThread();
        if (attempt == null || attempt.isFinished()) return false;
        attempt.cancel();
        return true;
    }

    void collect() {
        if (result != null || attempt == null) return;
        var completed = attempt;
        var ship = completed.getSpawnedShip();
        result =
                new SpawnResult(
                        id,
                        SpawnResult.Mode.valueOf(completed.getModeName()),
                        completed.getSelectedStructure() == null
                                ? null
                                : completed.getSelectedStructure().structureId(),
                        completed.getLevel() == null
                                ? null
                                : completed.getLevel().dimension().location(),
                        completed.isSuccessful() ? completed.getFoundPlacement().origin() : null,
                        SpawnResult.Outcome.valueOf(completed.getOutcome().name()),
                        completed.getFailureReason(),
                        !completed.isSuccessful()
                                ? SpawnResult.Placement.UNCONFIRMED
                                : ship == null
                                        ? SpawnResult.Placement.STATIC
                                        : SpawnResult.Placement.PHYSICAL,
                        ship == null ? List.of() : ship.getPhysicalIds());
        if (completed.mode == NaturalSpawnAttemptMode.AUTOMATIC)
            automaticResult =
                    new NPShipsSpawnApi.AutomaticResult(result, completed.bypassedRecentCooldown);
        attempt = null;
        var automaticCallback = automaticObserver;
        automaticObserver = null;
        var callback = observer;
        observer = null;
        completed.notifyTerminal();
        if (callback != null || automaticCallback != null) {
            try {
                if (automaticCallback != null) automaticCallback.accept(automaticResult);
                else callback.accept(result);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                LogUtils.getLogger()
                        .warn(
                                "[NPShips API] Completion observer failed for job {}: {}",
                                id,
                                SpawnResult.bounded(failure.toString()));
            }
        }
    }
}
