package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

/** Native authoring description owned by the registered provider, not the editor host. */
public interface PatchDeviceDescription extends PatchSourceFormat {
    String kind(String blockName);

    default String kind(SchematicDocument document, BlockPos pos) {
        return kind(document.blockName(pos));
    }

    default boolean highlights(net.minecraft.nbt.CompoundTag state) {
        return !kind(state.getString("Name")).isEmpty();
    }

    int defaultRank();

    /** Pure detached record construction, not native capture/restoration or document acceptance. */
    JsonObject create(SchematicDocument document, BlockPos pos, String kind);
}
