package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.patches.DetachedPatchAssembly;
import com.eynny.npships.ship.DetachedAssemblySource;

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

    @Override
    public boolean deferSeed(net.minecraft.world.level.block.state.BlockState state) {
        return com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(state);
    }

    @Override
    public PlacementRequirements placementRequirements(
            net.minecraft.world.level.Level source, BlockPos local) {
        return com.eynny.npships.integration.create.NativePlacementRequirements.inspect(
                source, local);
    }

    @Override
    public Traversal beginDetached(Level level, BlockPos point, Set<BlockPos> permitted)
            throws Exception {
        // The native constructor preallocates its block set even for a one-block component.
        if (level instanceof DetachedAssemblySource detached)
            detached.reserveTraversalReferences(16384);
        // Use the installed native graph implementation one node at a time. Its special
        // chassis, swivel, glue and mod-registered edges remain native-owned.
        // NPShips budgets this traversal itself; the interactive assembler size cap does not apply.
        class NativeTraversal extends SimAssemblyContraption implements Traversal {
            private final java.util.Queue<BlockPos> frontier =
                    new net.createmod.catnip.data.UniqueLinkedList<>();
            private final Set<BlockPos> visited = new java.util.HashSet<>();
            private final Set<BlockPos> readVisited =
                    java.util.Collections.unmodifiableSet(visited);
            private boolean closed;

            NativeTraversal(BlockPos seed) {
                super(null, false);
                addInitialHoneyGlue(level, this, null, seed, false);
                frontier.add(seed);
            }

            @Override
            protected boolean movementAllowed(BlockState state, Level world, BlockPos position) {
                return permitted.contains(position)
                        && (world instanceof DetachedAssemblySource source
                                        && source.allowsImmovableBlocks()
                                || super.movementAllowed(state, world, position));
            }

            public boolean step() throws Exception {
                if (closed) throw new IllegalStateException("Assembly traversal is closed");
                if (frontier.isEmpty()) return true;
                if (level instanceof DetachedAssemblySource detached)
                    detached.reserveTraversalReferences(
                            16384L + 64L * (visited.size() + frontier.size() + 19));
                if (!moveBlock(level, frontier, visited, readVisited))
                    throw new IllegalArgumentException("Native assembly membership refused");
                return frontier.isEmpty();
            }

            public Plan result() {
                if (closed || !frontier.isEmpty())
                    throw new IllegalStateException("Assembly traversal is not complete");
                if (!getBlocks().contains(point))
                    throw new IllegalArgumentException("Native assembly omitted its seed");
                return () -> java.util.Collections.unmodifiableCollection(getBlocks());
            }

            public void close() {
                closed = true;
                frontier.clear();
                visited.clear();
            }
        }
        BlockPos seed = point;
        var state = level.getBlockState(point);
        if (com.simibubi.create.api.contraption.BlockMovementChecks.isBrittle(state)) {
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
        return new NativeTraversal(seed);
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
                                && (world instanceof DetachedAssemblySource source
                                                && source.allowsImmovableBlocks()
                                        || super.movementAllowed(state, world, position));
                    }
                };
        if (!nativePlan.searchMovedStructure(level, seed))
            throw new IllegalArgumentException(
                    "Native assembly membership refused at " + point.toShortString());
        if (level instanceof DetachedAssemblySource detached)
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
