package com.eynny.npships.mixin;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cursor-aware insertion without replacing the clipboard or appending to the file's end. */
@Mixin(MultiLineEditBox.class)
public interface NPShipsMultilineAccessor {
    @Accessor("textField")
    MultilineTextField npships$textField();
}
