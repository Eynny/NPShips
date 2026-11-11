package com.eynny.npships.naturalspawn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class NaturalSpawnAttemptContext {

    private NaturalSpawnAttemptContext() {}

    public static net.minecraft.world.phys.Vec3 worldPosition(final ServerPlayer player) {
        return dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                player.serverLevel(), player.position());
    }

    public static int countEligiblePlayers(final MinecraftServer server) {
        return (int)
                server.getPlayerList().getPlayers().stream()
                        .filter(NaturalSpawnAttemptContext::isEligiblePlayer)
                        .count();
    }

    @Nullable
    public static ServerPlayer selectRandomEligiblePlayer(final MinecraftServer server) {
        return selectRandomEligiblePlayer(server, null);
    }

    static ServerPlayer selectRandomEligiblePlayer(MinecraftServer server, ServerLevel level) {
        final List<ServerPlayer> eligiblePlayers = new ArrayList<>();

        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {

            if (isEligiblePlayer(player) && (level == null || player.serverLevel() == level)) {
                eligiblePlayers.add(player);
            }
        }

        if (eligiblePlayers.isEmpty()) {
            return null;
        }

        return eligiblePlayers.get(server.overworld().getRandom().nextInt(eligiblePlayers.size()));
    }

    public static boolean isEligiblePlayer(final ServerPlayer player) {
        return switch (player.gameMode.getGameModeForPlayer()) {
            case SURVIVAL, ADVENTURE, CREATIVE -> true;

            default -> false;
        };
    }

    public static BlockPos createAutomaticInitialPoint(
            final MinecraftServer server, final ServerLevel level, final ServerPlayer player) {
        final BlockPos playerPosition = BlockPos.containing(worldPosition(player));
        final int playerX = playerPosition.getX();

        final int playerY = automaticReferenceY(level, playerPosition.getY(), 1, 0, 0);

        final int playerZ = playerPosition.getZ();

        final int simulationDistanceChunks =
                Math.max(
                        NaturalSpawnLimits.min_effective_simulation_distance_chunks(),
                        server.getPlayerList().getSimulationDistance());

        final int simulationDistanceBlocks = simulationDistanceChunks * 16;

        int maximumDistance =
                simulationDistanceBlocks
                        - NaturalSpawnLimits.player_simulation_distance_edge_margin();

        if (NaturalSpawnLimits.max_player_horizontal_distance() > 0) {
            maximumDistance =
                    Math.min(maximumDistance, NaturalSpawnLimits.max_player_horizontal_distance());
        }

        maximumDistance = Math.max(1, maximumDistance);

        final int minimumDistance =
                Math.min(NaturalSpawnLimits.min_player_horizontal_distance(), maximumDistance);

        final int borderBand = Math.max(1, Math.min(32, maximumDistance - minimumDistance + 1));

        final int borderStart = Math.max(minimumDistance, maximumDistance - borderBand + 1);

        final int distance =
                borderStart == maximumDistance
                        ? maximumDistance
                        : borderStart
                                + level.getRandom().nextInt(maximumDistance - borderStart + 1);

        final double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;

        int targetX = playerX + (int) Math.round(Math.cos(angle) * distance);

        int targetZ = playerZ + (int) Math.round(Math.sin(angle) * distance);

        if (targetX == playerX && targetZ == playerZ) {

            targetX += Math.max(1, distance);
        }

        return new BlockPos(targetX, playerY, targetZ);
    }

    /**
     * Build-height maximum is exclusive. Keep the complete template and its environment checks
     * inside the dimension, not merely its bottom block.
     */
    public static int automaticReferenceY(
            ServerLevel level,
            int requestedY,
            int structureHeight,
            int underDepth,
            int aboveDepth) {
        long min = (long) level.getMinBuildHeight() + underDepth;
        long max = (long) level.getMaxBuildHeight() - structureHeight - aboveDepth;
        if (min > max) {
            // An impossible candidate must exhaust normally and allow fallback.
            // There is no legal origin: only keep its search volume inside the world.
            min = level.getMinBuildHeight();
            max = level.getMaxBuildHeight() - 1L;
        }
        return (int) Math.max(min, Math.min(max, requestedY));
    }
}
