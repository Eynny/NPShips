package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.config.NPShipsEditorConfig;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/** Screen-local pointer capture and preference transaction; never owns editing/focus state. */
final class EditorInspectorDivider {
    private final SchematicEditorScreen screen;
    private boolean dragging, releasePending, moved;
    private int startPreference, startWidth;
    private double startX, startY, lastClickX, lastClickY;
    private long lastClickAt = -1;
    private long cursor, cursorWindow;
    private boolean cursorApplied, cursorAttempted;
    private boolean hovered;
    private long hoverSince;

    EditorInspectorDivider(SchematicEditorScreen screen) {
        this.screen = screen;
    }

    boolean dragging() {
        return dragging;
    }

    boolean captured() {
        return dragging || releasePending;
    }

    boolean press(double x, double y, int button) {
        if (dragging) return true;
        releasePending = false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        long now = Util.getMillis();
        if (lastClickAt >= 0
                && now - lastClickAt <= 250
                && Math.hypot(x - lastClickX, y - lastClickY) <= 4) {
            lastClickAt = -1;
            screen.reflowInspector(0);
            NPShipsEditorConfig.inspectorWidth(0);
            releasePending = true;
            return true;
        }
        startX = x;
        startY = y;
        startWidth = screen.layout().inspector().width();
        startPreference = screen.preferredInspectorWidth;
        moved = false;
        dragging = true;
        lastClickAt = -1;
        return true;
    }

    boolean drag(double x) {
        if (!captured()) return false;
        if (dragging) {
            if (Math.abs(x - startX) >= 3) moved = true;
            if (moved) {
                long desired = Math.round(startWidth + startX - x);
                screen.reflowInspector((int) Math.clamp(desired, 1L, Integer.MAX_VALUE));
            }
        }
        return true;
    }

    boolean release(int button) {
        if (!captured()) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (dragging && moved) {
                // User drag saves the feasible width; later native clamps never replace it.
                screen.reflowInspector(screen.layout().inspector().width());
                NPShipsEditorConfig.inspectorWidth(screen.preferredInspectorWidth);
            } else if (dragging) {
                lastClickAt = Util.getMillis();
                lastClickX = startX;
                lastClickY = startY;
            }
            dragging = releasePending = false;
            restoreCursor();
        }
        return true;
    }

    void newPress() {
        if (!dragging) releasePending = false;
    }

    void lostRelease() {
        cancel();
        releasePending = false;
    }

    void cancel() {
        if (dragging) {
            dragging = false;
            releasePending = true;
            screen.reflowInspector(startPreference);
        }
        lastClickAt = -1;
        restoreCursor();
    }

    void cursor(long window, boolean hover) {
        if (!hover) {
            restoreCursor();
            return;
        }
        if (!cursorAttempted) {
            cursorAttempted = true;
            cursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
            cursorWindow = window;
        }
        if (cursor != 0 && !cursorApplied) {
            GLFW.glfwSetCursor(window, cursor);
            cursorApplied = true;
        }
    }

    private void restoreCursor() {
        if (cursorApplied) GLFW.glfwSetCursor(cursorWindow, 0);
        cursorApplied = false;
    }

    void close() {
        cancel();
        releasePending = false;
        if (cursor != 0) GLFW.glfwDestroyCursor(cursor);
        cursor = 0;
        cursorAttempted = false;
    }

    void render(GuiGraphics graphics, Font font, boolean hover, int mouseX, int mouseY) {
        var rect = screen.layout().divider();
        if (rect.width() == 0) return;
        if (hover != hovered) {
            hovered = hover;
            hoverSince = Util.getMillis();
        }
        int color =
                hover || dragging
                        ? EditorButtonTheme.mix(
                                EditorButtonTheme.panel(), EditorButtonTheme.accent(), .55f)
                        : EditorButtonTheme.edge();
        screen.layout().dividerLine(hover || dragging).fill(graphics, color);
        if (hover && !dragging && Util.getMillis() - hoverSince >= 500)
            EditorTooltips.show(
                    font,
                    Component.translatable("npships.editor.inspector_resize_hint"),
                    mouseX,
                    mouseY);
    }
}
