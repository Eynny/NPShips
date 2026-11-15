package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;

/** Detached visual topology, owned by one preview. No live world, ticking or native formation. */
public interface PatchPreviewTopology {
    boolean applies(BlockState state);

    Session open(SchematicDocument document, Map<BlockPos, BlockState> blocks);

    interface Session {
        Set<BlockPos> update(
                SchematicDocument document,
                Map<BlockPos, BlockState> blocks,
                Set<BlockPos> changed);

        BlockState state(BlockPos pos, BlockState authored);

        void visualData(BlockPos pos, CompoundTag saved);
    }
}
