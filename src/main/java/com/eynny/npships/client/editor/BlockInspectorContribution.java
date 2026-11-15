package com.eynny.npships.client.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Internal CLIENT form callback. The panel is borrowed only while building its current form. */
interface BlockInspectorContribution {
    enum Slot {
        BEFORE_DATA,
        AFTER_DATA,
        RESOURCE_SETTINGS
    }

    Slot slot();

    boolean applies(BlockState state, BlockEntity detached);

    void build(EditorToolsPanel panel, BlockState state, CompoundTag saved, String[] draft);
}
