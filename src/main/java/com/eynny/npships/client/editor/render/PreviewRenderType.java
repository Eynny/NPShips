package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.renderer.RenderType;

import java.util.IdentityHashMap;
import java.util.Map;

/** Preserve native model sidedness: the GUI projection and camera each reflect Y. */
public final class PreviewRenderType extends RenderType {
    private static final Map<RenderType, RenderType> CACHE = new IdentityHashMap<>();

    public static RenderType of(RenderType original) {
        return CACHE.computeIfAbsent(original, PreviewRenderType::new);
    }

    static void clearResources() {
        CACHE.clear();
    }

    private PreviewRenderType(RenderType original) {
        super(
                "npships_preview",
                original.format(),
                original.mode(),
                original.bufferSize(),
                false,
                original.sortOnUpload(),
                () -> {
                    // Native cull-enabled shards assume the normal enabled default. Preserve
                    // that default before allowing an explicitly two-sided layer to disable it.
                    RenderSystem.enableCull();
                    original.setupRenderState();
                },
                () -> {
                    original.clearRenderState();
                    RenderSystem.enableCull();
                });
    }
}
