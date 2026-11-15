package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Internal scoped native appearance serializer; history and source mutation remain host-owned. */
public interface PatchBlockAppearance {
    boolean applies(BlockState state, BlockPos position);

    CompoundTag validate(
            SchematicDocument document,
            BlockPos position,
            BlockState target,
            BlockState material,
            Level level);
}
