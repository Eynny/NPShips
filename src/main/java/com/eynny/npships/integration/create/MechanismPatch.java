package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Function;

public final class MechanismPatch {
    private MechanismPatch() {}

    public static boolean matches(String id) {
        return id.equals("simulated:swivel_bearing");
    }

    public static void restore(
            ServerLevel level, JsonObject patch, Function<BlockPos, BlockPos> resolve) {
        if (!patch.has("assemble") || !patch.get("assemble").getAsBoolean()) return;
        var p = resolve.apply(SchematicPatches.position(patch.getAsJsonArray("pos")));
        if (!(level.getBlockEntity(p) instanceof SwivelBearingBlockEntity be))
            throw new IllegalArgumentException("Missing swivel bearing at " + p);
        if (!be.isAssembled()) be.assemble();
        com.eynny.npships.authoring.patches.PatchedTemplate.trackPhysicalBody(
                level, be.getSubLevelID());
        if (!be.isAssembled())
            throw new IllegalArgumentException(
                    "Swivel bearing could not assemble at "
                            + p
                            + ": "
                            + be.getLastAssemblyException());
    }
}
