package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.BlockModes;
import com.eynny.npships.authoring.document.DataSuggestions;
import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;

import java.util.List;
import java.util.Map;

/** Detached native option/value serializers behind the existing editor form owner. */
public interface PatchBlockControls {
    boolean applies(SchematicDocument doc, BlockPos pos, HolderLookup.Provider registry);

    /**
     * Native configured domains, inspected without loading authored values or invoking callbacks.
     */
    default Map<String, DataSuggestions.NativeField> nativeFields(
            net.minecraft.world.level.block.entity.BlockEntity entity,
            HolderLookup.Provider registry) {
        return Map.of();
    }

    List<BlockModes.NumberOption> readNumbers(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registry);

    SchematicDocument setNumber(
            SchematicDocument doc,
            BlockPos pos,
            int index,
            int value,
            HolderLookup.Provider registry);

    List<BlockModes.Option> read(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registry);

    SchematicDocument cycle(
            SchematicDocument doc, BlockPos pos, int index, HolderLookup.Provider registry);
}
