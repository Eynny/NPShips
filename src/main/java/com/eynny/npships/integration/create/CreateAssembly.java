package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.patches.DetachedPatchAssembly;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Native base-platform connectivity; no world mutation or optional device restoration. Create
 * connection predicate correspondence retains its MIT notice in licenses/Create-MIT.txt.
 */
public final class CreateAssembly implements DetachedPatchAssembly {
    @Override
    public boolean defaultRules() {
        return true;
    }

    @Override
    public Plan plan(ServerLevel level, BlockPos point, Set<BlockPos> permitted) {
        var selected = blocks(level, point, permitted);
        return () -> selected;
    }

    @Override
    public boolean detachedTransferIsNoop() {
        return true;
    }

    @Override
    public Plan planDetached(
            net.minecraft.world.level.Level source, BlockPos point, Set<BlockPos> permitted)
            throws Exception {
        try (var traversal = beginDetached(source, point, permitted)) {
            while (!traversal.step()) {}
            return traversal.result();
        }
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
    public Traversal beginDetached(
            net.minecraft.world.level.Level source, BlockPos point, Set<BlockPos> permitted) {
        return new Traversal() {
            private final java.util.LinkedHashSet<BlockPos> selected =
                    new java.util.LinkedHashSet<>();
            private final Set<BlockPos> queued = new HashSet<>();
            private final java.util.ArrayDeque<BlockPos> frontier = new java.util.ArrayDeque<>();
            private final Set<SuperGlueEntity> glue = new HashSet<>();
            private boolean closed;

            {
                frontier.add(point);
                queued.add(point);
            }

            public boolean step() {
                if (closed) throw new IllegalStateException("Assembly traversal is closed");
                if (frontier.isEmpty()) return true;
                var from = frontier.removeFirst();
                queued.remove(from);
                selected.add(from);
                if (source instanceof com.eynny.npships.ship.DetachedAssemblySource detached)
                    detached.reserveReferences(12);
                var previous = source.getBlockState(from);
                // Native membership uses face and edge neighbours, but not diagonal corners.
                for (int x = -1; x <= 1; x++)
                    for (int y = -1; y <= 1; y++)
                        for (int z = -1; z <= 1; z++) {
                            int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);
                            if (distance == 0 || distance == 3) continue;
                            var position = from.offset(x, y, z);
                            if (!permitted.contains(position)
                                    || selected.contains(position)
                                    || queued.contains(position)) continue;
                            var state = source.getBlockState(position);
                            if (state.isAir()) continue;
                            var direction =
                                    distance == 1
                                            ? net.minecraft.core.Direction.fromDelta(x, y, z)
                                            : null;
                            if (connected(
                                    source, glue, from, previous, position, state, direction)) {
                                queued.add(position);
                                frontier.addLast(position);
                            }
                        }
                return frontier.isEmpty();
            }

            public Plan result() {
                if (closed || !frontier.isEmpty())
                    throw new IllegalStateException("Assembly traversal is not complete");
                // Ownership passes to the plan. Closing the cursor drops only traversal scratch.
                return () -> java.util.Collections.unmodifiableSet(selected);
            }

            public void close() {
                closed = true;
                frontier.clear();
                queued.clear();
                glue.clear();
            }
        };
    }

    private static Collection<BlockPos> blocks(
            ServerLevel level, BlockPos point, Set<BlockPos> permitted) {
        var glue = new HashSet<SuperGlueEntity>();
        var result =
                SubLevelAssemblyHelper.gatherConnectedBlocks(
                        point,
                        level,
                        permitted.size(),
                        (from, previous, position, state, direction) ->
                                permitted.contains(position)
                                        && connected(
                                                level, glue, from, previous, position, state,
                                                direction));
        return result.blocks();
    }

    private static boolean connected(
            net.minecraft.world.level.Level level,
            Set<SuperGlueEntity> glue,
            BlockPos from,
            net.minecraft.world.level.block.state.BlockState previous,
            BlockPos position,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.core.Direction direction) {
        if (AssemblyEligibility.excluded(state, level, position)) return false;
        boolean attached =
                direction != null
                        && BlockMovementChecks.isBlockAttachedTowards(
                                state, level, position, direction.getOpposite());
        boolean sticky =
                !BlockMovementChecks.isBrittle(state)
                        && previous.canStickTo(state)
                        && state.canStickTo(previous);
        if (sticky && direction != null)
            sticky =
                    !BlockMovementChecks.isNotSupportive(previous, direction)
                            && !BlockMovementChecks.isNotSupportive(state, direction.getOpposite());
        if (sticky || attached) return true;
        for (var entity : glue) if (entity.contains(from) && entity.contains(position)) return true;
        for (var entity :
                level.getEntitiesOfClass(
                        SuperGlueEntity.class, SuperGlueEntity.span(from, position).inflate(16)))
            if (entity.contains(from) && entity.contains(position)) {
                glue.add(entity);
                return true;
            }
        return false;
    }
}
