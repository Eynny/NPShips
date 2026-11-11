package com.eynny.npships.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public final class Ship {

    private final UUID id;

    private final ResourceLocation structureId;

    private final BlockPos origin;
    private final List<UUID> physicalIds;
    private final List<UUID> spawnedMobs;

    public Ship(UUID id, ResourceLocation structureId, BlockPos origin) {
        this(id, structureId, origin, List.of(id));
    }

    public Ship(UUID id, ResourceLocation structureId, BlockPos origin, List<UUID> physicalIds) {
        this(id, structureId, origin, physicalIds, List.of());
    }

    public Ship(
            UUID id,
            ResourceLocation structureId,
            BlockPos origin,
            List<UUID> physicalIds,
            List<UUID> spawnedMobs) {
        this.spawnedMobs = List.copyOf(spawnedMobs);
        this.id = id;
        this.structureId = structureId;
        this.origin = origin.immutable();
        this.physicalIds = List.copyOf(physicalIds);
    }

    public List<UUID> getSpawnedMobs() {
        return spawnedMobs;
    }

    public List<UUID> getPhysicalIds() {
        return physicalIds;
    }

    public UUID getId() {
        return id;
    }

    public ResourceLocation getStructureId() {
        return structureId;
    }

    public BlockPos getOrigin() {
        return origin;
    }
}
