package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchCode;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** One native material operation, through the common captured policy/order authority. */
public final class BlockAppearances {
    private BlockAppearances() {}

    public static CompoundTag material(
            SchematicDocument document,
            BlockPos position,
            BlockState state,
            BlockState material,
            Level level) {
        var owners =
                PatchCode.applicable(
                        PatchBlockAppearance.class,
                        c -> c.applies(state, position),
                        PatchStage.AUTHOR_PREPARATION);
        if (owners.size() != 1)
            throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                    "copycat_only",
                    "Material applies only to a supported appearance provider. Clear the material field for another block.");
        return owners.getFirst().getValue().validate(document, position, state, material, level);
    }

    public static CompoundTag saved(SchematicDocument source, BlockPos at) {
        var data = source.blockEntityData(at);
        CompoundTag result = null;
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all()) {
            var value = code.savedVisual(data);
            if (value == null) continue;
            if (result != null)
                throw new IllegalArgumentException("Conflicting native appearance codecs");
            result = value;
        }
        return result == null ? new CompoundTag() : result;
    }
}
