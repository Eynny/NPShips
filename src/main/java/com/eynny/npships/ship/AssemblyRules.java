package com.eynny.npships.ship;

import com.eynny.npships.api.patches.PatchAssembly;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.AssemblyPoints;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchCode;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Own coordinate assembly and bounded optional native rules; registry and spawn retain ownership.
 */
public final class AssemblyRules {
    private AssemblyRules() {}

    public static boolean excluded(BlockState state, BlockGetter level, BlockPos pos) {
        for (var code : PatchTypes.capabilities(PatchAssembly.class).values())
            if (code.excluded(state, level, pos)) return true;
        return false;
    }

    public static List<BlockPos> points(SchematicDocument document, BlockPos origin) {
        AssemblyPoints.validate(document);
        var result = new ArrayList<BlockPos>();
        for (var point : document.assemblyPoints()) {
            if (AssemblyPoints.empty(document, point))
                throw new IllegalArgumentException(
                        "Assembly point has no block: " + point.position().toShortString());
            result.add(origin.offset(point.position()));
        }
        return List.copyOf(result);
    }

    /** One immutable membership view is shared by all points; no provider gets a full NBT copy. */
    public static Set<BlockPos> membership(SchematicDocument document, BlockPos origin) {
        var result = new HashSet<BlockPos>();
        for (var point : document.blocks().keySet())
            if (!document.blockName(point).equals("minecraft:air"))
                result.add(origin.offset(point));
        return Collections.unmodifiableSet(result);
    }

    public static Set<BlockPos> assemble(
            ServerLevel level, BlockPos point, Set<BlockPos> permitted) {
        return assemble(level, point, permitted, null);
    }

    static Set<BlockPos> assemble(
            ServerLevel level,
            BlockPos point,
            Set<BlockPos> permitted,
            Set<BlockPos> importedGroup) {
        try {
            return assembleChecked(level, point, permitted, importedGroup);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Assembly failed at " + point.toShortString() + ": " + error.getMessage(),
                    error);
        }
    }

    private static Set<BlockPos> assembleChecked(
            ServerLevel level, BlockPos point, Set<BlockPos> permitted, Set<BlockPos> importedGroup)
            throws Exception {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Assembly requires the server thread.");
        BlockState pointState;
        if (!permitted.contains(point) || (pointState = level.getBlockState(point)).isAir())
            throw new IllegalArgumentException(
                    "Assembly point has no block: " + point.toShortString());
        if (AssemblyEligibility.excluded(pointState, level, point))
            throw new IllegalArgumentException(
                    "Assembly point is not movable: " + point.toShortString());
        PatchAssembly.Plan chosen = null;
        try {
            for (int phase = 0; importedGroup == null && chosen == null && phase < 2; phase++) {
                boolean defaults = phase == 1;
                for (var entry :
                        PatchCode.applicable(
                                PatchAssembly.class,
                                code -> code.defaultRules() == defaults,
                                PatchStage.BEFORE_NATIVE_ASSEMBLY)) {
                    var plan = entry.getValue().plan(level, point, permitted);
                    if (plan == null) continue;
                    if (chosen != null) {
                        plan.close();
                        throw new IllegalArgumentException(
                                "Conflicting assembly membership providers.");
                    }
                    chosen = plan;
                }
            }
            Collection<BlockPos> selected;
            if (importedGroup != null) selected = importedGroup;
            else if (chosen != null) selected = chosen.blocks();
            else throw new IllegalArgumentException("No applicable assembly rules.");
            if (selected == null || selected.isEmpty() || selected.size() > permitted.size())
                throw new IllegalArgumentException(
                        "No valid physical assembly at " + point.toShortString());
            var blocks = new LinkedHashSet<BlockPos>();
            for (var p : selected) {
                BlockState state;
                if (!permitted.contains(p)
                        || (state = level.getBlockState(p)).isAir()
                        || AssemblyEligibility.excluded(state, level, p))
                    throw new IllegalArgumentException(
                            "Assembly selected a block outside the permitted source or a non-movable block: "
                                    + p.toShortString());
                if (!blocks.add(p.immutable()))
                    throw new IllegalArgumentException("Duplicate assembly membership.");
            }
            if (!blocks.contains(point))
                throw new IllegalArgumentException("Assembly plan omitted its point.");
            var bounds = BoundingBox3i.from(blocks);
            try (var volumes = new AssemblyGlueSnapshot(level, bounds.toAABB())) {
                var body = SubLevelAssemblyHelper.assembleBlocks(level, point, blocks, bounds);
                if (body == null || body.isRemoved())
                    throw new IllegalStateException(
                            "Physical assembly did not create a live body.");
                var offset = body.getPlot().getCenterBlock().subtract(point);
                volumes.transferred(blocks, offset);
                if (chosen != null) chosen.transferred(offset);
            }
            return Collections.unmodifiableSet(blocks);
        } finally {
            if (chosen != null) chosen.close();
        }
    }
}
