package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Live server-thread checks; no world reads, player snapshots or persistent spatial index. */
final class NaturalSpawnPlayerProximity {
    private NaturalSpawnPlayerProximity() {}

    /** Continuous template cells occupy [origin, origin + size], not inclusive block centers. */
    record Volume(
            double x,
            double y,
            double z,
            double halfX,
            double halfY,
            double halfZ,
            double cosine,
            double sine) {
        static Volume at(BlockPos origin, Vec3i size, double yaw) {
            return new Volume(
                    origin.getX() + size.getX() / 2.0,
                    origin.getY() + size.getY() / 2.0,
                    origin.getZ() + size.getZ() / 2.0,
                    size.getX() / 2.0,
                    size.getY() / 2.0,
                    size.getZ() / 2.0,
                    Math.cos(yaw),
                    Math.sin(yaw));
        }

        double distanceSquared(Vec3 point) {
            double dx = point.x - x, dz = point.z - z;
            // Inverse of ShipRotation's JOML Y rotation about the template center.
            double localX = cosine * dx - sine * dz;
            double localZ = sine * dx + cosine * dz;
            double a = Math.max(0, Math.abs(localX) - halfX);
            double b = Math.max(0, Math.abs(point.y - y) - halfY);
            double c = Math.max(0, Math.abs(localZ) - halfZ);
            return a * a + b * b + c * c;
        }

        double unrotatedDistanceSquared(Vec3 point) {
            double a = Math.max(0, Math.abs(point.x - x) - halfX);
            double b = Math.max(0, Math.abs(point.y - y) - halfY);
            double c = Math.max(0, Math.abs(point.z - z) - halfZ);
            return a * a + b * b + c * c;
        }

        double horizontalDistanceSquared(Vec3 point) {
            double dx = point.x - x, dz = point.z - z;
            double a = Math.max(0, Math.abs(cosine * dx - sine * dz) - halfX);
            double c = Math.max(0, Math.abs(sine * dx + cosine * dz) - halfZ);
            return a * a + c * c;
        }

        double unrotatedHorizontalDistanceSquared(Vec3 point) {
            double a = Math.max(0, Math.abs(point.x - x) - halfX);
            double c = Math.max(0, Math.abs(point.z - z) - halfZ);
            return a * a + c * c;
        }
    }

    record Violation(UUID player, Vec3 position, double distanceSquared, double minimum) {
        String message() {
            return "Automatic natural spawn refused: player "
                    + player
                    + " at "
                    + position
                    + " is "
                    + Math.sqrt(distanceSquared)
                    + " blocks from the placement volume; minimum "
                    + minimum
                    + ".";
        }
    }

    @Nullable
    static Violation find(ServerLevel level, BlockPos origin, Vec3i size, double yaw) {
        double minimum = NPShipsRuntimeConfig.minimumPlayerStructureDistance();
        if (minimum <= 0) return null;
        var volume = Volume.at(origin, size, yaw);
        double squared = minimum * minimum;
        var players = level.players();
        for (int i = 0; i < players.size(); i++) {
            var player = players.get(i);
            if (player.serverLevel() != level
                    || !NaturalSpawnAttemptContext.isEligiblePlayer(player)) continue;
            var position = NaturalSpawnAttemptContext.worldPosition(player);
            // Placement first writes the unrotated template. Only assembled bodies then yaw;
            // static cells and templates without assembly points remain in the original volume.
            double distance =
                    Math.min(
                            volume.unrotatedDistanceSquared(position),
                            volume.distanceSquared(position));
            if (distance < squared)
                return new Violation(player.getUUID(), position, distance, minimum);
        }
        return null;
    }
}
