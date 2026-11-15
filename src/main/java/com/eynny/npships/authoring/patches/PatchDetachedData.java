package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Native data preparation on a fresh detached BE, before generic component loading. */
public interface PatchDetachedData {
    boolean applies(BlockEntity entity);

    void prepare(SchematicDocument document, BlockPos pos, BlockEntity entity, CompoundTag data);

    default void prepare(
            SchematicDocument document,
            BlockPos pos,
            BlockEntity entity,
            CompoundTag data,
            net.minecraft.world.level.Level context) {
        prepare(document, pos, entity, data);
    }

    default boolean resourceView(SchematicDocument document, BlockPos pos) {
        return false;
    }

    /** Only the complete projected native-data editor calls this within its accepted edit. */
    default SchematicDocument acceptResourceView(
            SchematicDocument before,
            SchematicDocument after,
            BlockPos pos,
            net.minecraft.world.level.Level context) {
        return after;
    }
}
