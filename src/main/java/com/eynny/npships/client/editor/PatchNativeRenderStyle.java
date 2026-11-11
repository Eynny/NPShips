package com.eynny.npships.client.editor;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.joml.Vector4f;

/**
 * Stateless provider-owned interpretation for a thin native visual bridge. Existing-world authored
 * style is pure rendering, not a new server restoration/enable policy transition.
 */
public interface PatchNativeRenderStyle {
    Vector4f color(BlockEntity target, Vector4f original);

    boolean hidden(BlockEntity target);
}
