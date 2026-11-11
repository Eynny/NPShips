package com.eynny.npships.authoring;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Mandatory base movement safety plus installed native patch rules. */
public final class AssemblyEligibility {
    private AssemblyEligibility() {}

    /** Whether the document declares assembly coordinates. */
    public static boolean hasAssemblyPoints(
            com.eynny.npships.authoring.document.SchematicDocument document) {
        return !document.assemblyPoints().isEmpty();
    }

    public static boolean excluded(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.isAir()
                && (state.getDestroySpeed(level, pos) == -1
                        || com.eynny.npships.ship.AssemblyRules.excluded(state, level, pos));
    }
}
