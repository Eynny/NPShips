package com.eynny.npships.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class SupportBlockDetector {

    private SupportBlockDetector() {}

    /**
     * Returns true when the block at the given position provides a sturdy upper face that Minecraft
     * itself considers suitable as a full support surface.
     *
     * <p>This overload is retained for callers which need to inspect the live world.
     */
    public static boolean isSupportBlock(final ServerLevel level, final BlockPos pos) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (pos == null) {
            throw new IllegalArgumentException("pos cannot be null.");
        }

        final BlockState state = level.getBlockState(pos);

        return isSupportBlock(level, pos, state);
    }

    /**
     * Returns true when the supplied block state provides a sturdy upper face that Minecraft itself
     * considers suitable as a full support surface.
     *
     * <p>IMPORTANT:
     *
     * <p>This overload does not perform any world block lookup. The BlockState must already have
     * been obtained from the appropriate source, such as SpawnSpatialSnapshot.
     *
     * <p>Fluids are explicitly excluded because NPShips treats fluids as a separate environment
     * category.
     */
    public static boolean isSupportBlock(
            final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (pos == null) {
            throw new IllegalArgumentException("pos cannot be null.");
        }

        if (state == null) {
            throw new IllegalArgumentException("state cannot be null.");
        }

        /*
         * Fluids are handled separately by the environment rules.
         *
         * Do not allow a waterlogged / fluid block to become a
         * support merely because its block state happens to have
         * a sturdy face.
         */
        if (!state.getFluidState().isEmpty()) {
            return false;
        }

        /*
         * This is Minecraft's own support test.
         *
         * Direction.UP is deliberately kept here because a SURFACE
         * support means that the UPPER face of this block provides
         * the placement surface.
         *
         * No VoxelShape inspection is performed manually.
         */
        return state.isFaceSturdy(level, pos, Direction.UP);
    }
}
