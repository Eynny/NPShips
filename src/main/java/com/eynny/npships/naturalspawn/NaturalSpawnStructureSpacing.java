package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.lifecycle.NPShipsLifecycleQuery;
import com.eynny.npships.lifecycle.NPShipsSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Reject crowded automatic search references before choosing or preparing a schematic. */
public final class NaturalSpawnStructureSpacing {
    private NaturalSpawnStructureSpacing() {}

    static double radius(double multiplier, int simulationChunks, int minimumChunks) {
        return multiplier * 16.0 * Math.max(minimumChunks, simulationChunks);
    }

    static double distanceSquared(BlockPos point, AABB bounds) {
        double dx = Math.max(0, Math.max(bounds.minX - point.getX(), point.getX() - bounds.maxX));
        double dy = Math.max(0, Math.max(bounds.minY - point.getY(), point.getY() - bounds.maxY));
        double dz = Math.max(0, Math.max(bounds.minZ - point.getZ(), point.getZ() - bounds.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean blocked(ServerLevel level, BlockPos point) {
        double radius =
                radius(
                        NPShipsRuntimeConfig.structureSpacingMultiplier(),
                        level.getServer().getPlayerList().getSimulationDistance(),
                        NaturalSpawnLimits.min_effective_simulation_distance_chunks());
        if (radius <= 0) return false;
        var data = NPShipsSavedData.peek(level);
        if (data == null) return false;
        double squared = radius * radius;
        for (var uuid : data.getTrackedShips().keySet()) {
            var bounds = NPShipsLifecycleQuery.bounds(level, uuid);
            if (bounds != null && distanceSquared(point, bounds) < squared) return true;
        }
        return false;
    }
}
