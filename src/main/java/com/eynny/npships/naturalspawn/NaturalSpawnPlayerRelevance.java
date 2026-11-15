package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/** Live horizontal witnesses, independent of the minimum 3D safety check. Server-thread only. */
final class NaturalSpawnPlayerRelevance {
    private NaturalSpawnPlayerRelevance() {}

    static double maximum(
            int configured,
            int simulationChunks,
            int floor,
            int margin,
            double minimum,
            int extra) {
        if (configured >= 0) return configured;
        double simulation = Math.max(1.0, 16.0 * Math.max(floor, simulationChunks) - margin);
        return Math.max(simulation, minimum + 16.0) + extra;
    }

    record Refusal(double maximum, boolean originalWitness, boolean yawedWitness) {
        String message() {
            return "Automatic natural spawn refused: no relevant player within horizontal distance "
                    + maximum
                    + " of "
                    + (!originalWitness && !yawedWitness
                            ? "either original or yawed placement footprint"
                            : !originalWitness
                                    ? "the original placement footprint"
                                    : "the yawed placement footprint")
                    + ".";
        }
    }

    @Nullable
    static Refusal find(ServerLevel level, BlockPos origin, Vec3i size, double yaw) {
        double maximum =
                maximum(
                        NPShipsRuntimeConfig.maximumPlayerStructureHorizontalDistance(),
                        level.getServer().getPlayerList().getSimulationDistance(),
                        NaturalSpawnLimits.min_effective_simulation_distance_chunks(),
                        NaturalSpawnLimits.player_simulation_distance_edge_margin(),
                        NPShipsRuntimeConfig.minimumPlayerStructureDistance(),
                        NPShipsRuntimeConfig.playerRelevanceExtraBlocks());
        if (maximum == 0) return null;
        var volume = NaturalSpawnPlayerProximity.Volume.at(origin, size, yaw);
        double squared = maximum * maximum;
        boolean original = false, yawed = yaw == 0;
        var players = level.players();
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(i);
            if (player.serverLevel() != level
                    || !NaturalSpawnAttemptContext.isEligiblePlayer(player)) continue;
            var position = NaturalSpawnAttemptContext.worldPosition(player);
            if (!original && volume.unrotatedHorizontalDistanceSquared(position) <= squared)
                original = true;
            if (!yawed && volume.horizontalDistanceSquared(position) <= squared) yawed = true;
            if (original && yawed) return null;
        }
        return new Refusal(maximum, original, yawed);
    }
}
