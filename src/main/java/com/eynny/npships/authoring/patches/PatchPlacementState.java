package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchPlacement;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.ship.EntityPlacementScope;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4d;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * One template's final-position observer, with no world ticker, scan or native cache repair.
 * PatchedTemplate already owns native coordinate tracking. The host calls transferred at that SAME
 * per-tracked-position boundary, avoiding an additional assembly block pass.
 */
final class PatchPlacementState implements AutoCloseable {
    private ServerLevel level;
    private BlockPos origin;
    private Map<BlockPos, BlockPos> moved;
    private final Map<BlockPos, ServerSubLevel> movedBodies = new HashMap<>();
    private final Map<Long, ChunkOwner> chunks = new HashMap<>();

    private static final class ChunkOwner {
        final ServerSubLevel body;
        int targets;

        ChunkOwner(ServerSubLevel body) {
            this.body = body;
        }
    }

    private List<UUID> bodies = List.of();
    private Set<UUID> ownedBodies = Set.of();
    private boolean complete;

    PatchPlacementState(ServerLevel level, BlockPos origin, Map<BlockPos, BlockPos> moved) {
        this.level = Objects.requireNonNull(level);
        this.origin = Objects.requireNonNull(origin).immutable();
        this.moved = Objects.requireNonNull(moved);
        requireOpen();
    }

    void transferred(BlockPos sourceLocal, BlockPos next, ServerSubLevel body) {
        requireOpen();
        if (complete) throw new IllegalStateException("Native body positioning already completed");
        if (!NativeBlockAccess.current(level, Objects.requireNonNull(body))
                || !body.getPlot().contains(new ChunkPos(next)))
            throw new IllegalStateException("Native transfer has no current target owner: " + next);
        long key = ChunkPos.asLong(next.getX() >> 4, next.getZ() >> 4);
        var found = chunks.get(key);
        if (found != null && found.body != body)
            throw new IllegalStateException(
                    "Native placement chunk has conflicting owners: " + next);
        if (movedBodies.containsKey(sourceLocal)) {
            var old = moved.get(sourceLocal);
            long oldKey = ChunkPos.asLong(old.getX() >> 4, old.getZ() >> 4);
            var oldOwner = chunks.get(oldKey);
            if (--oldOwner.targets == 0) chunks.remove(oldKey);
        }
        chunks.computeIfAbsent(key, ignored -> new ChunkOwner(body)).targets++;
        movedBodies.put(sourceLocal.immutable(), body);
    }

    NativeBlockAccess.Scope nativeReads() {
        requireOpen();
        return NativeBlockAccess.open(
                level,
                key -> {
                    requireOpen();
                    var entry = chunks.get(key);
                    return entry == null ? null : entry.body;
                });
    }

    /**
     * Supply ShipSpawner's existing complete union AFTER child creation and final body rotation. Do
     * not use only PatchedTemplate.physicalIds: its previous observer can be inactive for an
     * empty-footprint, document-scoped adapter. No native object or resource becomes publicly
     * owned.
     */
    void complete(Collection<UUID> ids) {
        requireOpen();
        if (complete) throw new IllegalStateException("Final placement already completed");
        var unique = new LinkedHashSet<UUID>();
        for (var id : ids) unique.add(Objects.requireNonNull(id));
        bodies = List.copyOf(unique);
        ownedBodies = Set.copyOf(unique);
        complete = true;
    }

    /**
     * New wrapper per active handler callback. The requireWindow and record reader are supplied by
     * ExternalPatchHandlers and capture its existing per-callback window / per-instance identity.
     * No mutable allowlist is accepted from provider code.
     */
    PatchPlacement capability(
            Set<BlockPos> footprint,
            Set<UUID> rootTokens,
            Runnable requireWindow,
            EntityPlacementScope mapping,
            Function<ResourceLocation, PatchPlacement.RecordRead> records) {
        Objects.requireNonNull(footprint);
        Objects.requireNonNull(rootTokens);
        Objects.requireNonNull(requireWindow);
        Objects.requireNonNull(records);
        Runnable require =
                () -> {
                    requireWindow.run();
                    requireComplete();
                };
        return new PatchPlacement(
                () -> {
                    require.run();
                    return bodies;
                },
                local -> {
                    require.run();
                    if (!footprint.contains(local))
                        throw new IllegalArgumentException(
                                "Final mapping accepts only this instance's declared position: "
                                        + local);
                    return position(local);
                },
                id -> {
                    require.run();
                    if (!ownedBodies.contains(id))
                        throw new IllegalArgumentException("Body is outside this placement: " + id);
                    return body(id);
                },
                token -> {
                    require.run();
                    if (!rootTokens.contains(token))
                        throw new IllegalArgumentException(
                                "Entity root is outside this instance's declared tokens: " + token);
                    if (mapping == null)
                        throw new IllegalStateException("Native entity insertion did not run");
                    return mapping.require(token);
                },
                token -> {
                    require.run();
                    if (!rootTokens.contains(token))
                        throw new IllegalArgumentException(
                                "Entity root is outside this instance's declared tokens: " + token);
                    return entity(token, mapping);
                },
                name -> {
                    require.run();
                    // The executor checks namespace/instance ownership and turns copy quota failure
                    // into typed REFUSED; mandatory native cleanup/restoration is not interrupted.
                    return records.apply(name);
                });
    }

    private PatchPlacement.EntityState entity(UUID token, EntityPlacementScope mapping) {
        net.minecraft.world.entity.Entity entity;
        try {
            entity = mapping == null ? null : mapping.require(token);
        } catch (IllegalStateException unavailable) {
            entity = null;
        }
        if (entity == null)
            return new PatchPlacement.EntityState(
                    token,
                    PatchPlacement.EntityStatus.UNAVAILABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        return new PatchPlacement.EntityState(
                token,
                PatchPlacement.EntityStatus.PRESENT,
                Optional.of(entity.getUUID()),
                Optional.of(net.minecraft.world.entity.EntityType.getKey(entity.getType())),
                Optional.of(entity.position()),
                Optional.of(Sable.HELPER.projectOutOfSubLevel(level, entity.position())));
    }

    private PatchPlacement.Position position(BlockPos local) {
        var nativePosition = moved.getOrDefault(local, origin.offset(local));
        var expected = movedBodies.get(local);
        var bodyId = expected == null ? null : expected.getUniqueId();
        var space =
                bodyId == null
                        ? PatchPlacement.Space.ORDINARY_WORLD
                        : PatchPlacement.Space.NATIVE_PLOT;
        if (bodyId == null) {
            // A native plot containing an unrecorded target is not an ordinary static placement.
            // Refuse that mapping rather than inferring a foreign/reused body by position alone.
            if (Sable.HELPER.getContaining(level, nativePosition) != null)
                return result(
                        local,
                        nativePosition,
                        PatchPlacement.Space.NATIVE_PLOT,
                        PatchPlacement.PositionStatus.OWNERSHIP_CHANGED,
                        null,
                        null,
                        null);
            var chunk =
                    level.getChunkSource()
                            .getChunkNow(nativePosition.getX() >> 4, nativePosition.getZ() >> 4);
            return observed(local, nativePosition, space, null, chunk, null);
        }
        var body = expected;
        if (!NativeBlockAccess.current(level, body))
            return result(
                    local,
                    nativePosition,
                    space,
                    PatchPlacement.PositionStatus.BODY_UNAVAILABLE,
                    bodyId,
                    null,
                    null);
        if (!body.getPlot().contains(new ChunkPos(nativePosition))
                || Sable.HELPER.getContaining(level, nativePosition) != body)
            return result(
                    local,
                    nativePosition,
                    space,
                    PatchPlacement.PositionStatus.OWNERSHIP_CHANGED,
                    bodyId,
                    null,
                    null);
        // Supported native plot access. No fallback to a possibly stale ordinary chunk view,
        // reflection, cache clearing, generation or repair of the known external U1 boundary.
        var chunk =
                NativeBlockAccess.chunk(
                        level, body, nativePosition.getX() >> 4, nativePosition.getZ() >> 4);
        return observed(local, nativePosition, space, bodyId, chunk, body);
    }

    private PatchPlacement.Position observed(
            BlockPos local,
            BlockPos nativePosition,
            PatchPlacement.Space space,
            UUID bodyId,
            LevelChunk chunk,
            ServerSubLevel body) {
        if (chunk == null || level.isOutsideBuildHeight(nativePosition))
            return result(
                    local,
                    nativePosition,
                    space,
                    PatchPlacement.PositionStatus.CHUNK_UNAVAILABLE,
                    bodyId,
                    null,
                    null);
        var state = chunk.getBlockState(nativePosition);
        var point = Vec3.atLowerCornerOf(nativePosition);
        if (body != null) point = body.logicalPose().transformPosition(point);
        return result(
                local,
                nativePosition,
                space,
                state.isAir()
                        ? PatchPlacement.PositionStatus.EMPTY
                        : PatchPlacement.PositionStatus.PRESENT,
                bodyId,
                point,
                state);
    }

    private static PatchPlacement.Position result(
            BlockPos local,
            BlockPos nativePosition,
            PatchPlacement.Space space,
            PatchPlacement.PositionStatus status,
            UUID bodyId,
            Vec3 worldPoint,
            net.minecraft.world.level.block.state.BlockState state) {
        return new PatchPlacement.Position(
                local,
                nativePosition,
                space,
                status,
                Optional.ofNullable(bodyId),
                Optional.ofNullable(worldPoint),
                Optional.ofNullable(state));
    }

    private PatchPlacement.Body body(UUID id) {
        var body = nativeBody(id);
        if (body == null)
            return new PatchPlacement.Body(
                    id, PatchPlacement.BodyStatus.UNAVAILABLE, Optional.empty());
        var frame =
                new PatchPlacement.BodyFrame(
                        level.dimension().location(),
                        id,
                        body.getRuntimeId(),
                        body.getPlot().getCenterBlock(),
                        body.logicalPose().bakeIntoMatrix(new Matrix4d()));
        return new PatchPlacement.Body(id, PatchPlacement.BodyStatus.PRESENT, Optional.of(frame));
    }

    private ServerSubLevel nativeBody(UUID id) {
        var container = SubLevelContainer.getContainer(level);
        var found = container == null ? null : container.getSubLevel(id);
        return found instanceof ServerSubLevel body && !body.isRemoved() ? body : null;
    }

    private void requireOpen() {
        if (level == null || !level.getServer().isSameThread())
            throw new IllegalStateException("Final mapping requires its open server-thread owner");
    }

    private void requireComplete() {
        requireOpen();
        if (!complete) throw new IllegalStateException("Native body positioning is not final");
    }

    @Override
    public void close() {
        // Not native cleanup. Escaped/expired capability wrappers must not keep this world alive.
        level = null;
        origin = null;
        moved = null;
        movedBodies.clear();
        chunks.clear();
        bodies = List.of();
        ownedBodies = Set.of();
        complete = false;
    }
}
