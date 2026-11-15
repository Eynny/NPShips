package com.eynny.npships.naturalspawn;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.integration.events.NPShipsEventBridge;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;
import com.eynny.npships.ship.Ship;
import com.eynny.npships.ship.ShipSpawner;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

/** One phase of the server-thread attempt; shared state and cleanup remain owned by the attempt. */
final class SpawnPlacementPhase {
    private final NaturalSpawnAttempt attempt;

    SpawnPlacementPhase(NaturalSpawnAttempt attempt) {
        this.attempt = attempt;
    }

    boolean validateFoundPlacement() {

        if (attempt.selectedStructure == null
                || attempt.foundPlacement == null
                || attempt.level == null) {

            attempt.finishFailed(
                    "Natural spawn internal error: " + "found placement is incomplete.");

            return false;
        }

        if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC) {
            var proximity =
                    NaturalSpawnPlayerProximity.find(
                            attempt.level,
                            attempt.foundPlacement.origin(),
                            attempt.selectedStructure.size(),
                            0);
            if (proximity != null && !attempt.bypass("player_distance")) {
                // Once preparation exists, rejection is terminal: never continue with its old
                // template/devices or synchronously restart search at the final boundary.
                if (attempt.preparedSpawn != null)
                    attempt.finishFailed(NaturalSpawnOutcome.PLAYER_TOO_CLOSE, proximity.message());
                else rejectPlayerPlacement(proximity);
                return false;
            }
        }

        if (attempt.bypassConditions
                && !attempt.selectedStructure
                        .schematic()
                        .config()
                        .restrictions()
                        .allowsOriginY(attempt.foundPlacement.origin().getY()))
            attempt.bypass("height");

        if (!(attempt.bypassConditions
                                ? attempt.selectedStructure.schematic().config()
                                : attempt.selectedStructure.config())
                        .placement()
                        .avoidPhysicalStructures()
                || attempt.preparedSpawn != null) {

            return true;
        }

        if (attempt.bodyPreflight == null
                || !attempt.bodyPreflight.matches(
                        attempt.level,
                        attempt.foundPlacement.origin(),
                        attempt.selectedStructure.size(),
                        0))
            attempt.bodyPreflight =
                    new NaturalSpawnPhysicalValidator.Scan(
                            attempt.level,
                            attempt.foundPlacement.origin(),
                            attempt.selectedStructure.size(),
                            0);
        long before = attempt.bodyPreflight.progress();
        boolean done = attempt.bodyPreflight.step(attempt::canContinueWork);
        if (attempt.bodyPreflight.progress() != before) attempt.progressed();
        if (!done) return false;
        final NaturalSpawnPhysicalValidator.Overlap overlap = attempt.bodyPreflight.result();

        if (overlap == null || attempt.bypass("physical_overlap")) {
            return true;
        }

        if (attempt.preparedSpawn != null) {
            attempt.finishFailed(NaturalSpawnOutcome.PHYSICAL_OVERLAP, overlap.message());
            return false;
        }

        if (attempt.placementSearch != null
                && attempt.physicalOverlapRetries
                        < NaturalSpawnLimits.max_physical_overlap_retries()) {
            attempt.physicalOverlapRetries++;
            attempt.placementSearch.excludePhysicalBounds(overlap.bounds());
            attempt.foundPlacement = null;
            resetPlacementEnvelope();
            attempt.phase =
                    attempt.fallbackSearch
                            ? NaturalSpawnAttempt.Phase.SEARCH_FALLBACK
                            : NaturalSpawnAttempt.Phase.SEARCH_PRIMARY;
            if (attempt.searchChunkLoader != null && attempt.activeSearchVolume != null) {
                attempt.searchChunkLoader.useRequestedChunks();
            }

            return false;
        }

        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            attempt.finishFailed(
                    NaturalSpawnOutcome.PHYSICAL_OVERLAP,
                    overlap.message() + " Physical overlap retry limit reached.");

            return false;
        }

        /*
         * Automatic mode:
         * reject this placement/structure and continue fallback.
         */
        attempt.lastCandidateFailure = NaturalSpawnOutcome.PHYSICAL_OVERLAP;
        attempt.foundPlacement = null;
        resetPlacementEnvelope();

        attempt.placementSearch = null;

        attempt.selectedStructure = null;

        attempt.phase = NaturalSpawnAttempt.Phase.SEARCH_FALLBACK;

        return false;
    }

    private void rejectPlayerPlacement(NaturalSpawnPlayerProximity.Violation proximity) {
        attempt.lastCandidateFailure = NaturalSpawnOutcome.PLAYER_TOO_CLOSE;

        if (attempt.placementSearch != null
                && attempt.playerProximityRetries
                        < NaturalSpawnLimits.max_physical_overlap_retries()) {
            attempt.playerProximityRetries++;
            // Dynamic players are not immutable physical AABB exclusions. Skip just this origin
            // in the existing ordered queue; positions will be read live for the next result.
            attempt.placementSearch.rejectFoundPlacement();
        } else {
            attempt.placementSearch = null;
            attempt.selectedStructure = null;
        }
        attempt.foundPlacement = null;
        resetPlacementEnvelope();
        attempt.rotationCheck = null;
        attempt.phase =
                attempt.selectedStructure == null || attempt.fallbackSearch
                        ? NaturalSpawnAttempt.Phase.SEARCH_FALLBACK
                        : NaturalSpawnAttempt.Phase.SEARCH_PRIMARY;
        if (attempt.searchChunkLoader != null) attempt.searchChunkLoader.useRequestedChunks();
    }

    @Nullable
    net.minecraft.world.phys.Vec3 rotationTarget() {
        final ServerPlayer player =
                attempt.selectedPlayer != null
                        ? attempt.selectedPlayer
                        : attempt.commandSource != null
                                        && attempt.commandSource.getEntity()
                                                instanceof ServerPlayer sourcePlayer
                                ? sourcePlayer
                                : null;
        return player != null && player.serverLevel() == attempt.level
                ? NaturalSpawnAttemptContext.worldPosition(player)
                : null;
    }

    void rejectRotationPlacement() {
        if (attempt.placementSearch == null) {
            attempt.finishFailed(
                    NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED,
                    "Natural spawn requires rotation clearance, but this placement cannot rotate.");
            return;
        }
        attempt.placementSearch.rejectFoundPlacement();
        attempt.foundPlacement = null;
        resetPlacementEnvelope();
        attempt.rotationCheck = null;
        attempt.phase =
                attempt.fallbackSearch
                        ? NaturalSpawnAttempt.Phase.SEARCH_FALLBACK
                        : NaturalSpawnAttempt.Phase.SEARCH_PRIMARY;
        if (attempt.searchChunkLoader != null && attempt.activeSearchVolume != null)
            attempt.searchChunkLoader.useRequestedChunks();
    }

    private void resetPlacementEnvelope() {
        attempt.bodyPreflight = attempt.rotationPreflight = null;
        attempt.rotationResolved = false;
        attempt.rotationSelected = false;
        attempt.physicalPreloadVolume = null;
    }

    void processChunkPreload() {

        if (attempt.selectedStructure == null
                || attempt.foundPlacement == null
                || attempt.level == null) {

            attempt.finishFailed();
            return;
        }

        final var placement = attempt.selectedStructure.config().placement();
        final var facingTarget = placement.disableAutoRotation() ? null : attempt.rotationTarget();
        if (!attempt.rotationResolved) {
            if (!placement.disableAutoRotation()
                    && facingTarget != null
                    && attempt.areaScanner != null) {
                if (attempt.rotationCheck == null
                        || !attempt.foundPlacement.origin().equals(attempt.rotationOrigin)
                        || !attempt.selectedStructure.size().equals(attempt.rotationSize)) {
                    attempt.rotationOrigin = attempt.foundPlacement.origin();
                    attempt.rotationSize = attempt.selectedStructure.size();
                    attempt.rotationCheck =
                            new NaturalSpawnRotationCheck(
                                    attempt.level,
                                    attempt.areaScanner.snapshot(),
                                    attempt.rotationOrigin,
                                    attempt.rotationSize,
                                    attempt.rotationSnapshot);
                    attempt.rotationRandomUnit = attempt.random.nextDouble();
                }
            } else {
                attempt.rotationCheck = null;
                if (!placement.disableAutoRotation() && placement.requireRotationSpace()) {
                    attempt.finishFailed(
                            NaturalSpawnOutcome.NO_ELIGIBLE_PLAYER,
                            "Natural spawn requires rotation, but no player target is available in this dimension.");
                    return;
                }
            }
        }
        final SpawnSearchVolume structureVolume =
                attempt.physicalPreloadVolume != null
                        ? attempt.physicalPreloadVolume
                        : attempt.rotationCheck != null && !attempt.rotationCheck.isComplete()
                                ? attempt.rotationCheck.volume()
                                : SpawnVolumes.createStructureVolume(
                                        attempt.selectedStructure, attempt.foundPlacement);

        if (attempt.searchChunkLoader == null) {

            attempt.searchChunkLoader =
                    new NaturalSpawnChunkLoader(
                            attempt.level,
                            structureVolume,
                            NaturalSpawnLimits.search_chunk_margin(),
                            attempt.initialPoint,
                            attempt.chunkResources);

        } else {

            attempt.searchChunkLoader.setTargetVolume(structureVolume);
        }

        attempt.searchChunkLoader.timing(attempt.timing());
        int chunkAllowance = NPShipsNaturalSpawnManager.chunkAllowance(attempt.server);
        NaturalSpawnChunkLoader.StepResult result =
                new NaturalSpawnChunkLoader.StepResult(
                        attempt.searchChunkLoader.isComplete()
                                ? NaturalSpawnChunkLoader.Status.COMPLETE
                                : NaturalSpawnChunkLoader.Status.RUNNING,
                        0,
                        0);
        if (chunkAllowance > 0 && attempt.canContinueWork()) {
            result = attempt.searchChunkLoader.step(chunkAllowance);
            NPShipsNaturalSpawnManager.chargeOperations(
                    attempt.server, 0, 0, result.chunksProcessed());
            if (result.chunksProcessed() > 0) attempt.progressed();
        }
        if (result.status() != NaturalSpawnChunkLoader.Status.FAILED
                && attempt.rotationCheck != null
                && !attempt.rotationCheck.isComplete()) {
            attempt.rotationCheck.timing(attempt.timing());
            int rotationAllowance =
                    Math.min(
                            NPShipsNaturalSpawnManager.searchAllowance(attempt.server),
                            NPShipsNaturalSpawnManager.scanAllowance(attempt.server));
            if (rotationAllowance <= 0 || !attempt.canContinueWork()) return;
            boolean complete = attempt.rotationCheck.step(rotationAllowance);
            int operations = attempt.rotationCheck.operationsLastStep();
            NPShipsNaturalSpawnManager.chargeOperations(attempt.server, operations, operations, 0);
            if (operations > 0) attempt.progressed();
            if (!complete) {
                var timing = attempt.timing();
                if (timing != null && !attempt.searchChunkLoader.isComplete())
                    timing.gap(SpawnTimingProfiler.Gap.CHUNKS);
                return;
            }
            if (!attempt.rotationCheck.allowed()
                    && attempt.bypassConditions
                    && attempt.selectedStructure
                            .schematic()
                            .config()
                            .placement()
                            .requireRotationSpace()) attempt.bypass("rotation");
            if (!attempt.rotationCheck.allowed() && placement.requireRotationSpace()) {
                attempt.rejectRotationPlacement();
                return;
            }
        }
        if (result.status() != NaturalSpawnChunkLoader.Status.FAILED
                && (attempt.rotationCheck == null || attempt.rotationCheck.isComplete())) {
            if (!attempt.rotationResolved) {
                boolean rotate =
                        attempt.rotationCheck != null
                                && attempt.rotationCheck.allowed()
                                && facingTarget != null;
                if (!placement.disableAutoRotation()
                        && placement.requireRotationSpace()
                        && !rotate) {
                    attempt.rejectRotationPlacement();
                    return;
                }
                attempt.rotationSelected = rotate;
                attempt.rotationRadians =
                        rotate
                                ? NaturalSpawnRotationCheck.yawRadians(
                                        NaturalSpawnRotationCheck.pivot(
                                                attempt.foundPlacement.origin(),
                                                attempt.selectedStructure.size()),
                                        facingTarget,
                                        attempt.rotationRandomUnit,
                                        placement.rotationSpreadDegrees())
                                : 0;
                attempt.physicalPreloadVolume =
                        retainedConstructionVolume(
                                attempt.selectedStructure,
                                attempt.foundPlacement.origin(),
                                attempt.rotationRadians);
                attempt.rotationResolved = true;
                attempt.progressed();
            }
            // Reuse ready chunks and the same leases; retain the final physical footprint until
            // construction commits. Native force-loaded bodies never discover a missing edge.
            attempt.searchChunkLoader.setTargetVolume(attempt.physicalPreloadVolume);
            result =
                    new NaturalSpawnChunkLoader.StepResult(
                            attempt.searchChunkLoader.isComplete()
                                    ? NaturalSpawnChunkLoader.Status.COMPLETE
                                    : NaturalSpawnChunkLoader.Status.RUNNING,
                            result.chunksProcessed(),
                            result.chunksLoaded());
        }
        switch (result.status()) {
            case RUNNING -> {
                var timing = attempt.timing();
                if (timing != null && !attempt.searchChunkLoader.isComplete())
                    timing.gap(SpawnTimingProfiler.Gap.CHUNKS);
                // Shrinking after cached clearance can leave all placement chunks ready.
                if (attempt.searchChunkLoader.isComplete())
                    attempt.phase = NaturalSpawnAttempt.Phase.SPAWN;
            }

            case COMPLETE -> {
                attempt.phase = NaturalSpawnAttempt.Phase.SPAWN;
            }

            case FAILED -> {
                attempt.finishFailed(
                        NaturalSpawnOutcome.PREPARATION_FAILED,
                        "Natural spawn could not preload " + "the structure chunks.");
            }
        }
    }

    static SpawnSearchVolume retainedConstructionVolume(
            NaturalSpawnStructureCandidate candidate, BlockPos origin, double yaw) {
        var physical = NaturalSpawnRotationCheck.physicalVolume(origin, candidate.size(), yaw);
        var guard =
                com.eynny.npships.ship.SpawnRegionGuard.requiredVolume(
                        candidate.config(), origin, candidate.size(), yaw);
        return new SpawnSearchVolume(
                Math.min(physical.minX(), guard.minX()),
                Math.min(physical.minY(), guard.minY()),
                Math.min(physical.minZ(), guard.minZ()),
                Math.max(physical.maxX(), guard.maxX()),
                Math.max(physical.maxY(), guard.maxY()),
                Math.max(physical.maxZ(), guard.maxZ()));
    }

    private java.util.Set<java.util.UUID> ownedPhysicalIds() {
        return attempt.preparedSpawn == null
                ? java.util.Set.of()
                : attempt.preparedSpawn.ownedPhysicalIds();
    }

    void processSpawn() {
        if (attempt.isTerminating()) return;

        if (attempt.selectedStructure == null
                || attempt.foundPlacement == null
                || attempt.level == null) {

            attempt.finishFailed();
            return;
        }

        boolean rotate;
        var timing = attempt.timing();
        long safetyToken = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.SAFETY);
        try {
            final int onlinePlayers =
                    NaturalSpawnAttemptContext.countEligiblePlayers(attempt.server);
            if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC && onlinePlayers == 0) {
                attempt.finishFailed(
                        NaturalSpawnOutcome.NO_ELIGIBLE_PLAYER,
                        "Natural spawn cancelled: no non-spectator players remain.");
                return;
            }
            // Device preparation may yield for longer than the native ticket timeout.
            // Keep the preloaded chunks owned until placement or terminal cleanup.
            if (attempt.searchChunkLoader != null) attempt.searchChunkLoader.refreshTickets();
            // Physical structures can move during chunk preloading.
            if (!attempt.validateFoundPlacement()) {
                return;
            }

            rotate =
                    attempt.rotationResolved
                            ? attempt.rotationSelected
                            : attempt.rotationCheck != null && attempt.rotationCheck.allowed();
            final var facingTarget = rotate ? attempt.rotationTarget() : null;
            if (!attempt.rotationResolved) rotate = rotate && facingTarget != null;
            if (!attempt.exactOrigin
                    && !attempt.selectedStructure.config().placement().disableAutoRotation()
                    && attempt.selectedStructure.config().placement().requireRotationSpace()
                    && !rotate) {
                attempt.rejectRotationPlacement();
                return;
            }
            if (rotate && !attempt.rotationResolved) {
                attempt.rotationRadians =
                        NaturalSpawnRotationCheck.yawRadians(
                                NaturalSpawnRotationCheck.pivot(
                                        attempt.foundPlacement.origin(),
                                        attempt.selectedStructure.size()),
                                facingTarget,
                                attempt.rotationRandomUnit,
                                attempt.selectedStructure
                                        .config()
                                        .placement()
                                        .rotationSpreadDegrees());
            }
            if (rotate
                    && attempt.preparedSpawn == null
                    && attempt.selectedStructure.config().placement().avoidPhysicalStructures()) {
                if (attempt.rotationPreflight == null
                        || !attempt.rotationPreflight.matches(
                                attempt.level,
                                attempt.foundPlacement.origin(),
                                attempt.selectedStructure.size(),
                                attempt.rotationRadians))
                    attempt.rotationPreflight =
                            new NaturalSpawnPhysicalValidator.Scan(
                                    attempt.level,
                                    attempt.foundPlacement.origin(),
                                    attempt.selectedStructure.size(),
                                    attempt.rotationRadians);
                long before = attempt.rotationPreflight.progress();
                boolean done = attempt.rotationPreflight.step(attempt::canContinueWork);
                if (attempt.rotationPreflight.progress() != before) attempt.progressed();
                if (!done) return;
                var overlap = attempt.rotationPreflight.result();
                if (overlap != null) {
                    if (attempt.selectedStructure.config().placement().requireRotationSpace()) {
                        attempt.rejectRotationPlacement();
                        return;
                    }
                    // Preserve optional-rotation fallback before a prepared/native owner exists.
                    attempt.rotationSelected = false;
                    attempt.rotationRadians = 0;
                    attempt.rotationResolved = true;
                    attempt.physicalPreloadVolume =
                            retainedConstructionVolume(
                                    attempt.selectedStructure, attempt.foundPlacement.origin(), 0);
                    attempt.phase = NaturalSpawnAttempt.Phase.PRELOAD_CHUNKS;
                    attempt.progressed();
                    return;
                }
            }
            if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                    && (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled()
                            || !com.eynny.npships.config.NPShipsRuntimeConfig.addonEnabled(
                                    attempt.selectedStructure.structureId().getNamespace())
                            || !com.eynny.npships.config.NPShipsRuntimeConfig.structureEnabled(
                                    attempt.selectedStructure.commandName()))
                    && !attempt.bypass("disabled")) {
                attempt.finishFailed(
                        NaturalSpawnOutcome.DISABLED,
                        "Automatic spawning disabled before placement.");
                return;
            }
            if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC) {
                var proximity =
                        NaturalSpawnPlayerProximity.find(
                                attempt.level,
                                attempt.foundPlacement.origin(),
                                attempt.preparedSpawn != null
                                        ? attempt.preparedSpawn.size()
                                        : attempt.selectedStructure.size(),
                                rotate ? attempt.rotationRadians : 0);
                if (proximity != null && !attempt.bypass("player_distance")) {
                    if (attempt.preparedSpawn != null)
                        attempt.finishFailed(
                                NaturalSpawnOutcome.PLAYER_TOO_CLOSE, proximity.message());
                    else rejectPlayerPlacement(proximity);
                    return;
                }
            }

        } finally {
            if (timing != null) timing.end(safetyToken);
        }
        final Ship ship;
        boolean mutatingPlacement = false;
        net.minecraft.core.Vec3i placedSize = null;

        try {

            safetyToken = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.SAFETY);
            try {
                if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                        && com.eynny.npships.config.NPShipsRuntimeConfig
                                        .maximumPlayerStructureHorizontalDistance()
                                != 0) {
                    // Catalogue metadata alone can be supplied independently of the pinned
                    // template.
                    // Only a matching prepared template or its immutable native NBT envelope is
                    // proven.
                    var snapshot = attempt.selectedStructure.schematic().snapshot();
                    var relevanceSize =
                            attempt.preparedSpawn != null
                                            && attempt.preparedSpawn.matches(
                                                    attempt.level,
                                                    attempt.selectedStructure,
                                                    attempt.foundPlacement.origin(),
                                                    attempt.segmentPlan)
                                    ? attempt.preparedSpawn.size()
                                    : snapshot != null ? snapshot.size() : null;
                    if (relevanceSize != null) {
                        var relevance =
                                NaturalSpawnPlayerRelevance.find(
                                        attempt.level,
                                        attempt.foundPlacement.origin(),
                                        relevanceSize,
                                        rotate ? attempt.rotationRadians : 0);
                        if (relevance != null && !attempt.bypass("player_relevance")) {
                            attempt.finishFailed(
                                    NaturalSpawnOutcome.NO_RELEVANT_PLAYER,
                                    relevance.message()
                                            + " Origin: "
                                            + attempt.foundPlacement.origin());
                            return;
                        }
                    }
                }

            } finally {
                if (timing != null) timing.end(safetyToken);
            }
            if (attempt.preparedSpawn != null
                    && !attempt.preparedSpawn.matches(
                            attempt.level,
                            attempt.selectedStructure,
                            attempt.foundPlacement.origin(),
                            attempt.segmentPlan)) {
                attempt.finishFailed(
                        NaturalSpawnOutcome.PREPARATION_FAILED,
                        "Prepared spawn no longer matches its accepted source and placement.");
                return;
            }
            if (attempt.preparedSpawn == null) {
                if (!attempt.canContinueWork()) return;
                attempt.rotationSelected = rotate;
                if (!rotate) attempt.rotationRadians = 0;
                attempt.preparedSpawn =
                        ShipSpawner.prepareNatural(
                                attempt.level,
                                attempt.selectedStructure,
                                attempt.foundPlacement.origin(),
                                attempt.segmentPlan,
                                attempt.timing(),
                                attempt.random);
                if (timing != null) timing.gap(SpawnTimingProfiler.Gap.PREPARATION);
                return;
            }
            safetyToken = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.SAFETY);
            try {
                if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC) {
                    var proximity =
                            NaturalSpawnPlayerProximity.find(
                                    attempt.level,
                                    attempt.foundPlacement.origin(),
                                    attempt.preparedSpawn.size(),
                                    rotate ? attempt.rotationRadians : 0);
                    if (proximity != null && !attempt.bypass("player_distance")) {
                        attempt.finishFailed(
                                NaturalSpawnOutcome.PLAYER_TOO_CLOSE, proximity.message());
                        return;
                    }
                    var relevance =
                            NaturalSpawnPlayerRelevance.find(
                                    attempt.level,
                                    attempt.foundPlacement.origin(),
                                    attempt.preparedSpawn.size(),
                                    rotate ? attempt.rotationRadians : 0);
                    if (relevance != null && !attempt.bypass("player_relevance")) {
                        attempt.finishFailed(
                                NaturalSpawnOutcome.NO_RELEVANT_PLAYER,
                                relevance.message()
                                        + " Origin: "
                                        + attempt.foundPlacement.origin());
                        return;
                    }
                }

            } finally {
                if (timing != null) timing.end(safetyToken);
            }
            var prepared = attempt.preparedSpawn;
            placedSize = prepared.size();
            mutatingPlacement = attempt.notificationPlacementStarted;
            var notifications =
                    NPShipsEventBridge.enter(
                            attempt.notificationOperation(),
                            () -> !attempt.isTerminating(),
                            () -> attempt.notificationPlacementStarted = true);
            try (notifications) {
                NPShipsEventBridge.acceptedScope();
                if (!prepared.stepConstruction(attempt.rotationRadians, attempt::canContinueWork)) {
                    if (timing != null) timing.gap(SpawnTimingProfiler.Gap.PREPARATION);
                    return;
                }
                if (!prepared.constructionFinished())
                    throw new IllegalStateException("Construction returned before completion");
                ship = prepared.result();
            } finally {
                attempt.notificationPlacementStarted |= notifications.placementStarted();
            }

        } catch (Exception exception) {

            attempt.finishFailed(
                    mutatingPlacement || attempt.notificationPlacementStarted
                            ? NaturalSpawnOutcome.PLACEMENT_FAILED
                            : NaturalSpawnOutcome.PREPARATION_FAILED,
                    "Natural spawn validation passed, "
                            + "but physical structure creation "
                            + "failed: "
                            + attempt.safeMessage(exception));

            return;
        }

        // A provider can cancel/collect this job while its current portion unwinds.
        // Its terminal outcome/economy remains final; the prepared owner handles owned cleanup.
        if (attempt.isTerminating()) return;

        // Physical output is produced by the direct continuation, independently of authored
        // markers.
        rotate = rotate && ship != null;
        if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                && attempt.automaticEffects.recordRarity()) {
            attempt.rarityOwner =
                    NaturalSpawnRarity.successOwner(
                            attempt.level,
                            attempt.foundPlacement.origin(),
                            placedSize,
                            rotate ? attempt.rotationRadians : 0,
                            attempt.selectedPlayer);
        }

        if (attempt.mode == NaturalSpawnAttemptMode.MANUAL
                && attempt.commandSource != null
                && !attempt.exactOrigin) {

            final BlockPos spawnedOrigin = attempt.foundPlacement.origin();

            attempt.commandSource.sendSuccess(
                    () ->
                            Component.literal(
                                    "Successfully spawned natural "
                                            + "structure "
                                            + attempt.selectedStructure.commandName()
                                            + " at "
                                            + spawnedOrigin
                                            + "."),
                    true);
        }

        if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                && attempt.automaticEffects.recordCooldown()
                && attempt.selectedPlayer != null) {
            NPShipsNaturalSpawnData.get(attempt.level)
                    .recordSuccessfulSpawn(
                            attempt.selectedPlayer.getUUID(),
                            attempt.server.overworld().getGameTime(),
                            NPShipsNaturalSpawnManager.successfulCooldown(
                                    NaturalSpawnAttemptContext.countEligiblePlayers(
                                            attempt.server)));
        }
        if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                && attempt.automaticEffects.recordRarity()
                && attempt.rarityOwner != null
                && attempt.selectedStructure.raritySelection() != null) {
            var parameters =
                    attempt.rarityParameters == null
                            ? com.eynny.npships.config.NPShipsRuntimeConfig.rarity()
                            : attempt.rarityParameters;
            long now = attempt.server.overworld().getGameTime();
            NPShipsNaturalSpawnData.get(attempt.level)
                    .recordRaritySuccess(
                            new RarityHistory.Event(
                                    attempt.successEventId,
                                    attempt.rarityOwner,
                                    attempt.level.dimension().location().toString(),
                                    now,
                                    attempt.selectedStructure.raritySelection().authorProbability(),
                                    parameters.halfLifeTicks()),
                            now,
                            parameters.maximumPressure());
        }
        attempt.spawnedShip = ship;
        attempt.complete();
    }
}
