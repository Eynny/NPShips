package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/** Provider-owned detached fluid serializer. The existing panel still commits one document edit. */
public interface PatchFluidEditor {
    boolean applies(
            SchematicDocument document,
            BlockPos pos,
            HolderLookup.Provider registry,
            java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntity> target);

    Session open(
            SchematicDocument document,
            BlockPos pos,
            Level context,
            java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntity> target);

    interface Session {
        int size();

        BlockPos owner();

        String label(int slot);

        FluidStack get(int slot);

        int capacity(int slot);

        boolean accepts(int slot, FluidStack stack);

        boolean creative();

        SchematicDocument put(int slot, FluidStack stack);
    }
}
