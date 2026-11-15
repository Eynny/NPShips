package com.eynny.npships.naturalspawn;

import net.minecraft.core.BlockPos;

/**
 * One concrete placement of a particular structure.
 *
 * <p>A structure may have multiple valid placements inside the same already-scanned search area.
 */
public record NaturalSpawnPlacement(BlockPos origin) {

    public NaturalSpawnPlacement {

        if (origin == null) {
            throw new IllegalArgumentException("origin cannot be null.");
        }

        origin = origin.immutable();
    }
}
