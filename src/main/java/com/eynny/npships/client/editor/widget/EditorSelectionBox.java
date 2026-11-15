package com.eynny.npships.client.editor.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** A small selection mark with a full row-height mouse and keyboard target. */
public final class EditorSelectionBox extends Button {
    private final BooleanSupplier selected;

    public EditorSelectionBox(Component label, BooleanSupplier selected, Runnable toggle) {
        super(0, 0, 20, 20, label, button -> toggle.run(), DEFAULT_NARRATION);
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX() + (width - 8) / 2;
        int y = getY() + (height - 8) / 2;
        int color = active ? EditorButtonTheme.text() : 0xff78838d;
        if (selected.getAsBoolean()) graphics.fill(x, y, x + 8, y + 8, color);
        else graphics.renderOutline(x, y, 8, 8, color);
        if (isHoveredOrFocused()) graphics.renderOutline(x - 2, y - 2, 12, 12, color);
    }
}
