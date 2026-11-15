package com.eynny.npships.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read the active native font context without measuring the text again. */
@Mixin(Font.class)
public interface NPShipsFontAccessor {
    @Invoker("getFontSet")
    FontSet npships$fontSet(ResourceLocation font);
}
