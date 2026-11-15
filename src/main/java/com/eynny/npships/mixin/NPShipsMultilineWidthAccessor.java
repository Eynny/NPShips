package com.eynny.npships.mixin;

import net.minecraft.client.gui.components.MultilineTextField;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reflow the existing text model on workspace resize, keeping cursor and undo history. */
@Mixin(MultilineTextField.class)
public interface NPShipsMultilineWidthAccessor {
    @Mutable
    @Accessor("width")
    void npships$width(int width);

    @Accessor("width")
    int npships$width();

    @Accessor("selectCursor")
    int npships$selectionCursor();

    @Invoker("reflowDisplayLines")
    void npships$reflow();
}
