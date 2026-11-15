package com.eynny.npships.authoring;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Mandatory base movement safety plus installed native patch rules. */
public final class AssemblyEligibility {
    private AssemblyEligibility() {}

    public static boolean excluded(BlockState state, BlockGetter level, BlockPos pos) {
        if (level instanceof com.eynny.npships.ship.DetachedAssemblySource source
                && source.allowsImmovableBlocks()) return false;
        return !state.isAir()
                && (state.getDestroySpeed(level, pos) == -1
                        || com.eynny.npships.ship.AssemblyRules.excluded(state, level, pos));
    }
}
