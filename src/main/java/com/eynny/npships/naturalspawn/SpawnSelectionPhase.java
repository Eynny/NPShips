package com.eynny.npships.naturalspawn;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Random;

/** One phase of the server-thread attempt; shared state and cleanup remain owned by the attempt. */
final class SpawnSelectionPhase {
    private final NaturalSpawnAttempt attempt;
    private java.util.List<ServerPlayer> remainingPlayers;
    private boolean sawCooldown, sawStructureSpacing;
    private int referenceAttempts;
    private NaturalSpawnRarity.Weighting weighting;
    private com.eynny.npships.authoring.StructureSegments.Choice segmentChoice;
    private NaturalSpawnStructureCandidate choosingStructure;
    private boolean initialStructureSelected;
    private static final int MAX_REFERENCE_ATTEMPTS = 16;

    SpawnSelectionPhase(NaturalSpawnAttempt attempt) {
        this.attempt = attempt;
    }

    void prepareSegmentPlan() {
        while (!prepareSegmentPlanStep(() -> true)) {}
    }

    private boolean prepareSegmentPlanStep(java.util.function.BooleanSupplier allowed) {
        if (attempt.plannedStructure == attempt.selectedStructure) return true;
        if (attempt.selectedStructure.raritySelection() != null) {
            attempt.segmentPlan = attempt.selectedStructure.raritySelection().plan();
            attempt.plannedStructure = attempt.selectedStructure;
            return true;
        }
        if (choosingStructure != attempt.selectedStructure) {
            choosingStructure = attempt.selectedStructure;
            segmentChoice =
                    com.eynny.npships.authoring.StructureSegments.beginChoice(
                            choosingStructure.config().segments(), attempt.random);
        }
        long before = segmentChoice.progress();
        boolean done = segmentChoice.step(allowed);
        if (segmentChoice.progress() != before) attempt.progressed();
        if (!done) return false;
        attempt.segmentPlan = segmentChoice.result();
        attempt.plannedStructure = choosingStructure;
        segmentChoice = null;
        choosingStructure = null;
        return true;
    }

    void close() {
        remainingPlayers = null;
        weighting = null;
        segmentChoice = null;
        choosingStructure = null;
    }

    void initialize() {
        if (attempt.exactOrigin) {
            if (attempt.level == null
                    || attempt.initialPoint == null
                    || attempt.selectedStructure == null
                    || !NPShipsNaturalSpawnManager.exactOriginAllowed(
                            attempt.level,
                            attempt.initialPoint,
                            attempt.selectedStructure.size())) {
                attempt.finishFailed(
                        NaturalSpawnOutcome.PREPARATION_FAILED,
                        "The complete exact schematic envelope must fit the world height and border.");
                return;
            }
            // Null means automatic segment choice in the existing composer; exact means base.
            attempt.segmentPlan =
                    new com.eynny.npships.authoring.StructureSegments.Plan(java.util.Map.of());
            attempt.foundPlacement = new NaturalSpawnPlacement(attempt.initialPoint);
            attempt.rotationResolved = true;
            attempt.rotationSelected = false;
            attempt.rotationRadians = 0;
            attempt.physicalPreloadVolume =
                    SpawnPlacementPhase.retainedConstructionVolume(
                            attempt.selectedStructure, attempt.initialPoint, 0);
            attempt.phase = NaturalSpawnAttempt.Phase.PRELOAD_CHUNKS;
            return;
        }

        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            if (attempt.level == null
                    || attempt.initialPoint == null
                    || attempt.selectedStructure == null) {

                attempt.finishFailed(
                        "Natural spawn internal error: " + "manual attempt is incomplete.");

                return;
            }

            attempt.startGameTime = attempt.level.getGameTime();

            if (!NaturalSpawnCandidatePool.allowsRestrictions(
                    attempt.level,
                    attempt.initialPoint,
                    attempt.selectedStructure.config().restrictions())) {

                attempt.finishFailed(
                        "Natural spawn rejected at "
                                + attempt.initialPoint
                                + ": structure restrictions "
                                + "are not satisfied.");

                return;
            }

            attempt.phase = NaturalSpawnAttempt.Phase.SELECT_INITIAL_STRUCTURE;

            return;
        }

        final ServerLevel overworld = attempt.server.overworld();

        if (attempt.automaticEffects.reschedule())
            NPShipsNaturalSpawnData.get(overworld)
                    .setLastSelectionGameTime(overworld.getGameTime());

        attempt.startGameTime = overworld.getGameTime();

        attempt.phase = NaturalSpawnAttempt.Phase.SELECT_PLAYER;
    }

    void selectPlayer() {
        // One finite roster snapshot, consumed without replacement before any C8 work.
        // Native player/reference RNG is sampled only when considering a candidate.
        if (remainingPlayers == null) {
            remainingPlayers = new java.util.ArrayList<>();
            for (var player : attempt.server.getPlayerList().getPlayers()) {
                if (NaturalSpawnAttemptContext.isEligiblePlayer(player)
                        && (attempt.operatorLevel == null
                                || player.serverLevel() == attempt.operatorLevel)) {
                    remainingPlayers.add(player);
                }
            }
        }
        while (!remainingPlayers.isEmpty()) {
            int index = attempt.random.nextInt(remainingPlayers.size());
            var player = remainingPlayers.get(index);
            int last = remainingPlayers.size() - 1;
            remainingPlayers.set(index, remainingPlayers.get(last));
            remainingPlayers.remove(last);
            if (!eligibleNow(player)) continue;
            referenceAttempts = 0;
            attempt.selectedPlayer = player;
            attempt.level = player.serverLevel();
            attempt.phase = NaturalSpawnAttempt.Phase.SELECT_INITIAL_POINT;
            return;
        }
        if (sawStructureSpacing) {
            attempt.finishFailed(
                    NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED,
                    "Natural spawn skipped: no search reference is far enough from tracked structures.");
            return;
        }
        attempt.finishFailed(
                sawCooldown
                        ? NaturalSpawnOutcome.RECENT_SPAWN_COOLDOWN
                        : NaturalSpawnOutcome.NO_ELIGIBLE_PLAYER,
                sawCooldown
                        ? "Natural spawn skipped: all available reference regions are on recent spawn cooldown."
                        : "Natural spawn skipped: there are no eligible players.");
    }

    private boolean eligibleNow(ServerPlayer player) {
        return attempt.server.getPlayerList().getPlayer(player.getUUID()) == player
                && NaturalSpawnAttemptContext.isEligiblePlayer(player)
                && (attempt.operatorLevel == null || player.serverLevel() == attempt.operatorLevel);
    }

    private void nextPlayer() {
        attempt.selectedPlayer = null;
        attempt.level = null;
        attempt.initialPoint = null;
        attempt.phase = NaturalSpawnAttempt.Phase.SELECT_PLAYER;
    }

    void selectInitialPoint() {
        if (attempt.selectedPlayer == null || attempt.level == null) {
            attempt.finishFailed("Natural spawn internal error: player/level is missing.");
            return;
        }
        if (!eligibleNow(attempt.selectedPlayer)
                || attempt.selectedPlayer.serverLevel() != attempt.level) {
            nextPlayer();
            return;
        }
        attempt.initialPoint =
                attempt.operatorPoint != null
                        ? attempt.operatorPoint
                        : NaturalSpawnAttemptContext.createAutomaticInitialPoint(
                                attempt.server,
                                attempt.level,
                                attempt.selectedPlayer,
                                attempt.random);
        if (attempt.initialPoint == null) {
            attempt.finishFailed(
                    NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED,
                    "Natural spawn could not determine an initial search point.");
            return;
        }
        referenceAttempts++;
        if (NaturalSpawnStructureSpacing.blocked(attempt.level, attempt.initialPoint)
                && !attempt.bypass("spacing")) {
            sawStructureSpacing = true;
            attempt.initialPoint = null;
            // One candidate per tick; a pinned OP reference cannot be silently moved.
            if (referenceAttempts >= MAX_REFERENCE_ATTEMPTS || attempt.operatorPoint != null)
                nextPlayer();
            return;
        }
        if (NPShipsNaturalSpawnData.get(attempt.level)
                .isSpawnAreaBlocked(
                        attempt.level,
                        attempt.initialPoint,
                        attempt.server.overworld().getGameTime())) {
            if (!attempt.relaxRecentCooldown) {
                sawCooldown = true;
                nextPlayer();
                return;
            }
            attempt.bypassedRecentCooldown = true;
            attempt.bypass("cooldown");
        }
        // Rejected references must not freeze another player's pressure owner or emit a search
        // marker.
        NaturalSpawnRarity.freeze(attempt);
        com.eynny.npships.diagnostics.SpawnVisualizationServer.initialPoint(
                attempt.server, attempt.level, attempt.initialPoint, attempt.opportunityPressure);
        attempt.phase = NaturalSpawnAttempt.Phase.FILTER_STRUCTURES;
    }

    void filterStructures() {

        if (attempt.level == null || attempt.initialPoint == null) {

            attempt.finishFailed();
            return;
        }

        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            if (attempt.selectedStructure == null) {

                attempt.finishFailed(
                        "Natural spawn internal error: " + "manual structure is missing.");

                return;
            }

            attempt.phase = NaturalSpawnAttempt.Phase.SELECT_INITIAL_STRUCTURE;

            return;
        }

        if (attempt.candidatePool == null)
            attempt.candidatePool =
                    NaturalSpawnCandidatePool.build(
                            attempt.server,
                            attempt.level,
                            attempt.initialPoint,
                            attempt.timing(),
                            attempt.operatorCandidate,
                            attempt.bypassConditions ? attempt : null);

        if (attempt.candidatePool.isEmpty()) {

            attempt.finishFailed(
                    NaturalSpawnOutcome.NO_ELIGIBLE_CANDIDATE,
                    "Natural spawn skipped: no structure is currently eligible.");

            return;
        }

        if (attempt.bypassConditions) {
            attempt.structureQueue =
                    new NaturalSpawnStructureQueue(
                            attempt.candidatePool.getAll(),
                            new Random(attempt.random.nextLong()),
                            NaturalSpawnLimits.max_structures_per_attempt());
            attempt.phase = NaturalSpawnAttempt.Phase.SELECT_INITIAL_STRUCTURE;
            return;
        }

        var timing = attempt.timing();
        long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.LOTTERY);
        try {
            // This may be invoked by controlled native fixtures; it is idempotent and never
            // re-samples
            // H.
            NaturalSpawnRarity.freeze(attempt);
            if (weighting == null)
                weighting =
                        NaturalSpawnRarity.beginWeights(
                                attempt.candidatePool.getAll(),
                                attempt.rarityParameters,
                                attempt.opportunityPressure,
                                attempt.random);
            long before = weighting.progress();
            boolean done = weighting.step(attempt::canContinueWork);
            if (weighting.progress() != before) attempt.progressed();
            if (!done) return;
            var weighted = weighting.result();
            weighting = null;
            if (weighted.candidates().isEmpty()) {
                attempt.finishFailed(
                        NaturalSpawnOutcome.NO_ELIGIBLE_CANDIDATE,
                        "Natural spawn has no valid nonempty native candidates (unavailable="
                                + weighted.unavailable()
                                + ", empty="
                                + weighted.empty()
                                + ").");
                return;
            }
            attempt.structureQueue =
                    new NaturalSpawnStructureQueue(
                            weighted.candidates(),
                            new Random(attempt.random.nextLong()),
                            NaturalSpawnLimits.max_structures_per_attempt());
            if (timing != null) timing.drawSlots(attempt.structureQueue.drawSlots());

        } finally {
            if (timing != null) timing.end(token);
        }

        attempt.phase = NaturalSpawnAttempt.Phase.SELECT_INITIAL_STRUCTURE;
    }

    void selectInitialStructure() {

        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {

            if (attempt.selectedStructure == null) {

                attempt.finishFailed();
                return;
            }

        } else {

            if (!initialStructureSelected && !attempt.startNextStructure()) {

                attempt.finishFailed(
                        NaturalSpawnOutcome.NO_ELIGIBLE_CANDIDATE,
                        "Natural spawn failed: " + "all available structures " + "were exhausted.");

                return;
            }
        }

        initialStructureSelected = true;
        if (!prepareSegmentPlanStep(attempt::canContinueWork)) return;
        if (attempt.mode != NaturalSpawnAttemptMode.AUTOMATIC) {
            var timing = attempt.timing();
            if (timing != null) timing.candidate(attempt.selectedStructure.commandName());
        }
        attempt.initializePrimarySearch();

        if (attempt.phase != NaturalSpawnAttempt.Phase.FAILED) {

            attempt.phase = NaturalSpawnAttempt.Phase.SEARCH_PRIMARY;
        }
    }

    boolean startNextStructure() {

        if (attempt.structureQueue == null) {
            return false;
        }

        final NaturalSpawnStructureCandidate next = attempt.structureQueue.pollRandom();

        if (next == null) {
            return false;
        }

        attempt.selectedStructure =
                attempt.bypassConditions ? CommandSpawnPolicy.unrestricted(next, attempt) : next;

        attempt.attemptedStructureCount++;
        var timing = attempt.timing();
        if (timing != null) timing.candidate(next.commandName());
        attempt.lastCandidateFailure = NaturalSpawnOutcome.ENVIRONMENT_EXHAUSTED;
        attempt.physicalOverlapRetries = 0;
        attempt.playerProximityRetries = 0;

        return true;
    }
}
