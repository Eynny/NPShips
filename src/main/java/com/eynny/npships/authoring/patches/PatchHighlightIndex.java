package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-document cache shared by the patch list and its scene markers. */
public final class PatchHighlightIndex {
    private SchematicDocument source;
    private Map<BlockPos, Boolean> entries = Map.of();

    public Map<BlockPos, Boolean> entries(SchematicDocument document) {
        if (source == document) return entries;
        var next = new LinkedHashMap<BlockPos, Boolean>();
        var palette = document.paletteCopy();
        boolean[] supported = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            var state = palette.getCompound(i);
            supported[i] =
                    PatchCode.sourceFormats(PatchDeviceDescription.class).values().stream()
                            .anyMatch(code -> code.highlights(state));
        }
        var changed = document.changedBlocks(source, 256);
        java.util.Collection<BlockPos> candidates;
        if (changed == null) candidates = document.blocks().keySet();
        else {
            var local = new java.util.HashSet<>(entries.keySet());
            local.addAll(changed);
            candidates = local;
        }
        for (var position : candidates) {
            var index = document.blocks().get(position);
            if (index == null) continue;
            if (supported[index]
                    || document.hasBlockEntityData(position)
                            && !PatchDevices.kind(document, position).isEmpty()) {
                next.put(position, false);
            }
        }
        for (var patch : ExternalPatches.authoringEntries(document)) {
            next.put(SchematicPatches.position(patch.getAsJsonArray("pos")), true);
            for (var target : PatchDevices.targets(patch)) next.put(target, true);
        }
        // Publish together only after a successful rebuild; failures can be retried.
        entries = Collections.unmodifiableMap(next);
        source = document;
        return entries;
    }
}
