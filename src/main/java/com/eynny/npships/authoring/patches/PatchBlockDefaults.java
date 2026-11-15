package com.eynny.npships.authoring.patches;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Native serializer defaults on a detached BE, before stored values win. No live level is given.
 */
public interface PatchBlockDefaults {
    boolean applies(String name, BlockEntity entity);

    void fill(
            com.eynny.npships.authoring.document.SchematicDocument document,
            net.minecraft.core.BlockPos position,
            boolean stored,
            String name,
            BlockEntity entity,
            CompoundTag defaults,
            HolderLookup.Provider registry);
}
