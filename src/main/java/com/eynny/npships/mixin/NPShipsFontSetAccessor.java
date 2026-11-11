package com.eynny.npships.mixin;

import com.mojang.blaze3d.font.GlyphProvider;

import net.minecraft.client.gui.font.FontSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Provider-list identity changes on native resource reload and font-option application. */
@Mixin(FontSet.class)
public interface NPShipsFontSetAccessor {
    @Accessor("activeProviders")
    List<GlyphProvider> npships$providers();
}
