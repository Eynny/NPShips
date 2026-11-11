package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.PatchTypes;

import net.minecraft.resources.ResourceLocation;

/** Frozen code projection, with no frame scans, world references or independent registration. */
public final class NativeRenderStyles {
    private NativeRenderStyles() {}

    private static final class Code {
        static final java.util.Map<ResourceLocation, PatchNativeRenderStyle> VALUES =
                PatchTypes.capabilities(PatchNativeRenderStyle.class);
    }

    public static PatchNativeRenderStyle get(ResourceLocation provider) {
        return Code.VALUES.get(provider);
    }
}
