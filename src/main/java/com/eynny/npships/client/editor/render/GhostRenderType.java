package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.renderer.RenderType;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Preserve each renderer's texture/shader while enabling alpha blending for the placement ghost.
 */
public final class GhostRenderType extends RenderType {
    private static final Map<RenderType, RenderType> CACHE = new IdentityHashMap<>();

    public static RenderType of(RenderType original) {
        return CACHE.computeIfAbsent(original, GhostRenderType::new);
    }

    static void clearResources() {
        CACHE.clear();
    }

    private GhostRenderType(RenderType original) {
        super(
                "npships_ghost",
                original.format(),
                original.mode(),
                original.bufferSize(),
                false,
                true,
                () -> {
                    // Native cull-enabled shards assume the normal enabled default. Preserve
                    // that default before allowing an explicitly two-sided layer to disable it.
                    RenderSystem.enableCull();
                    original.setupRenderState();
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                },
                () -> {
                    original.clearRenderState();
                    RenderSystem.disableBlend();
                    RenderSystem.enableCull();
                });
    }
}
