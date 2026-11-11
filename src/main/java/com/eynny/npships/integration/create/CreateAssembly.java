package com.eynny.npships.integration.create;

import com.eynny.npships.api.patches.PatchAssembly;
import com.eynny.npships.authoring.AssemblyEligibility;
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
public final class CreateAssembly implements PatchAssembly {
    @Override
    public boolean defaultRules() {
        return true;
    }

    @Override
    public Plan plan(ServerLevel level, BlockPos point, Set<BlockPos> permitted) {
        var selected = blocks(level, point, permitted);
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
                        (from, previous, position, state, direction) -> {
                            if (!permitted.contains(position)
                                    || AssemblyEligibility.excluded(state, level, position))
                                return false;
                            boolean attached =
                                    direction != null
                                            && BlockMovementChecks.isBlockAttachedTowards(
                                                    state,
                                                    level,
                                                    position,
                                                    direction.getOpposite());
                            boolean sticky =
                                    !BlockMovementChecks.isBrittle(state)
                                            && previous.canStickTo(state)
                                            && state.canStickTo(previous);
                            if (sticky && direction != null)
                                sticky =
                                        !BlockMovementChecks.isNotSupportive(previous, direction)
                                                && !BlockMovementChecks.isNotSupportive(
                                                        state, direction.getOpposite());
                            if (sticky || attached) return true;
                            for (var entity : glue)
                                if (entity.contains(from) && entity.contains(position)) return true;
                            for (var entity :
                                    level.getEntitiesOfClass(
                                            SuperGlueEntity.class,
                                            SuperGlueEntity.span(from, position).inflate(16)))
                                if (entity.contains(from) && entity.contains(position)) {
                                    glue.add(entity);
                                    return true;
                                }
                            return false;
                        });
        return result.blocks();
    }
}
