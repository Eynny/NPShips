package com.eynny.npships.naturalspawn;

import com.eynny.npships.api.NPShipsSpawnApi;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.Route;
import com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase;
import com.eynny.npships.config.RarityParameters;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.integration.events.NPShipsEventBridge;
import com.eynny.npships.naturalspawn.search.NaturalSpawnSearchDriver;
import com.eynny.npships.naturalspawn.search.SpawnAreaScanner;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;
import com.eynny.npships.naturalspawn.search.StructurePlacementSearch;
import com.eynny.npships.ship.Ship;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

/**
 * Server-thread attempt handle and sole owner of phase state and chunk-loader cleanup. Phase
 * helpers borrow this context; they never schedule independent ticks or retain their own copy of
 * the selected structure, placement or random plan.
 */
public final class NaturalSpawnAttempt {

    enum Phase {
        INITIALIZE,

        SELECT_PLAYER,
        SELECT_INITIAL_POINT,
        FILTER_STRUCTURES,
        SELECT_INITIAL_STRUCTURE,

        SEARCH_PRIMARY,
        SEARCH_FALLBACK,

        PRELOAD_CHUNKS,
        SPAWN,

        COMPLETE,
        FAILED
    }

    private final SpawnSelectionPhase spawnSelectionPhase = new SpawnSelectionPhase(this);
    private final SpawnSearchPhase spawnSearchPhase = new SpawnSearchPhase(this);
    private final SpawnPlacementPhase spawnPlacementPhase = new SpawnPlacementPhase(this);
    final MinecraftServer server;
    final ChunkResourceLedger chunkResources;
    final com.eynny.npships.resources.ResourceWorkMeter resourceWork =
            new com.eynny.npships.resources.ResourceWorkMeter();

    com.eynny.npships.resources.ResourceMemoryBudget.Scope snapshotMemory() {
        return com.eynny.npships.resources.ServerResourcePolicy.memory(server)
                .scope("spawn snapshot");
    }

    final NaturalSpawnAttemptMode mode;
    final NPShipsSpawnApi.AutomaticEffects automaticEffects;

    @Nullable final CommandSourceStack commandSource;

    @Nullable ServerLevel level;

    @Nullable ServerPlayer selectedPlayer;

    @Nullable BlockPos initialPoint;

    @Nullable NaturalSpawnCandidatePool candidatePool;

    @Nullable NaturalSpawnStructureQueue structureQueue;

    @Nullable NaturalSpawnStructureCandidate selectedStructure;
    NaturalSpawnStructureCandidate plannedStructure;
    com.eynny.npships.authoring.StructureSegments.Plan segmentPlan;

    void prepareSegmentPlan() {
        spawnSelectionPhase.prepareSegmentPlan();
    }

    @Nullable NaturalSpawnPlacement foundPlacement;

    @Nullable SpawnAreaScanner areaScanner;

    @Nullable NaturalSpawnChunkLoader searchChunkLoader;

    @Nullable StructurePlacementSearch placementSearch;

    @Nullable SpawnSearchVolume maximumSearchVolume;

    @Nullable SpawnSearchVolume activeSearchVolume;

    Phase phase;

    long startGameTime;

    int currentHorizontalRadius;

    int currentVerticalRadius;

    /** Horizontal radius increase for this particular structure. */
    int horizontalExpansionStep;

    /** Vertical radius increase for this particular structure. */
    int verticalExpansionStep;

    int attemptedStructureCount;

    int physicalOverlapRetries;
    int playerProximityRetries;

    @Nullable Ship spawnedShip;
    @Nullable ManagedSpawnJob externalJob;

    @Nullable NaturalSpawnRotationCheck rotationCheck;
    @Nullable com.eynny.npships.ship.ShipSpawner.PreparedNatural preparedSpawn;
    @Nullable BlockPos rotationOrigin;
    @Nullable BlockPos rotationSize;
    double rotationRadians;
    double rotationRandomUnit;
    final java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> rotationSnapshot =
            new java.util.HashMap<>();

    @Nullable private SpawnTimingProfiler spawnTiming;
    private boolean profileCollectionPending;

    @Nullable
    public SpawnTimingProfiler timing() {
        if (spawnTiming != null && !spawnTiming.active()) spawnTiming = null;
        return spawnTiming;
    }

    NaturalSpawnAttempt managedProfile() {
        profileCollectionPending = true;
        return this;
    }

    void finishTiming() {
        var timing = timing();
        if (timing != null && isFinished()) {
            try {
                timing.finished(outcome.name(), server.overworld().getGameTime());
            } finally {
                // Open scopes keep only their local borrowed reference until native unwind.
                // A retained completed legacy handle must not retain diagnostic arrays.
                spawnTiming = null;
            }
        }
    }

    @Nullable private String failureReason;

    RarityParameters rarityParameters;
    double opportunityPressure;
    boolean pressureSampled;
    // Accepted OP trigger only, owned by this attempt; never persisted or shared with due ticks.
    boolean relaxRecentCooldown;
    // Observed by the real selection guard; admission alone never proves a bypass.
    boolean bypassedRecentCooldown;
    // Optional OP constraints; normal automatic opportunities leave these null.
    NaturalSpawnStructureCandidate operatorCandidate;
    ServerLevel operatorLevel;
    BlockPos operatorPoint;
    java.util.UUID rarityOwner;
    final java.util.UUID successEventId = java.util.UUID.randomUUID();
    private boolean notificationStarted, notificationTerminal;
    boolean notificationPlacementStarted;

    NPShipsEventBridge.Operation notificationOperation() {
        return new NPShipsEventBridge.Operation(
                successEventId,
                switch (mode) {
                    case AUTOMATIC -> Route.AUTOMATIC_SEARCH;
                    case MANUAL -> Route.MANUAL_SEARCH;
                    case EXTERNAL -> Route.EXTERNAL_SEARCH;
                },
                selectedStructure != null
                        ? selectedStructure.structureId()
                        : operatorCandidate == null ? null : operatorCandidate.structureId(),
                initialPoint);
    }

    void notifyStarted() {
        if (notificationStarted) return;
        notificationStarted = true;
        NPShipsEventBridge.started(
                level == null ? server.overworld() : level, notificationOperation());
    }

    void notifyTerminal() {
        if (notificationTerminal || !isFinished()) return;
        notificationTerminal = true;
        var placement =
                !isSuccessful()
                        ? com.eynny.npships.api.SpawnResult.Placement.UNCONFIRMED
                        : spawnedShip == null
                                ? com.eynny.npships.api.SpawnResult.Placement.STATIC
                                : com.eynny.npships.api.SpawnResult.Placement.PHYSICAL;
        var phase =
                isSuccessful()
                        ? SpawnPhase.SUCCEEDED
                        : outcome == NaturalSpawnOutcome.CANCELLED
                                ? SpawnPhase.CANCELLED
                                : SpawnPhase.FAILED;
        NPShipsEventBridge.complete(
                level == null ? server.overworld() : level,
                notificationOperation(),
                phase,
                outcome == null ? "INTERNAL_ERROR" : outcome.name(),
                failureReason,
                placement,
                spawnedShip == null ? java.util.List.of() : spawnedShip.getPhysicalIds(),
                isSuccessful() && foundPlacement != null ? foundPlacement.origin() : null,
                notificationPlacementStarted);
    }

    NaturalSpawnOutcome lastCandidateFailure = NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED;
    @Nullable private NaturalSpawnOutcome outcome;

    private NaturalSpawnAttempt(final MinecraftServer server, @Nullable CommandSourceStack source) {
        this(server, source, NPShipsSpawnApi.AutomaticEffects.DEFAULT);
    }

    private NaturalSpawnAttempt(
            final MinecraftServer server,
            @Nullable CommandSourceStack source,
            NPShipsSpawnApi.AutomaticEffects effects) {
        this.automaticEffects = java.util.Objects.requireNonNull(effects);
        this.server = server;
        this.chunkResources =
                new ChunkResourceLedger(
                        com.eynny.npships.resources.ServerResourcePolicy.current(server),
                        com.eynny.npships.resources.ServerResourcePolicy.memory(server));

        this.mode = NaturalSpawnAttemptMode.AUTOMATIC;

        this.commandSource = source;

        this.phase = Phase.INITIALIZE;

        this.startGameTime = server.overworld().getGameTime();
        spawnTiming =
                com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled()
                        ? SpawnTimingProfiler.start(
                                successEventId, mode.name(), server.overworld().getGameTime())
                        : null;
    }

    private NaturalSpawnAttempt(
            final MinecraftServer server,
            final ServerLevel level,
            @Nullable final CommandSourceStack commandSource,
            final NaturalSpawnStructureCandidate candidate,
            final BlockPos initialPoint) {
        if (server == null) {
            throw new IllegalArgumentException("server cannot be null.");
        }

        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (candidate == null) {
            throw new IllegalArgumentException("candidate cannot be null.");
        }

        if (initialPoint == null) {
            throw new IllegalArgumentException("initialPoint cannot be null.");
        }

        this.server = server;
        this.chunkResources =
                new ChunkResourceLedger(
                        com.eynny.npships.resources.ServerResourcePolicy.current(server),
                        com.eynny.npships.resources.ServerResourcePolicy.memory(server));

        this.automaticEffects = NPShipsSpawnApi.AutomaticEffects.DEFAULT;
        this.mode =
                commandSource == null
                        ? NaturalSpawnAttemptMode.EXTERNAL
                        : NaturalSpawnAttemptMode.MANUAL;

        this.commandSource = commandSource;

        this.level = level;

        this.selectedStructure = candidate;

        this.initialPoint = initialPoint.immutable();

        this.phase = Phase.INITIALIZE;

        this.startGameTime = level.getGameTime();
        spawnTiming =
                com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled()
                        ? SpawnTimingProfiler.start(
                                successEventId, mode.name(), server.overworld().getGameTime())
                        : null;
    }

    public static NaturalSpawnAttempt start(final MinecraftServer server) {
        return start(server, null);
    }

    static NaturalSpawnAttempt start(MinecraftServer server, @Nullable CommandSourceStack source) {
        return new NaturalSpawnAttempt(server, source);
    }

    static NaturalSpawnAttempt start(
            MinecraftServer server,
            @Nullable CommandSourceStack source,
            NPShipsSpawnApi.AutomaticEffects effects) {
        return new NaturalSpawnAttempt(server, source, effects);
    }

    public static NaturalSpawnAttempt startManual(
            final MinecraftServer server,
            final ServerLevel level,
            final CommandSourceStack commandSource,
            final NaturalSpawnStructureCandidate candidate,
            final BlockPos initialPoint) {
        java.util.Objects.requireNonNull(commandSource, "commandSource");
        return new NaturalSpawnAttempt(server, level, commandSource, candidate, initialPoint);
    }

    static NaturalSpawnAttempt startExternal(
            final ServerLevel level,
            final NaturalSpawnStructureCandidate candidate,
            final BlockPos referencePoint) {
        return new NaturalSpawnAttempt(level.getServer(), level, null, candidate, referencePoint);
    }

    public boolean tick() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.SLICE);
        try {

            try {

                return switch (phase) {
                    case INITIALIZE -> {
                        initialize();
                        yield false;
                    }

                    case SELECT_PLAYER -> {
                        selectPlayer();
                        yield false;
                    }

                    case SELECT_INITIAL_POINT -> {
                        selectInitialPoint();
                        yield false;
                    }

                    case FILTER_STRUCTURES -> {
                        filterStructures();
                        yield false;
                    }

                    case SELECT_INITIAL_STRUCTURE -> {
                        selectInitialStructure();
                        yield false;
                    }

                    case SEARCH_PRIMARY -> {
                        processPrimarySearch();
                        yield false;
                    }

                    case SEARCH_FALLBACK -> {
                        processFallbackSearch();
                        yield false;
                    }

                    case PRELOAD_CHUNKS -> {
                        processChunkPreload();
                        yield false;
                    }

                    case SPAWN -> {
                        processSpawn();
                        yield false;
                    }

                    case COMPLETE, FAILED -> true;
                };

            } catch (com.eynny.npships.resources.ResourceLimitException limit) {
                finishFailed(NaturalSpawnOutcome.PREPARATION_FAILED, limit.getMessage());
                return true;
            } catch (Exception exception) {

                finishFailed("Natural spawn failed: " + safeMessage(exception));

                return true;
            }

        } finally {
            if (!profileCollectionPending && isFinished()) finishTiming();
            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void initialize() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.INITIALIZE);
        try {
            spawnSelectionPhase.initialize();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void selectPlayer() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PLAYERS);
        try {
            spawnSelectionPhase.selectPlayer();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void selectInitialPoint() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null
                        ? 0
                        : spawnTiming.begin(SpawnTimingProfiler.Stage.INITIAL_POINT);
        try {
            spawnSelectionPhase.selectInitialPoint();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void filterStructures() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CATALOGUE);
        try {
            spawnSelectionPhase.filterStructures();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void selectInitialStructure() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.FALLBACK);
        try {
            spawnSelectionPhase.selectInitialStructure();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    boolean startNextStructure() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.FALLBACK);
        try {
            return spawnSelectionPhase.startNextStructure();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    void initializePrimarySearch() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.ENVELOPE);
        try {
            spawnSearchPhase.initializePrimarySearch();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    boolean fallbackSearch;
    long searchOperations;
    long scanOperations;
    int searchDriverCalls;

    NaturalSpawnSearchDriver.Result stepSearchWork() {
        var result = spawnSearchPhase.stepSearchWork();
        searchOperations += result.search().operationsUsed();
        scanOperations += result.scanOperations();
        searchDriverCalls++;
        var timing = timing();
        if (timing != null) {
            timing.search(result.search().operationsUsed(), result.scanOperations());
            if (result.scanner() == SpawnAreaScanner.Status.WAITING_FOR_UNLOADED_CHUNK)
                timing.gap(SpawnTimingProfiler.Gap.CHUNKS);
        }
        return result;
    }

    private void processPrimarySearch() {
        spawnSearchPhase.processPrimarySearch();
    }

    /**
     * Expands the currently active search volume.
     *
     * <p>Horizontal and vertical growth are independent and are calculated from the selected
     * structure dimensions.
     */
    boolean expandPrimarySearch() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.ENVELOPE);
        try {
            return spawnSearchPhase.expandPrimarySearch();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void processFallbackSearch() {
        spawnSearchPhase.processFallbackSearch();
    }

    boolean validateFoundPlacement() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.SAFETY);
        try {
            return spawnPlacementPhase.validateFoundPlacement();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    @Nullable
    net.minecraft.world.phys.Vec3 rotationTarget() {
        return spawnPlacementPhase.rotationTarget();
    }

    void rejectRotationPlacement() {
        spawnPlacementPhase.rejectRotationPlacement();
    }

    private void processChunkPreload() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CHUNKS);
        try {
            spawnPlacementPhase.processChunkPreload();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private void processSpawn() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PLACEMENT);
        try {
            spawnPlacementPhase.processSpawn();

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    static int ceilHalf(final int value) {

        return value / 2 + value % 2;
    }

    static int clampRadius(final int value, final int min, final int max) {

        return Math.max(min, Math.min(max, value));
    }

    void releaseSearchLoader() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CLEANUP);
        try {
            try {
                if (preparedSpawn != null) preparedSpawn.close();
            } finally {
                preparedSpawn = null;
                if (areaScanner != null) areaScanner.close();
                areaScanner = null;
                placementSearch = null;
                rotationCheck = null;
                try {
                    if (searchChunkLoader != null) searchChunkLoader.release();
                } finally {
                    searchChunkLoader = null;
                    chunkResources.close();
                }
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public void cancel() {
        var spawnTiming = timing();
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CLEANUP);
        try {

            if (isFinished()) {
                return;
            }

            outcome = NaturalSpawnOutcome.CANCELLED;
            failureReason = "Natural spawn was cancelled.";

            releaseSearchLoader();

            phase = Phase.FAILED;

            if (commandSource != null && mode != NaturalSpawnAttemptMode.AUTOMATIC) {

                commandSource.sendFailure(Component.literal("Natural spawn was cancelled."));
            }

        } finally {
            if (!profileCollectionPending && isFinished()) finishTiming();
            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    void finishFailed() {
        finishFailed("Natural spawn failed: required attempt state is missing.");
    }

    void finishFailed(final String message) {
        finishFailed(NaturalSpawnOutcome.INTERNAL_ERROR, message);
    }

    void finishFailed(final NaturalSpawnOutcome reason, final String message) {
        if (isFinished()) return;
        outcome = java.util.Objects.requireNonNull(reason);
        failureReason = message;
        if (reason == NaturalSpawnOutcome.INTERNAL_ERROR
                || reason == NaturalSpawnOutcome.PREPARATION_FAILED
                || reason == NaturalSpawnOutcome.PLACEMENT_FAILED)
            com.mojang.logging.LogUtils.getLogger().warn("[NPShips NaturalSpawn] {}", message);

        releaseSearchLoader();

        phase = Phase.FAILED;

        if (commandSource != null && mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            commandSource.sendFailure(Component.literal(message));
        }
    }

    static String safeMessage(final Exception exception) {

        final String message = exception.getMessage();

        return message == null ? exception.getClass().getSimpleName() : message;
    }

    @Nullable
    public ServerLevel getLevel() {
        return level;
    }

    @Nullable
    public ServerPlayer getSelectedPlayer() {
        return selectedPlayer;
    }

    @Nullable
    public BlockPos getInitialPoint() {
        return initialPoint;
    }

    @Nullable
    public NaturalSpawnStructureCandidate getSelectedStructure() {
        return selectedStructure;
    }

    @Nullable
    public NaturalSpawnPlacement getFoundPlacement() {
        return foundPlacement;
    }

    @Nullable
    public NaturalSpawnOutcome getOutcome() {
        return outcome;
    }

    void complete() {
        outcome = NaturalSpawnOutcome.SUCCESS;
        phase = Phase.COMPLETE;
    }

    public long getStartGameTime() {
        return startGameTime;
    }

    public boolean isFinished() {
        return phase == Phase.COMPLETE || phase == Phase.FAILED;
    }

    public boolean isSuccessful() {
        return phase == Phase.COMPLETE;
    }

    @Nullable
    public Ship getSpawnedShip() {
        return spawnedShip;
    }

    @Nullable
    public String getFailureReason() {
        return failureReason;
    }

    /*test*/
    public String getPhaseName() {
        return phase.name();
    }

    public String getModeName() {
        return mode.name();
    }

    public long getElapsedTicks() {

        final ServerLevel currentLevel = level != null ? level : server.overworld();

        return Math.max(0L, currentLevel.getGameTime() - startGameTime);
    }
}
