package com.eynny.npships.authoring.patches;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stateless native interpolation initialization on one detached BE; never ticks or keeps it. */
public interface PatchPreviewData {
    boolean applies(BlockEntity entity);

    void settle(BlockEntity entity, CompoundTag data, boolean emptySource);
}
