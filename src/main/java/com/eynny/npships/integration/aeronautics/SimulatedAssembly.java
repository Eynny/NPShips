package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.patches.DetachedPatchAssembly;

import dev.simulated_team.simulated.index.SimTags;
import dev.simulated_team.simulated.util.assembly.SimAssemblyContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Installed native graph/glue semantics, entirely outside the mandatory assembly path. */
public final class SimulatedAssembly implements DetachedPatchAssembly {
    public boolean excluded(BlockState state, BlockGetter level, BlockPos position) {
        return state.is(SimTags.Blocks.NON_MOVABLE);
    }

    public Plan plan(ServerLevel level, BlockPos point, Set<BlockPos> permitted) throws Exception {
        return select(level, point, permitted);
    }

    @Override
    public boolean detachedTransferIsNoop() {
        return true;
    }

    @Override
    public Plan planDetached(Level level, BlockPos point, Set<BlockPos> permitted)
            throws Exception {
        return select(level, point, permitted);
    }

    private Plan select(Level level, BlockPos point, Set<BlockPos> permitted) throws Exception {
        BlockPos seed = point;
        var state = level.getBlockState(point);
        if (com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(state)) {
            // Native machine search does not seed a brittle block. Use its actual attachment,
            // then require the original marker block in the unchanged native result below.
            for (var direction : net.minecraft.core.Direction.values()) {
                var support = point.relative(direction);
                if (permitted.contains(support)
                        && !com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(
                                level.getBlockState(support))
                        && com.simibubi.create.api.contraption.BlockMovementChecks
                                .isBlockAttachedTowards(state, level, point, direction)) {
                    seed = support;
                    break;
                }
            }
        }
        var nativePlan =
                new SimAssemblyContraption(null, false) {
                    @Override
                    protected boolean movementAllowed(
                            BlockState state, Level world, BlockPos position) {
                        return permitted.contains(position)
                                && super.movementAllowed(state, world, position);
                    }
                };
        if (!nativePlan.searchMovedStructure(level, seed))
            throw new IllegalArgumentException(
                    "Native assembly membership refused at " + point.toShortString());
        if (level instanceof com.eynny.npships.ship.DetachedAssemblySource detached)
            detached.reserveReferences(nativePlan.getBlocks().size());
        var blocks = List.copyOf(nativePlan.getBlocks());
        if (!blocks.contains(point))
            throw new IllegalArgumentException(
                    "The native assembly graph does not include the marker block: "
                            + point.toShortString());
        return new Plan() {
            public Collection<BlockPos> blocks() {
                return blocks;
            }
        };
    }
}
