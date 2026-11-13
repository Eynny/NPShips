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
            net.minecraft.world.level.Level source, BlockPos point, Set<BlockPos> permitted) {
        if (source instanceof com.eynny.npships.ship.DetachedAssemblySource detached) {
            detached.reserveMap(permitted.size());
            detached.reserveMap(permitted.size());
            detached.reserveReferences(permitted.size());
        }
        // Independent bounded graph traversal; the existing native predicate supplies every edge.
        // Match native face and edge neighbours; omit corners offset on all three axes.
        var selected = new java.util.LinkedHashSet<BlockPos>();
        var queued = new HashSet<BlockPos>();
        var frontier = new java.util.ArrayDeque<BlockPos>();
        var glue = new HashSet<SuperGlueEntity>();
        frontier.add(point);
        queued.add(point);
        while (!frontier.isEmpty()) {
            var from = frontier.removeFirst();
            queued.remove(from);
            selected.add(from);
            if (selected.size() > permitted.size())
                throw new IllegalArgumentException("Detached native membership exceeds source");
            var previous = source.getBlockState(from);
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
                        if (connected(source, glue, from, previous, position, state, direction)) {
                            queued.add(position);
                            frontier.addLast(position);
                        }
                    }
        }
        return () -> selected;
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
