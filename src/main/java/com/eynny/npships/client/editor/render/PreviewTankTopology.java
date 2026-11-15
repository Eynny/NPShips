package com.eynny.npships.client.editor.render;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.config.NPShipsRuntimeConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Provider-neutral detached topology owner. The historical private name is not an API contract. */
final class PreviewTankTopology {
    private final PatchPolicy policy = NPShipsRuntimeConfig.patchPolicy();
    private final Map<ResourceLocation, PatchPreviewTopology> factories =
            PatchTypes.capabilities(PatchPreviewTopology.class);
    private final Map<ResourceLocation, PatchPreviewTopology.Session> sessions =
            new LinkedHashMap<>();
    private final java.util.function.BiConsumer<BlockPos, net.minecraft.network.chat.Component>
            diagnostic;

    PreviewTankTopology(SchematicDocument doc, Map<BlockPos, BlockState> blocks) {
        this(doc, blocks, (pos, detail) -> {});
    }

    PreviewTankTopology(
            SchematicDocument doc,
            Map<BlockPos, BlockState> blocks,
            java.util.function.BiConsumer<BlockPos, net.minecraft.network.chat.Component>
                    diagnostic) {
        this.diagnostic = diagnostic;
        warnDisabled(blocks, blocks.keySet());
        for (var item : applicable(blocks))
            sessions.put(
                    item.getKey(), item.getValue().open(doc, Collections.unmodifiableMap(blocks)));
    }

    private List<Map.Entry<ResourceLocation, PatchPreviewTopology>> applicable(
            Map<BlockPos, BlockState> blocks) {
        return PatchCode.applicable(
                PatchPreviewTopology.class,
                code -> enabled(code) && blocks.values().stream().anyMatch(code::applies),
                PatchStage.AUTHOR_PREPARATION,
                policy);
    }

    private boolean enabled(PatchPreviewTopology code) {
        for (var entry : factories.entrySet())
            if (entry.getValue() == code) return policy.enabled(entry.getKey());
        return false;
    }

    private void warnDisabled(Map<BlockPos, BlockState> blocks, Iterable<BlockPos> positions) {
        for (var entry : factories.entrySet())
            if (!policy.enabled(entry.getKey()))
                for (var pos : positions) {
                    var state = blocks.get(pos);
                    if (state != null && entry.getValue().applies(state))
                        diagnostic.accept(
                                pos,
                                net.minecraft.network.chat.Component.translatable(
                                        "npships.editor.patch_preview_disabled",
                                        entry.getKey().toString()));
                }
    }

    Set<BlockPos> update(
            SchematicDocument doc, Map<BlockPos, BlockState> blocks, Set<BlockPos> changed) {
        var result = new HashSet<BlockPos>();
        warnDisabled(blocks, changed);
        var active =
                PatchCode.applicable(
                        PatchPreviewTopology.class,
                        code ->
                                enabled(code)
                                        && (sessions.entrySet().stream()
                                                        .anyMatch(
                                                                e ->
                                                                        factories.get(e.getKey())
                                                                                == code)
                                                || changed.stream()
                                                        .map(blocks::get)
                                                        .filter(Objects::nonNull)
                                                        .anyMatch(code::applies)),
                        PatchStage.AUTHOR_PREPARATION,
                        policy);
        for (var item : active)
            if (!sessions.containsKey(item.getKey())) {
                sessions.put(
                        item.getKey(),
                        item.getValue().open(doc, Collections.unmodifiableMap(blocks)));
                // A newly applicable topology may join retained neighbours, not just the edited
                // cell.
                for (var entry : blocks.entrySet())
                    if (item.getValue().applies(entry.getValue())) result.add(entry.getKey());
            }
        for (var session : sessions.values())
            result.addAll(session.update(doc, Collections.unmodifiableMap(blocks), changed));
        return result;
    }

    BlockState state(BlockPos p, BlockState state) {
        for (var session : sessions.values()) state = session.state(p, state);
        return state;
    }

    void visualData(BlockPos p, CompoundTag saved) {
        for (var session : sessions.values()) session.visualData(p, saved);
    }
}
