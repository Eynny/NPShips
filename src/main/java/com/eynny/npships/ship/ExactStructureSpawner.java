package com.eynny.npships.ship;

import com.eynny.npships.api.NPShipsSpawnApi.ExactResult;
import com.eynny.npships.api.NPShipsSpawnApi.ExactStatus;
import com.eynny.npships.api.SpawnResult.Placement;
import com.eynny.npships.authoring.patches.PatchedTemplate;
import com.eynny.npships.integration.events.NPShipsEventBridge;
import com.eynny.npships.naturalspawn.NPShipsNaturalSpawnManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalValidator;
import com.eynny.npships.resources.ResourceLimitException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/** Synchronous selected-catalogue placement; never searches, generates chunks or owns a ticker. */
public final class ExactStructureSpawner {
    private ExactStructureSpawner() {}

    public static ExactResult spawn(ServerLevel level, ResourceLocation id, BlockPos requested) {
        if (level == null)
            return result(
                    null,
                    id,
                    requested,
                    ExactStatus.INVALID_ORIGIN,
                    "A target level is required.",
                    null,
                    false);
        var server = level.getServer();
        if (!server.isSameThread())
            throw new IllegalStateException("Call spawnExact on the target server thread.");
        BlockPos origin = requested == null ? null : requested.immutable();
        if (!NPShipsNaturalSpawnManager.acceptsExternalRequests(server))
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.SERVER_UNAVAILABLE,
                    "Server is stopping or stopped.",
                    null,
                    false);
        if (server.getLevel(level.dimension()) != level || origin == null)
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.INVALID_ORIGIN,
                    "An actual registered level and origin are required.",
                    null,
                    false);
        if (id == null)
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.INVALID_STRUCTURE,
                    "A canonical structure ID is required.",
                    null,
                    false);
        final NPShipsSchematicManager.SchematicInfo info;
        try {
            SchematicNames.parse(id.toString());
            info = NPShipsSchematicManager.find(server, id.toString());
        } catch (ResourceLimitException refused) {
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.RESOURCE_LIMIT,
                    refused.getMessage(),
                    null,
                    false);
        } catch (IllegalArgumentException invalid) {
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.INVALID_STRUCTURE,
                    invalid.getMessage(),
                    null,
                    false);
        } catch (RuntimeException unavailable) {
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.SERVER_UNAVAILABLE,
                    "Catalogue unavailable: " + unavailable.getMessage(),
                    null,
                    false);
        }
        if (info == null || info.snapshot() == null)
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.INVALID_STRUCTURE,
                    "No valid catalogue schematic: " + id,
                    null,
                    false);
        if (!NPShipsNaturalSpawnManager.beginExactPlacement(server))
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.BUSY,
                    "A managed attempt or exact placement owns spawning.",
                    null,
                    false);
        boolean committing = false;
        boolean preparing = true;
        try {
            NPShipsEventBridge.accepted(level);
            var refusal = preflight(level, id, origin, info.snapshot().size());
            if (refusal != null) return refusal;
            var template = info.snapshot().load(level, null, origin);
            try {
                refusal = preflight(level, id, origin, template.getSize());
                if (refusal != null) return refusal;
                if (template instanceof PatchedTemplate patched) patched.prepare(level, origin);
                // Provider preflight is synchronous and trusted; recheck before destructive
                // placement.
                refusal = preflight(level, id, origin, template.getSize());
                if (refusal != null) return refusal;
                preparing = false;
                committing = true;
                var ship = ShipSpawner.spawnPreparedDirect(level, id, origin, template, info);
                return result(level, id, origin, ExactStatus.SUCCESS, "", ship, true);
            } finally {
                if (template instanceof PatchedTemplate patched) patched.close();
            }
        } catch (Exception failure) {
            Throwable cause = failure;
            for (int i = 0; i < 12 && cause != null; i++, cause = cause.getCause())
                if (cause instanceof ResourceLimitException)
                    return result(
                            level,
                            id,
                            origin,
                            ExactStatus.RESOURCE_LIMIT,
                            cause.getMessage(),
                            null,
                            committing);
            return result(
                    level,
                    id,
                    origin,
                    preparing ? ExactStatus.PREPARATION_FAILED : ExactStatus.PLACEMENT_FAILED,
                    failure.getMessage(),
                    null,
                    committing);
        } finally {
            NPShipsNaturalSpawnManager.endExactPlacement(server);
        }
    }

    private static ExactResult preflight(
            ServerLevel level, ResourceLocation id, BlockPos origin, Vec3i size) {
        long endX = (long) origin.getX() + size.getX();
        long endY = (long) origin.getY() + size.getY();
        long endZ = (long) origin.getZ() + size.getZ();
        if (size.getX() <= 0
                || size.getY() <= 0
                || size.getZ() <= 0
                || origin.getY() < level.getMinBuildHeight()
                || endY > level.getMaxBuildHeight()
                || origin.getX() < -30000000
                || origin.getZ() < -30000000
                || endX > 30000000
                || endZ > 30000000
                || origin.getX() < level.getWorldBorder().getMinX()
                || origin.getZ() < level.getWorldBorder().getMinZ()
                || endX > level.getWorldBorder().getMaxX()
                || endZ > level.getWorldBorder().getMaxZ())
            return result(
                    level,
                    id,
                    origin,
                    ExactStatus.INVALID_ORIGIN,
                    "The full schematic envelope must fit the world height and border.",
                    null,
                    false);
        for (int x = origin.getX() >> 4; x <= (endX - 1) >> 4; x++)
            for (int z = origin.getZ() >> 4; z <= (endZ - 1) >> 4; z++)
                if (!com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader.readyForPlacement(
                        level, x, z))
                    return result(
                            level,
                            id,
                            origin,
                            ExactStatus.UNLOADED_REGION,
                            "Load the complete schematic region and its native entity data before exact placement; unready chunk "
                                    + x
                                    + ","
                                    + z,
                            null,
                            false);
        String overlap = currentOverlap(level, origin, new BlockPos(size));
        return overlap == null
                ? null
                : result(level, id, origin, ExactStatus.PHYSICAL_OVERLAP, overlap, null, false);
    }

    private static String currentOverlap(ServerLevel level, BlockPos origin, BlockPos size) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return null;
        for (var body : container.getAllSubLevels()) {
            if (body == null || body.isRemoved()) continue;
            var world = com.eynny.npships.lifecycle.NPShipsLifecycleQuery.worldBounds(body);
            if (world != null && NaturalSpawnPhysicalValidator.overlapsBounds(world, origin, size))
                return "Exact placement overlaps physical Sable structure "
                        + body.getUniqueId()
                        + ".";
        }
        return null;
    }

    private static ExactResult result(
            ServerLevel level,
            ResourceLocation id,
            BlockPos origin,
            ExactStatus status,
            String reason,
            Ship ship,
            boolean effects) {
        return new ExactResult(
                status,
                id,
                level == null ? null : level.dimension().location(),
                origin,
                reason,
                status != ExactStatus.SUCCESS
                        ? Placement.UNCONFIRMED
                        : ship == null ? Placement.STATIC : Placement.PHYSICAL,
                ship == null ? List.of() : ship.getPhysicalIds(),
                effects);
    }
}
