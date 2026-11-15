package com.eynny.npships.authoring.geometry;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/** Never mutate document-owned diffs, including the immutable empty diff of entity edits. */
public final class PreviewInvalidation {
    private PreviewInvalidation() {}

    public static Set<BlockPos> changed(
            Set<BlockPos> blocks, Set<BlockPos> previous, Set<BlockPos> next) {
        var changed = new HashSet<>(previous);
        for (var p : next) if (!changed.add(p)) changed.remove(p);
        changed.addAll(blocks);
        return changed;
    }
}
