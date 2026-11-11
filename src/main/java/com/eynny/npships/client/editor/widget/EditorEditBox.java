package com.eynny.npships.client.editor.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Native text editing with editor palette surfaces and preserved validation colours. */
public class EditorEditBox extends EditBox {
    private int authoredTextColor = DEFAULT_TEXT_COLOR;
    private int selectionAnchor, dragAnchor;
    private boolean selecting;

    public EditorEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
    }

    @Override
    public void setTextColor(int color) {
        authoredTextColor = color;
        super.setTextColor(color);
    }

    @Override
    public void setHighlightPos(int position) {
        super.setHighlightPos(position);
        selectionAnchor = Math.clamp(position, 0, getValue().length());
    }

    @Override
    public void setWidth(int width) {
        if (width == getWidth()) return;
        super.setWidth(width);
        // Preserve the viewport of a long retained draft. Only remove stale scrolling
        // when widening makes the entire value fit (e.g. metadata created at width 20).
        if (getScreenX(getValue().length()) - getX() > getInnerWidth()) return;
        int cursor = getCursorPosition(), anchor = selectionAnchor;
        setCursorPosition(0);
        setHighlightPos(anchor);
        setCursorPosition(cursor);
    }

    @Override
    public void onClick(double x, double y) {
        super.onClick(x, y);
        dragAnchor = selectionAnchor;
        selecting = true;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) selecting = false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0 || !selecting || !isFocused() || !active || !visible) return false;
        // Delegate hit testing to native font metrics/displayPos, then restore the
        // initial anchor. Scroll toward the moving caret, rather than toward that anchor.
        super.onClick(x, y);
        int cursor = getCursorPosition();
        setHighlightPos(dragAnchor);
        setCursorPosition(cursor);
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) selecting = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float tick) {
        int rgb = authoredTextColor & 0xffffff;
        super.setTextColor(
                rgb == 0xffffff || rgb == (DEFAULT_TEXT_COLOR & 0xffffff)
                        ? EditorButtonTheme.text()
                        : authoredTextColor);
        super.setTextColorUneditable(EditorButtonTheme.muted());
        if (!isBordered()) {
            super.renderWidget(g, mx, my, tick);
            return;
        }
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x, y, x + w, y + h, EditorButtonTheme.panel());
        g.renderOutline(
                x, y, w, h, isFocused() ? EditorButtonTheme.accent() : EditorButtonTheme.edge());
        // Keep native border padding and inner width while omitting its fixed-colour sprite.
        setBordered(false);
        setX(x + 4);
        setY(y + (h - 8) / 2);
        super.setWidth(w - 8);
        try {
            super.renderWidget(g, mx, my, tick);
        } finally {
            setX(x);
            setY(y);
            super.setWidth(w);
            setBordered(true);
        }
    }
}
