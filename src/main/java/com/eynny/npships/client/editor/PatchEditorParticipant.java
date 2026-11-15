package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * Native session capability. The existing editor owns input, poll cadence, history and lifetime.
 */
interface PatchEditorParticipant extends AutoCloseable {
    interface Factory {
        PatchEditorParticipant create(EditorToolsPanel owner);
    }

    default void refresh() {}

    default void poll() {}

    default long revision() {
        return 0;
    }

    default BlockPos representative(BlockPos target) {
        return target;
    }

    default boolean selects(JsonObject record, BlockPos selection) {
        return false;
    }

    default boolean blocksCreation(BlockPos target) {
        return false;
    }

    default void highlights(Map<BlockPos, Boolean> result) {}

    default void label() {}

    default SchematicDocument prepareEdit(SchematicDocument source, JsonObject record) {
        return source;
    }

    @Override
    default void close() {}
}
