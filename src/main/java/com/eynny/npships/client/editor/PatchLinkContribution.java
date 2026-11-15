package com.eynny.npships.client.editor;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Set;

/** Native link UI session; the host retains ordinary input, selection and commit ownership. */
interface PatchLinkContribution extends AutoCloseable {
    interface Factory {
        default boolean incomingApplies(JsonObject record) {
            return false;
        }

        default void incoming(EditorToolsPanel owner, JsonObject record, BlockPos target) {}

        Set<String> roles();

        String section(String role);

        PatchLinkContribution open(EditorToolsPanel owner);
    }

    void begin(JsonObject patch, String role);

    Collection<BlockPos> targets();

    boolean compatible(BlockPos target);

    boolean pick(BlockPos target);

    default void choices(java.util.List<BlockPos> targets) {}

    default void resourcesChanged() {}

    @Override
    default void close() {}
}
