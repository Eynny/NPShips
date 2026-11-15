package com.eynny.npships.naturalspawn;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.config.RarityParameters;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Opportunity-owned pinned relative selection; live safety checks have separate owners. */
public final class NaturalSpawnRarity {
    private NaturalSpawnRarity() {}

    public record Selection(
            StructureSegments.Plan plan,
            int palette,
            long nonAirBlocks,
            double automaticProbability,
            double authorProbability,
            double effectiveLogRarity,
            int percentGroup,
            double logWeight) {}

    public record Weighted(
            List<NaturalSpawnStructureCandidate> candidates, int unavailable, int empty) {
        public Weighted {
            candidates = List.copyOf(candidates);
        }
    }

    public static double fieldRadius(int simulationChunks, int floor, int extra) {
        return 16.0 * Math.max(floor, simulationChunks) + extra;
    }

    public static double sample(ServerLevel level, BlockPos point, RarityParameters parameters) {
        var data = NPShipsNaturalSpawnData.get(level);
        long now = level.getServer().overworld().getGameTime();
        var history = data.rarityHistory();
        if (history.prune(now)) data.setDirty();
        return sampleField(level, Vec3.atLowerCornerOf(point), parameters, history, true);
    }

    /** Exact projected position, existing data only: no prune, dirty flag or observation time. */
    public static double readSample(ServerLevel level, Vec3 point, RarityParameters parameters) {
        var data = NPShipsNaturalSpawnData.peek(level);
        return data == null
                ? 0
                : sampleField(level, point, parameters, data.rarityHistory(), false);
    }

    private static double sampleField(
            ServerLevel level,
            Vec3 point,
            RarityParameters parameters,
            RarityHistory history,
            boolean observe) {
        long now = level.getServer().overworld().getGameTime();
        double radius =
                fieldRadius(
                        level.getServer().getPlayerList().getSimulationDistance(),
                        NaturalSpawnLimits.min_effective_simulation_distance_chunks(),
                        parameters.extraRadius());
        double squared = radius * radius, maximum = 0;
        String dimension = level.dimension().location().toString();
        var players = level.players();
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(i);
            if (player.serverLevel() != level
                    || !NaturalSpawnAttemptContext.isEligiblePlayer(player)) continue;
            var position = NaturalSpawnAttemptContext.worldPosition(player);
            if (position.distanceToSqr(point) <= squared)
                maximum =
                        Math.max(
                                maximum,
                                observe
                                        ? history.pressure(
                                                player.getUUID(),
                                                dimension,
                                                now,
                                                parameters.maximumPressure())
                                        : history.readPressure(
                                                player.getUUID(),
                                                dimension,
                                                now,
                                                parameters.maximumPressure()));
        }
        return maximum;
    }

    static void freeze(NaturalSpawnAttempt attempt) {
        if (attempt.pressureSampled) return;
        attempt.rarityParameters = NPShipsRuntimeConfig.rarity();
        attempt.opportunityPressure =
                sample(attempt.level, attempt.initialPoint, attempt.rarityParameters);
        attempt.pressureSampled = true;
    }

    /** Input already passed ordinary restrictions. No admission RNG or world reads here. */
    public static Weighted weightCandidates(
            List<NaturalSpawnStructureCandidate> pool,
            RarityParameters parameters,
            double pressure,
            RandomSource random) {
        var selection = beginWeights(pool, parameters, pressure, random);
        while (!selection.step(() -> true)) {}
        return selection.result();
    }

    static Weighting beginWeights(
            List<NaturalSpawnStructureCandidate> pool,
            RarityParameters parameters,
            double pressure,
            RandomSource random) {
        return new Weighting(pool, parameters, pressure, random);
    }

    /** Retains each candidate's plan and palette while yielding between choice primitives. */
    static final class Weighting {
        private final List<NaturalSpawnStructureCandidate> pool;
        private final RarityParameters parameters;
        private final double pressure;
        private final RandomSource random;
        private final int[] palettes;
        private final Weights weights;
        private final long[] counts;
        private final double[] automatic, author;
        private final StructureSegments.Plan[] plans;
        private final List<NaturalSpawnStructureCandidate> weighted = new ArrayList<>();
        private StructureSegments.Choice choice;
        private int index, phase, unavailable, empty;
        private long progress;
        private Weighted result;

        private Weighting(
                List<NaturalSpawnStructureCandidate> pool,
                RarityParameters parameters,
                double pressure,
                RandomSource random) {
            this.pool = pool;
            this.parameters = parameters;
            this.pressure = pressure;
            this.random = java.util.Objects.requireNonNull(random);
            int size = pool.size();
            palettes = new int[size];
            weights = new Weights(size);
            counts = new long[size];
            automatic = new double[size];
            author = new double[size];
            plans = new StructureSegments.Plan[size];
        }

        boolean step(java.util.function.BooleanSupplier allowed) {
            while (result == null) {
                if (choice != null) {
                    long before = choice.progress();
                    boolean done = choice.step(allowed);
                    progress += choice.progress() - before;
                    if (!done) return false;
                    plans[index] = choice.result();
                    choice = null;
                    phase = 1;
                }
                if (!allowed.getAsBoolean()) return false;
                progress++;
                if (phase < 2 && index == pool.size()) {
                    index = 0;
                    phase = 2;
                }
                if (phase == 2 && index == pool.size()) {
                    result = new Weighted(weighted, unavailable, empty);
                    return true;
                }
                var candidate = pool.get(index);
                if (phase == 0) {
                    var snapshot = candidate.schematic().snapshot();
                    var metadata = snapshot == null ? null : snapshot.rarityMetadata();
                    if (metadata == null || !metadata.available()) {
                        unavailable++;
                        index++;
                    } else
                        choice =
                                StructureSegments.beginChoice(
                                        candidate.config().segments(), random);
                } else if (phase == 1) {
                    var metadata = candidate.schematic().snapshot().rarityMetadata();
                    palettes[index] = random.nextInt(metadata.paletteCount());
                    counts[index] = metadata.count(palettes[index], plans[index]);
                    if (counts[index] == 0) empty++;
                    else {
                        automatic[index] = parameters.automaticProbability(counts[index]);
                        author[index] =
                                candidate.config().rarity().automatic()
                                        ? automatic[index]
                                        : candidate.config().rarity().manualProbability();
                        weights.add(
                                index,
                                parameters,
                                author[index],
                                pressure,
                                candidate.config().selectionWeight());
                    }
                    index++;
                    phase = 0;
                } else {
                    if (counts[index] != 0) {
                        double logWeight =
                                weights.logWeight(index, candidate.config().selectionWeight());
                        var selection =
                                new Selection(
                                        plans[index],
                                        palettes[index],
                                        counts[index],
                                        automatic[index],
                                        author[index],
                                        weights.effective[index],
                                        weights.groups[index],
                                        logWeight);
                        weighted.add(
                                new NaturalSpawnStructureCandidate(
                                        candidate.structureId(),
                                        candidate.schematic(),
                                        candidate.config(),
                                        candidate.size(),
                                        selection));
                    }
                    index++;
                }
            }
            return true;
        }

        long progress() {
            return progress;
        }

        Weighted result() {
            if (result == null) throw new IllegalStateException("Rarity selection is unfinished");
            return result;
        }
    }

    /** Pure counterpart for fixed candidate metadata; no composition, RNG or world access. */
    public static double[] selectionLogWeights(
            RarityParameters parameters, double pressure, double[] author, double[] preference) {
        if (author.length != preference.length)
            throw new IllegalArgumentException("Weight lengths differ");
        var weights = new Weights(author.length);
        for (int i = 0; i < author.length; i++)
            weights.add(i, parameters, author[i], pressure, preference[i]);
        var result = new double[author.length];
        for (int i = 0; i < result.length; i++) result[i] = weights.logWeight(i, preference[i]);
        return result;
    }

    private static final class Weights {
        final int[] counts = new int[101];
        final double[] rarity = new double[101], preference = new double[101], effective;
        final byte[] groups;

        Weights(int size) {
            effective = new double[size];
            groups = new byte[size];
            Arrays.fill(rarity, Double.NEGATIVE_INFINITY);
            Arrays.fill(preference, Double.NEGATIVE_INFINITY);
        }

        void add(
                int i, RarityParameters parameters, double author, double pressure, double weight) {
            effective[i] = parameters.effectiveLogRarity(author, pressure);
            groups[i] = (byte) parameters.percentGroup(author, pressure);
            int group = groups[i];
            counts[group]++;
            rarity[group] = logAdd(rarity[group], effective[i]);
            preference[group] = logAdd(preference[group], effective[i] + Math.log(weight));
        }

        double logWeight(int i, double weight) {
            int group = groups[i];
            // Mean(q) group mass; author preference only redistributes within the group.
            double correction = rarity[group] - preference[group];
            return counts[group] == 1
                    ? effective[i]
                    : effective[i] + (Math.log(weight) + correction) - Math.log(counts[group]);
        }
    }

    private static double logAdd(double sum, double value) {
        if (sum == Double.NEGATIVE_INFINITY) return value;
        double maximum = Math.max(sum, value);
        return maximum + Math.log1p(Math.exp(Math.min(sum, value) - maximum));
    }

    /** One NEW event owner. Does not retarget yaw, spawn ownership or the short cooldown. */
    static UUID successOwner(
            ServerLevel level, BlockPos origin, Vec3i size, double yaw, ServerPlayer reference) {
        var volume = NaturalSpawnPlayerProximity.Volume.at(origin, size, yaw);
        double maximum =
                NaturalSpawnPlayerRelevance.maximum(
                        NPShipsRuntimeConfig.maximumPlayerStructureHorizontalDistance(),
                        level.getServer().getPlayerList().getSimulationDistance(),
                        NaturalSpawnLimits.min_effective_simulation_distance_chunks(),
                        NaturalSpawnLimits.player_simulation_distance_edge_margin(),
                        NPShipsRuntimeConfig.minimumPlayerStructureDistance(),
                        NPShipsRuntimeConfig.playerRelevanceExtraBlocks());
        double limit = maximum == 0 ? Double.POSITIVE_INFINITY : maximum * maximum;
        UUID nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        var players = level.players();
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(i);
            if (player.serverLevel() != level
                    || !NaturalSpawnAttemptContext.isEligiblePlayer(player)) continue;
            double distance =
                    volume.horizontalDistanceSquared(
                            NaturalSpawnAttemptContext.worldPosition(player));
            if (distance > limit) continue;
            if (reference != null && player.getUUID().equals(reference.getUUID()))
                return player.getUUID();
            if (nearest == null
                    || distance < nearestDistance
                    || distance == nearestDistance && player.getUUID().compareTo(nearest) < 0) {
                nearest = player.getUUID();
                nearestDistance = distance;
            }
        }
        // Only relevant when admins disable maximum protection and every target-dimension
        // witness leaves. Preserve existing placement semantics and assign the original owner.
        return nearest != null ? nearest : reference == null ? null : reference.getUUID();
    }
}
