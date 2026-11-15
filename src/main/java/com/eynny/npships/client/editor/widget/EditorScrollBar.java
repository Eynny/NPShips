package com.eynny.npships.client.editor.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;

/** Pixel and row lists use the same draggable track, including clipped nested panels. */
public final class EditorScrollBar {
    private int x, y, height, thumb, max, value;
    private double grab;
    private boolean dragging;
    private IntConsumer changed;

    public void configure(
            int x, int y, int height, int visible, int total, int value, IntConsumer changed) {
        this.x = x;
        this.y = y;
        this.height = Math.max(1, height);
        max = Math.max(0, total - visible);
        thumb =
                Math.min(
                        this.height,
                        Math.max(10, (int) ((long) this.height * visible / Math.max(1, total))));
        this.value = Mth.clamp(value, 0, max);
        this.changed = changed;
        if (max == 0) dragging = false;
    }

    private int top() {
        return y + (int) ((long) value * (height - thumb) / Math.max(1, max));
    }

    public void render(GuiGraphics g) {
        render(
                g,
                EditorButtonTheme.surface(0xff101720),
                dragging ? EditorButtonTheme.accent() : EditorButtonTheme.surface(0xffa4b8c9),
                EditorButtonTheme.surface(0xff40546b));
    }

    /** The same geometry/input owner can use native settings colors without an editor palette. */
    public void render(GuiGraphics g, int track, int edge, int fill) {
        if (max == 0) return;
        g.fill(x, y, x + 6, y + height, track);
        g.renderOutline(x, top(), 6, thumb, edge);
        g.fill(x + 1, top() + 1, x + 5, top() + thumb - 1, fill);
    }

    public boolean click(double mx, double my, int button) {
        if (button != 0 || max == 0 || mx < x - 1 || mx >= x + 7 || my < y || my >= y + height)
            return false;
        grab = my >= top() && my < top() + thumb ? my - top() : thumb / 2d;
        dragging = true;
        drag(my);
        return true;
    }

    public boolean drag(double my) {
        if (!dragging) return false;
        int next =
                Mth.clamp(
                        (int) Math.round((my - y - grab) * max / Math.max(1, height - thumb)),
                        0,
                        max);
        if (next != value) {
            value = next;
            changed.accept(next);
        }
        return true;
    }

    public boolean dragging() {
        return dragging;
    }

    public void release() {
        dragging = false;
    }
}
