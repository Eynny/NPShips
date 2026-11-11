package com.eynny.npships.naturalspawn;

import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.ship.NPShipsSchematicManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * One structure eligible for the current natural-spawn attempt.
 *
 * <p>This contains all metadata needed by the attempt without re-reading configuration files or NBT
 * files.
 */
public record NaturalSpawnStructureCandidate(
        ResourceLocation structureId,
        NPShipsSchematicManager.SchematicInfo schematic,
        NPShipsStructureConfig config,
        BlockPos size,
        @org.jetbrains.annotations.Nullable NaturalSpawnRarity.Selection raritySelection) {

    public NaturalSpawnStructureCandidate(
            ResourceLocation id,
            NPShipsSchematicManager.SchematicInfo info,
            NPShipsStructureConfig config,
            BlockPos size) {
        this(id, info, config, size, null);
    }

    public NaturalSpawnStructureCandidate {

        if (structureId == null) {
            throw new IllegalArgumentException("structureId cannot be null.");
        }

        if (schematic == null) {
            throw new IllegalArgumentException("schematic cannot be null.");
        }

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null.");
        }

        if (size == null) {
            throw new IllegalArgumentException("size cannot be null.");
        }

        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {

            throw new IllegalArgumentException("Structure size must be positive: " + size);
        }

        /*
         * BlockPos is mutable in Minecraft's API ecosystem.
         * Store an immutable copy.
         */
        size = size.immutable();
    }

    public String commandName() {
        return this.schematic.commandName();
    }
}
