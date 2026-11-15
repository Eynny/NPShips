package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Reads only requested raw ranges. One snapshot is retained across all candidates. */
final class SpawnColumnReader {
    private record Request(int x, int z, int minY, int maxY) {}

    private final ServerLevel level;
    private final SpawnSpatialSnapshot snapshot;
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final Set<Request> queued = new HashSet<>();
    private Request activeRequest;
    private int nextY;
    SpawnTimingProfiler timing;

    SpawnColumnReader(ServerLevel level, SpawnSpatialSnapshot snapshot) {
        this.level = level;
        this.snapshot = snapshot;
        snapshot.setRequester(this::request);
    }

    private void request(int x, int z, int minY, int maxY) {
        if (!snapshot.contains(new BlockPos(x, minY, z))
                || !snapshot.contains(new BlockPos(x, maxY, z)))
            throw new IllegalArgumentException("Requested column lies outside snapshot");
        if (snapshot.isColumnRangeComplete(x, z, minY, maxY)) return;
        var request = new Request(x, z, minY, maxY);
        if (queued.add(request)) pending.addLast(request);
    }

    void requestChunks(NaturalSpawnChunkLoader loader) {
        for (var request : pending)
            if (request.maxY >= level.getMinBuildHeight()
                    && request.minY < level.getMaxBuildHeight())
                loader.requestChunk(request.x >> 4, request.z >> 4);
    }

    SpawnAreaScanner.StepResult step(int budget) {
        int used = 0;
        LevelChunkSection lastSection = null;
        BlockState uniform = null;
        while (!pending.isEmpty() && used < budget) {
            var request = pending.getFirst();
            if (request != activeRequest) {
                activeRequest = request;
                nextY = request.minY;
            }
            // Observed bits never clear. Resume after the last examined run while still
            // skipping samples populated ahead by overlapping requests or yaw checks.
            int y = snapshot.firstUnscannedY(request.x, request.z, nextY, request.maxY);
            if (y > request.maxY) {
                snapshot.markColumnRangeComplete(request.x, request.z, request.minY, request.maxY);
                queued.remove(pending.removeFirst());
                activeRequest = null;
                continue;
            }
            var pos = new BlockPos(request.x, y, request.z);
            if (level.isOutsideBuildHeight(pos)) {
                snapshot.put(pos, Blocks.VOID_AIR.defaultBlockState());
            } else {
                var chunk = level.getChunkSource().getChunkNow(request.x >> 4, request.z >> 4);
                if (chunk == null)
                    return new SpawnAreaScanner.StepResult(
                            SpawnAreaScanner.Status.WAITING_FOR_UNLOADED_CHUNK, used);
                var section = chunk.getSection(chunk.getSectionIndex(y));
                BlockState sampled = null;
                if (section != lastSection) {
                    lastSection = section;
                    sampled = section.getBlockState(request.x & 15, y & 15, request.z & 15);
                    if (timing != null) timing.nativeRead();
                    BlockState first = sampled;
                    uniform = section.getStates().maybeHas(state -> state != first) ? null : first;
                }
                if (uniform == null) {
                    if (sampled == null && timing != null) timing.nativeRead();
                    snapshot.put(
                            pos,
                            sampled != null
                                    ? sampled
                                    : section.getBlockState(
                                            request.x & 15, y & 15, request.z & 15));
                } else {
                    int end = Math.min(request.maxY, y | 15);
                    for (int fillY = y; fillY <= end; fillY++) {
                        // Rotation or an overlapping request may have populated any
                        // part of this run. Never read or overwrite those samples.
                        if (!snapshot.isScanned(request.x, fillY, request.z))
                            snapshot.put(new BlockPos(request.x, fillY, request.z), uniform);
                    }
                    y = end;
                }
            }
            nextY = y + 1;
            used++;
        }
        return new SpawnAreaScanner.StepResult(
                pending.isEmpty()
                        ? SpawnAreaScanner.Status.COMPLETE
                        : SpawnAreaScanner.Status.RUNNING,
                used);
    }
}
