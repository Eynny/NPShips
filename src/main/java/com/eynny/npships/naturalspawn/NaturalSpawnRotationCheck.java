package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.SupportBlockDetector;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.naturalspawn.search.SpawnSearchVolume;
import com.eynny.npships.naturalspawn.search.SpawnSpatialSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Budgeted rotation clearance; optionally fills missing data from loaded chunks. */
public final class NaturalSpawnRotationCheck {
    private SpawnTimingProfiler timing;

    void timing(SpawnTimingProfiler timing) {
        this.timing = timing;
    }

    private final ServerLevel level;
    private final SpawnSpatialSnapshot snapshot;
    private final SpawnSearchVolume volume;
    private java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> extraSnapshot;
    private long index;
    private boolean complete;
    private boolean allowed;

    public NaturalSpawnRotationCheck(
            ServerLevel level, SpawnSpatialSnapshot snapshot, BlockPos origin, BlockPos size) {
        this.level = level;
        this.snapshot = snapshot;
        volume = sweptVolume(origin, size);
        complete =
                volume.minX() < level.getWorldBorder().getMinX()
                        || volume.maxX() + 1.0 > level.getWorldBorder().getMaxX()
                        || volume.minZ() < level.getWorldBorder().getMinZ()
                        || volume.maxZ() + 1.0 > level.getWorldBorder().getMaxZ();
    }

    /** Fills only missing loaded-world data; extraSnapshot is shared across placement retries. */
    public NaturalSpawnRotationCheck(
            ServerLevel level,
            SpawnSpatialSnapshot snapshot,
            BlockPos origin,
            BlockPos size,
            java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> extraSnapshot) {
        this(level, snapshot, origin, size);
        this.extraSnapshot = extraSnapshot;
    }

    public static Vec3 pivot(BlockPos origin, BlockPos size) {
        return new Vec3(
                origin.getX() + size.getX() / 2.0,
                origin.getY() + size.getY() / 2.0,
                origin.getZ() + size.getZ() / 2.0);
    }

    public static SpawnSearchVolume sweptVolume(BlockPos origin, BlockPos size) {
        Vec3 center = pivot(origin, size);
        double radius = Math.hypot(size.getX(), size.getZ()) / 2.0;
        return new SpawnSearchVolume(
                (int) Math.floor(center.x - radius),
                origin.getY(),
                (int) Math.floor(center.z - radius),
                (int) Math.ceil(center.x + radius) - 1,
                origin.getY() + size.getY() - 1,
                (int) Math.ceil(center.z + radius) - 1);
    }

    /** JOML rotation about Y: north (0,0,-1) rotates toward the target. */
    public static double yawRadians(Vec3 center, Vec3 target, double randomUnit) {
        return yawRadians(
                center, target, randomUnit, NaturalSpawnLimits.spawn_yaw_spread_degrees());
    }

    public static double yawRadians(
            Vec3 center, Vec3 target, double randomUnit, double spreadDegrees) {
        return Math.atan2(-(target.x - center.x), -(target.z - center.z))
                + Math.toRadians((randomUnit * 2.0 - 1.0) * spreadDegrees);
    }

    public boolean step(int budget) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.ROTATION);
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int used = 0; !complete && used < budget; used++, index++) {
                int y = (int) (index % volume.sizeY());
                long column = index / volume.sizeY();
                int z = (int) (column % volume.sizeZ());
                int x = (int) (column / volume.sizeZ());
                pos.set(volume.minX() + x, volume.minY() + y, volume.minZ() + z);
                var state = snapshot.getStateIfScanned(pos);
                if (state == null && extraSnapshot != null) {
                    state = extraSnapshot.get(pos.asLong());
                    if (state == null) {
                        if (!level.hasChunkAt(pos)) return false;
                        state = level.getBlockState(pos);
                        if (snapshot.contains(pos)) snapshot.put(pos, state);
                        else extraSnapshot.put(pos.asLong(), state);
                    }
                }
                if (state == null || SupportBlockDetector.isSupportBlock(level, pos, state)) {
                    complete = true;
                    break;
                }
                if (index + 1 == volume.blockCount()) {
                    complete = true;
                    allowed = true;
                }
            }
            return complete;

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean allowed() {
        return complete && allowed;
    }

    public SpawnSearchVolume volume() {
        return volume;
    }
}
