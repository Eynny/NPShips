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
            if (proximity != null) {
                // Once preparation exists, rejection is terminal: never continue with its old
                // template/devices or synchronously restart search at the final boundary.
                if (attempt.preparedSpawn != null)
                    attempt.finishFailed(NaturalSpawnOutcome.PLAYER_TOO_CLOSE, proximity.message());
                else rejectPlayerPlacement(proximity);
                return false;
            }
        }

        if (!attempt.selectedStructure.config().placement().avoidPhysicalStructures()) {

            return true;
        }

        final NaturalSpawnPhysicalValidator.Overlap overlap =
                NaturalSpawnPhysicalValidator.findOverlapDetails(
                        attempt.level,
                        attempt.foundPlacement.origin(),
                        attempt.selectedStructure.size());

        if (overlap == null) {
            return true;
        }

        if (attempt.placementSearch != null
                && attempt.physicalOverlapRetries
                        < NaturalSpawnLimits.max_physical_overlap_retries()) {
            attempt.physicalOverlapRetries++;
            attempt.placementSearch.excludePhysicalBounds(overlap.bounds());
            attempt.foundPlacement = null;
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
        attempt.rotationCheck = null;
        attempt.phase =
                attempt.fallbackSearch
                        ? NaturalSpawnAttempt.Phase.SEARCH_FALLBACK
                        : NaturalSpawnAttempt.Phase.SEARCH_PRIMARY;
        if (attempt.searchChunkLoader != null && attempt.activeSearchVolume != null)
            attempt.searchChunkLoader.useRequestedChunks();
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
                attempt.rotationRandomUnit = attempt.level.getRandom().nextDouble();
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
        final SpawnSearchVolume structureVolume =
                attempt.rotationCheck != null && !attempt.rotationCheck.isComplete()
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
        final NaturalSpawnChunkLoader.StepResult result =
                attempt.searchChunkLoader.step(NaturalSpawnLimits.max_chunk_operations_per_tick());

        if (result.status() != NaturalSpawnChunkLoader.Status.FAILED
                && attempt.rotationCheck != null) {
            attempt.rotationCheck.timing(attempt.timing());
            if (!attempt.rotationCheck.step(
                    Math.min(
                            NaturalSpawnLimits.max_search_operations_per_tick(),
                            NaturalSpawnLimits.max_scan_checks_per_tick()))) {
                var timing = attempt.timing();
                if (timing != null && !attempt.searchChunkLoader.isComplete())
                    timing.gap(SpawnTimingProfiler.Gap.CHUNKS);
                return;
            }
            if (!attempt.rotationCheck.allowed() && placement.requireRotationSpace()) {
                attempt.rejectRotationPlacement();
                return;
            }
        }
        // Clearance has been read into the snapshot. Keep only the template's chunks
        // for placement; Sable will own loading around the assembled/rotated body.
        if (result.status() != NaturalSpawnChunkLoader.Status.FAILED
                && (attempt.rotationCheck == null || attempt.rotationCheck.isComplete())) {
            attempt.searchChunkLoader.setTargetVolume(
                    SpawnVolumes.createStructureVolume(
                            attempt.selectedStructure, attempt.foundPlacement));
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

    void processSpawn() {
        if (attempt.isFinished()) return;

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

            rotate = attempt.rotationCheck != null && attempt.rotationCheck.allowed();
            if (rotate) {
                final var v = attempt.rotationCheck.volume();
                rotate =
                        NaturalSpawnPhysicalValidator.findOverlapDetails(
                                        attempt.level,
                                        new BlockPos(v.minX(), v.minY(), v.minZ()),
                                        new BlockPos(v.sizeX(), v.sizeY(), v.sizeZ()))
                                == null;
            }
            final var facingTarget = rotate ? attempt.rotationTarget() : null;
            rotate = rotate && facingTarget != null;
            if (!attempt.selectedStructure.config().placement().disableAutoRotation()
                    && attempt.selectedStructure.config().placement().requireRotationSpace()
                    && !rotate) {
                attempt.rejectRotationPlacement();
                return;
            }
            if (rotate) {
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
            if (attempt.mode == NaturalSpawnAttemptMode.AUTOMATIC
                    && (!com.eynny.npships.config.NPShipsRuntimeConfig.enabled()
                            || !com.eynny.npships.config.NPShipsRuntimeConfig.addonEnabled(
                                    attempt.selectedStructure.structureId().getNamespace())
                            || !com.eynny.npships.config.NPShipsRuntimeConfig.structureEnabled(
                                    attempt.selectedStructure.commandName()))) {
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
                if (proximity != null) {
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
                        if (relevance != null) {
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
            if (attempt.preparedSpawn == null
                    || !attempt.preparedSpawn.matches(
                            attempt.level,
                            attempt.selectedStructure,
                            attempt.foundPlacement.origin(),
                            attempt.segmentPlan)) {
                if (attempt.preparedSpawn != null) attempt.preparedSpawn.close();
                attempt.preparedSpawn =
                        ShipSpawner.prepareNatural(
                                attempt.level,
                                attempt.selectedStructure,
                                attempt.foundPlacement.origin(),
                                attempt.segmentPlan,
                                attempt.timing());
                if (timing != null) timing.gap(SpawnTimingProfiler.Gap.PREPARATION);
                return;
            }
            // Re-check live safety, enabled flags and moving obstacles on the next tick.
            // Placement and native assembly stay atomic, with all links restored before yielding.
            if (!attempt.preparedSpawn.prepareDevices()) {
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
                    if (proximity != null) {
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
                    if (relevance != null) {
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
            attempt.preparedSpawn = null;
            placedSize = prepared.size();
            mutatingPlacement = true;
            var notifications =
                    NPShipsEventBridge.enter(
                            attempt.notificationOperation(),
                            () -> !attempt.isFinished(),
                            () -> attempt.notificationPlacementStarted = true);
            try (notifications) {
                NPShipsEventBridge.acceptedScope();
                ship = prepared.spawn(rotate ? attempt.rotationRadians : 0);
            } finally {
                attempt.notificationPlacementStarted |= notifications.placementStarted();
            }

        } catch (Exception exception) {

            attempt.finishFailed(
                    mutatingPlacement
                            ? NaturalSpawnOutcome.PLACEMENT_FAILED
                            : NaturalSpawnOutcome.PREPARATION_FAILED,
                    "Natural spawn validation passed, "
                            + "but physical structure creation "
                            + "failed: "
                            + attempt.safeMessage(exception));

            return;
        }

        // A synchronous provider can cancel/collect this job while native placement unwinds.
        // World effects are not rolled back, but its terminal outcome/economy must stay final.
        if (attempt.isFinished()) return;

        // A template without assembly points is a valid static placement. Exceptions above
        // still distinguish actual placement/assembly failures from this result.
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

        attempt.releaseSearchLoader();

        if (attempt.mode == NaturalSpawnAttemptMode.MANUAL && attempt.commandSource != null) {

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
