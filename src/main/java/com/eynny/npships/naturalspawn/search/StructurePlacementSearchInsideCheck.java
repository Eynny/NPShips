package com.eynny.npships.naturalspawn.search;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/** Incremental cursor for validating the structure footprint. */
final class StructurePlacementSearchInsideCheck {

    private boolean active;

    @Nullable private BlockPos origin;

    private int x;

    private int y;

    private int z;

    boolean isActive() {
        return active;
    }

    @Nullable
    BlockPos origin() {
        return origin;
    }

    void start(final BlockPos newOrigin) {

        active = true;

        origin = newOrigin;

        x = 0;

        y = 0;

        z = 0;
    }

    void reset() {

        active = false;

        origin = null;

        x = 0;

        y = 0;

        z = 0;
    }

    BlockPos currentPos(final BlockPos structureOrigin) {

        return structureOrigin.offset(x, y, z);
    }

    void advance(final int sizeX, final int sizeY, final int sizeZ) {

        z++;

        if (z < sizeZ) {
            return;
        }

        z = 0;

        y++;

        if (y < sizeY) {
            return;
        }

        y = 0;

        x++;
    }

    boolean isFinished(final int sizeX) {

        return x >= sizeX;
    }
}
