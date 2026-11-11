package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Palette-aware slider retaining native drag, keyboard and narration behavior. */
public abstract class EditorSlider extends AbstractSliderButton {
    protected EditorSlider(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float tick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, EditorButtonTheme.panel());
        g.renderOutline(
                getX(),
                getY(),
                width,
                height,
                active && (isHovered() || isFocused())
                        ? EditorButtonTheme.accent()
                        : EditorButtonTheme.edge());
        int thumb = getX() + (int) Math.round(value * Math.max(0, width - 8));
        EditorButtonTheme.draw(
                g,
                thumb,
                getY(),
                8,
                height,
                true,
                active,
                false,
                isFocused(),
                isHovered() ? 1 : 0,
                null);
        renderScrollingString(
                g,
                Minecraft.getInstance().font,
                2,
                active ? EditorButtonTheme.text() : EditorButtonTheme.muted());
    }
}
