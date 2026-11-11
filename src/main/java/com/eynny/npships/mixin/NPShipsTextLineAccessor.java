package com.eynny.npships.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Use vanilla wrapped-line bounds for coloured code without a second layout model. */
@Mixin(targets = "net.minecraft.client.gui.components.MultilineTextField$StringView")
public interface NPShipsTextLineAccessor {
    @Accessor("beginIndex")
    int npships$begin();

    @Accessor("endIndex")
    int npships$end();
}
