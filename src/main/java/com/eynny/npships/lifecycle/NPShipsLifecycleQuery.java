package com.eynny.npships.lifecycle;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Read-only query API for NPShips lifecycle state.
 *
 * <p>This class does not create NPShipsSavedData.
 *
 * <p>Reconciliation is intentionally performed by the caller before querying.
 */
public final class NPShipsLifecycleQuery {

    private NPShipsLifecycleQuery() {}

    public record StructureInfo(
            UUID uuid,
            ResourceKey<Level> dimension,
            boolean loaded,
            long deadline,
            @Nullable Double x,
            @Nullable Double y,
            @Nullable Double z) {

        /** -1 deadline means loaded. */
        public boolean isLoaded() {
            return this.loaded;
        }

        /** True when an unloaded tracked structure has passed its deletion deadline. */
        public boolean isExpired() {

            if (this.loaded) {
                return false;
            }

            if (this.deadline < 0L) {
                return false;
            }

            return this.deadline <= System.currentTimeMillis();
        }

        /**
         * Remaining milliseconds until automatic deletion.
         *
         * <p>-1 = loaded / no timer.
         */
        public long getRemainingMillis() {

            if (this.loaded) {
                return -1L;
            }

            if (this.deadline < 0L) {
                return -1L;
            }

            return Math.max(0L, this.deadline - System.currentTimeMillis());
        }

        /**
         * Remaining whole seconds.
         *
         * <p>-1 = loaded / no timer.
         */
        public long getRemainingSeconds() {

            final long millis = this.getRemainingMillis();

            if (millis < 0L) {
                return -1L;
            }

            return millis / 1000L;
        }

        /** Remaining minutes and seconds. */
        public TimeRemaining getRemainingTime() {

            final long seconds = this.getRemainingSeconds();

            if (seconds < 0L) {
                return new TimeRemaining(-1L, -1L);
            }

            return new TimeRemaining(seconds / 60L, seconds % 60L);
        }

        public boolean hasPosition() {
            return this.x != null && this.y != null && this.z != null;
        }
    }

    public record TimeRemaining(long minutes, long seconds) {

        public String format() {

            return "%d:%02d".formatted(this.minutes, this.seconds);
        }
    }

    public enum Status {
        LOADED,
        UNLOADED,
        EXPIRED
    }

    /**
     * Finds a tracked UUID across all server dimensions.
     *
     * <p>Does not create SavedData.
     */
    @Nullable
    public static StructureInfo find(final MinecraftServer server, final UUID uuid) {

        for (final ServerLevel level : server.getAllLevels()) {

            final NPShipsSavedData data = NPShipsSavedData.peek(level);

            if (data == null) {
                continue;
            }

            if (!data.isTracked(uuid)) {
                continue;
            }

            return createInfo(level, data, uuid);
        }

        return null;
    }

    public static List<StructureInfo> all(final MinecraftServer server) {

        final List<StructureInfo> result = new ArrayList<>();

        for (final ServerLevel level : server.getAllLevels()) {

            result.addAll(inDimension(level));
        }

        result.sort(
                Comparator.comparing(
                                StructureInfo::dimension,
                                Comparator.comparing(ResourceKey::location))
                        .thenComparing(StructureInfo::uuid));

        return result;
    }

    public static List<StructureInfo> inDimension(final ServerLevel level) {

        final List<StructureInfo> result = new ArrayList<>();

        final NPShipsSavedData data = NPShipsSavedData.peek(level);

        if (data == null) {
            return result;
        }

        for (final UUID uuid : data.getTrackedShips().keySet()) {

            result.add(createInfo(level, data, uuid));
        }

        result.sort(Comparator.comparing(StructureInfo::uuid));

        return result;
    }

    public static List<StructureInfo> loaded(final MinecraftServer server) {

        return all(server).stream().filter(StructureInfo::isLoaded).toList();
    }

    public static List<StructureInfo> unloaded(final MinecraftServer server) {

        return all(server).stream().filter(info -> !info.isLoaded()).toList();
    }

    public static List<StructureInfo> expired(final MinecraftServer server) {

        return all(server).stream().filter(StructureInfo::isExpired).toList();
    }

    public static Status getStatus(final StructureInfo info) {

        if (info.isLoaded()) {
            return Status.LOADED;
        }

        if (info.isExpired()) {
            return Status.EXPIRED;
        }

        return Status.UNLOADED;
    }

    /** Current world bounds; does not load chunks or read Sable storage from disk. */
    @Nullable
    public static net.minecraft.world.phys.AABB bounds(ServerLevel level, UUID uuid) {
        var data = NPShipsSavedData.peek(level);
        if (data == null || !data.isTracked(uuid)) return null;
        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            var body = container.getSubLevel(uuid);
            if (body != null && !body.isRemoved()) return worldBounds(body);
            var holding = container.getHoldingChunkMap().getHoldingSubLevel(uuid);
            if (holding != null) {
                var b = holding.data().bounds();
                return new net.minecraft.world.phys.AABB(
                        b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            }
        }
        return data.unloadedBounds(uuid);
    }

    /** Use the current pose, since Sable's published box can lag behind assembly or movement. */
    @Nullable
    public static net.minecraft.world.phys.AABB worldBounds(SubLevel body) {
        var local = body.getPlot().getBoundingBox();
        if (local == null) return null;
        var b =
                new dev.ryanhcode.sable.companion.math.BoundingBox3d(
                        local.minX(),
                        local.minY(),
                        local.minZ(),
                        local.maxX() + 1.0,
                        local.maxY() + 1.0,
                        local.maxZ() + 1.0);
        b.transform(body.logicalPose(), new org.joml.Matrix4d(), b);
        if (!Double.isFinite(b.minX())
                || !Double.isFinite(b.minY())
                || !Double.isFinite(b.minZ())
                || !Double.isFinite(b.maxX())
                || !Double.isFinite(b.maxY())
                || !Double.isFinite(b.maxZ())) return null;
        return new net.minecraft.world.phys.AABB(
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private static StructureInfo createInfo(
            final ServerLevel level, final NPShipsSavedData data, final UUID uuid) {

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);

        final SubLevel subLevel = container != null ? container.getSubLevel(uuid) : null;

        /*
         * ---------------------------------------------------------
         * LOADED SUB-LEVEL
         * ---------------------------------------------------------
         */
        if (subLevel instanceof final ServerSubLevel serverSubLevel) {

            final var position = serverSubLevel.logicalPose().position();

            return new StructureInfo(
                    uuid, level.dimension(), true, -1L, position.x(), position.y(), position.z());
        }

        /*
         * ---------------------------------------------------------
         * UNLOADED SUB-LEVEL
         * ---------------------------------------------------------
         *
         * Sable may still have the HoldingSubLevel in memory.
         */
        if (container != null) {

            final HoldingSubLevel holdingSubLevel =
                    container.getHoldingChunkMap().getHoldingSubLevel(uuid);

            if (holdingSubLevel != null) {

                final var position = holdingSubLevel.data().pose().position();

                return new StructureInfo(
                        uuid,
                        level.dimension(),
                        false,
                        data.getUnloadDeadline(uuid),
                        position.x(),
                        position.y(),
                        position.z());
            }
        }

        /*
         * ---------------------------------------------------------
         * UNLOADED, HOLDING CHUNK NOT IN MEMORY
         * ---------------------------------------------------------
         *
         * The UUID is still tracked, but Sable no longer exposes
         * the holding data in memory.
         *
         * Therefore coordinates are unavailable.
         */
        return new StructureInfo(
                uuid, level.dimension(), false, data.getUnloadDeadline(uuid), null, null, null);
    }
}
