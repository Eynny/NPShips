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
import com.eynny.npships.resources.SpawnWorkBudget;
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

        CLEANUP,
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
    boolean exactOrigin;
    @Nullable Route explicitRoute;
    final NPShipsSpawnApi.AutomaticEffects automaticEffects;

    @Nullable final CommandSourceStack commandSource;

    @Nullable ServerLevel level;

    // One admission draw detaches all later decisions from world ticks and other jobs.
    final net.minecraft.util.RandomSource random;

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
    @Nullable NaturalSpawnPhysicalValidator.Scan bodyPreflight, rotationPreflight;
    @Nullable com.eynny.npships.ship.ShipSpawner.PreparedNatural preparedSpawn;
    @Nullable BlockPos rotationOrigin;
    @Nullable BlockPos rotationSize;
    double rotationRadians;
    boolean rotationSelected, rotationResolved;
    @Nullable SpawnSearchVolume physicalPreloadVolume;
    double rotationRandomUnit;
    java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> rotationSnapshot =
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

    boolean bypassConditions;
    private final java.util.Set<String> bypassWarnings = new java.util.HashSet<>();

    boolean bypass(String reason) {
        if (!bypassConditions) return false;
        if (bypassWarnings.add(reason)) CommandSpawnPolicy.warn(commandSource, reason);
        return true;
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
                explicitRoute != null
                        ? explicitRoute
                        : switch (mode) {
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
        this.random = net.minecraft.util.RandomSource.create(server.overworld().random.nextLong());
        this.chunkResources =
                new ChunkResourceLedger(
                        com.eynny.npships.resources.ServerResourcePolicy.current(server),
                        com.eynny.npships.resources.ServerResourcePolicy.memory(server),
                        com.eynny.npships.resources.ServerResourcePolicy.chunkTickets(server));

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
        this.random = net.minecraft.util.RandomSource.create(level.random.nextLong());
        this.chunkResources =
                new ChunkResourceLedger(
                        com.eynny.npships.resources.ServerResourcePolicy.current(server),
                        com.eynny.npships.resources.ServerResourcePolicy.memory(server),
                        com.eynny.npships.resources.ServerResourcePolicy.chunkTickets(server));

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

    static NaturalSpawnAttempt startExact(
            ServerLevel level,
            NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            @Nullable CommandSourceStack source,
            Route route) {
        var attempt = new NaturalSpawnAttempt(level.getServer(), level, source, candidate, origin);
        attempt.exactOrigin = true;
        attempt.explicitRoute = java.util.Objects.requireNonNull(route);
        return attempt;
    }

    private boolean terminating;
    private boolean cleanupWork, cleanupFailureLogged;
    private int cleanupStage;
    private int cleanupRetryTick = Integer.MIN_VALUE;

    public boolean isTerminating() {
        return isFinished() || phase == Phase.CLEANUP;
    }

    private long workProgress;
    private int turnPortions;
    @Nullable private SpawnWorkBudget currentWorkBudget;

    public SpawnWorkBudget workBudget() {
        return NPShipsNaturalSpawnManager.workBudget(server);
    }

    /** The construction continuation checks this before every bounded portion, never after only. */
    public boolean canContinueWork() {
        if (isFinished()
                || terminating
                || phase == Phase.CLEANUP && !cleanupWork
                || turnPortions <= 0
                || currentWorkBudget == null
                || !currentWorkBudget.hasRemaining()) return false;
        turnPortions--;
        return true;
    }

    long workProgress() {
        return workProgress;
    }

    void progressed() {
        workProgress++;
    }

    boolean waitingForConstruction() {
        return phase != Phase.CLEANUP
                && preparedSpawn != null
                && preparedSpawn.constructionWaiting();
    }

    public boolean tick() {
        if (isFinished()) return true;
        if (currentWorkBudget != null
                || terminating
                || phase == Phase.CLEANUP && cleanupRetryTick == server.getTickCount())
            return false;
        var budget = workBudget();
        try (var work = budget.tryStartWork()) {
            if (work == null) return false;
            currentWorkBudget = budget;
            turnPortions = 32;
            cleanupWork = phase == Phase.CLEANUP;
            var beforePhase = phase;
            var beforeSearch = placementSearch;
            var beforePrepared = preparedSpawn;
            long beforeConstruction =
                    preparedSpawn == null
                            ? 0
                            : preparedSpawn.constructionProgress()
                                    + preparedSpawn.cleanupProgress();
            try {
                if (phase != Phase.CLEANUP
                        && level != null
                        && server.getLevel(level.dimension()) != level) {
                    finishFailed(
                            NaturalSpawnOutcome.PREPARATION_FAILED,
                            "Spawn dimension is no longer available.");
                    return isFinished();
                }
                return advancePhase();
            } finally {
                if (phase != beforePhase
                        || placementSearch != beforeSearch
                        || preparedSpawn != beforePrepared
                        || preparedSpawn != null
                                && preparedSpawn.constructionProgress()
                                                + preparedSpawn.cleanupProgress()
                                        != beforeConstruction) progressed();
                cleanupWork = false;
                currentWorkBudget = null;
                turnPortions = 0;
            }
        }
    }

    private boolean advancePhase() {
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

                    case CLEANUP -> {
                        stepCleanup(this::canContinueWork);
                        yield isFinished();
                    }
                    case COMPLETE, FAILED -> true;
                };

            } catch (com.eynny.npships.resources.ResourceLimitException limit) {
                finishFailed(NaturalSpawnOutcome.PREPARATION_FAILED, limit.getMessage());
                return isFinished();
            } catch (Exception exception) {

                finishFailed("Natural spawn failed: " + safeMessage(exception));

                return isFinished();
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

    /** Terminal work stays on this same active owner until every owned resource is released. */
    private boolean stepCleanup(java.util.function.BooleanSupplier canContinue) {
        var timing = timing();
        long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.CLEANUP);
        try {
            while (true) {
                if (cleanupStage == 0 && preparedSpawn != null) {
                    long before = preparedSpawn.cleanupProgress();
                    boolean done;
                    try {
                        done = preparedSpawn.stepCleanup(canContinue);
                    } finally {
                        if (preparedSpawn.cleanupProgress() != before) progressed();
                    }
                    if (!done) return false;
                    preparedSpawn = null;
                    cleanupStage++;
                    progressed();
                    continue;
                }
                if (cleanupStage == 2 && searchChunkLoader != null) {
                    long before = searchChunkLoader.releaseProgress();
                    boolean done;
                    try {
                        done = searchChunkLoader.stepRelease(canContinue);
                    } finally {
                        if (searchChunkLoader.releaseProgress() != before) progressed();
                    }
                    if (!done) return false;
                    searchChunkLoader = null;
                    cleanupStage++;
                    progressed();
                    continue;
                }
                if (!canContinue.getAsBoolean()) return false;
                switch (cleanupStage) {
                    case 0 -> cleanupStage++;
                    case 1 -> {
                        if (areaScanner != null) areaScanner.close();
                        areaScanner = null;
                        spawnSelectionPhase.close();
                        placementSearch = null;
                        rotationCheck = null;
                        bodyPreflight = rotationPreflight = null;
                        rotationSnapshot = new java.util.HashMap<>();
                        cleanupStage++;
                    }
                    case 2 -> cleanupStage++;
                    case 3 -> {
                        chunkResources.close();
                        cleanupStage++;
                    }
                    case 4 -> {
                        phase =
                                outcome == NaturalSpawnOutcome.SUCCESS
                                        ? Phase.COMPLETE
                                        : Phase.FAILED;
                        progressed();
                        if (!isSuccessful()
                                && commandSource != null
                                && mode != NaturalSpawnAttemptMode.AUTOMATIC
                                && !exactOrigin)
                            commandSource.sendFailure(Component.literal(failureReason));
                        return true;
                    }
                    default -> throw new IllegalStateException("Invalid attempt cleanup phase");
                }
                progressed();
            }
        } catch (Exception failure) {
            // A failed exact-owned native removal retains its owner and persistent journal stamp.
            // Do not hot-retry an indivisible native failure within the same server frame.
            cleanupRetryTick = server.getTickCount();
            if (!cleanupFailureLogged) {
                cleanupFailureLogged = true;
                com.mojang.logging.LogUtils.getLogger()
                        .warn(
                                "[NPShips NaturalSpawn] Cleanup retained for retry: {}",
                                safeMessage(failure));
            }
            return false;
        } finally {
            if (timing != null) timing.end(token);
        }
    }

    private void beginCleanup(NaturalSpawnOutcome reason, String message) {
        if (isTerminating() || terminating) return;
        outcome = java.util.Objects.requireNonNull(reason);
        failureReason = message;
        phase = Phase.CLEANUP;
        // No cleanup or provider callbacks here: cancellation can arrive inside forward work.
        progressed();
    }

    public void cancel() {
        beginCleanup(NaturalSpawnOutcome.CANCELLED, "Natural spawn was cancelled.");
    }

    /**
     * Server shutdown has no next tick: explicitly drain once, reporting retained native failures.
     */
    void drainCleanupForShutdown() {
        if (isFinished()) return;
        if (!isTerminating()) cancel();
        if (!stepCleanup(() -> true))
            throw new IllegalStateException(
                    "Spawn cleanup retained native ownership at server shutdown");
        if (!profileCollectionPending) finishTiming();
    }

    void finishFailed() {
        finishFailed("Natural spawn failed: required attempt state is missing.");
    }

    void finishFailed(final String message) {
        finishFailed(NaturalSpawnOutcome.INTERNAL_ERROR, message);
    }

    void finishFailed(final NaturalSpawnOutcome reason, final String message) {
        if (isTerminating() || terminating) return;
        if (reason == NaturalSpawnOutcome.INTERNAL_ERROR
                || reason == NaturalSpawnOutcome.PREPARATION_FAILED
                || reason == NaturalSpawnOutcome.PLACEMENT_FAILED)
            com.mojang.logging.LogUtils.getLogger().warn("[NPShips NaturalSpawn] {}", message);
        beginCleanup(reason, message);
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
        beginCleanup(NaturalSpawnOutcome.SUCCESS, null);
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
