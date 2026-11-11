package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.authoring.patches.SchematicResources;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Scoped form dispatch through the common frozen registry, only while rebuilding an inspector. */
final class BlockInspectorContributions {
    private BlockInspectorContributions() {}

    static void build(
            BlockInspectorContribution.Slot slot,
            EditorToolsPanel panel,
            BlockState state,
            CompoundTag saved,
            String[] draft) {
        var detached =
                slot != BlockInspectorContribution.Slot.BEFORE_DATA
                        ? SchematicResources.detached(
                                panel.cached, panel.pos(), panel.mc.level.registryAccess())
                        : null;
        // Reuse the detached reader's actual state instead of decoding/copying the palette again.
        var actual =
                state != null
                        ? state
                        : detached != null ? detached.getBlockState() : panel.selectedBlockState();
        for (var entry :
                PatchCode.applicable(
                        BlockInspectorContribution.class,
                        code -> code.slot() == slot && code.applies(actual, detached),
                        PatchStage.AUTHOR_PREPARATION))
            entry.getValue()
                    .build(
                            panel,
                            actual,
                            saved == null ? panel.cached.blockEntityData(panel.pos()) : saved,
                            draft);
    }
}
