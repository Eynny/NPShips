package com.eynny.npships.client.editor;

import net.minecraft.client.gui.GuiGraphics;

/** Scaled GUI geometry only; no document, widgets, camera or edit state is retained. */
record EditorLayout(
        Rect documentToolbar,
        Rect viewport,
        Rect inspector,
        Rect toolTabs,
        Rect inspectorContent,
        Rect inspectorClip,
        Rect information,
        Rect status,
        Rect modeControls,
        Rect expandedCode,
        Rect expandedCodeField,
        Rect expandedInventory,
        Rect inventoryGrid,
        Rect screenBounds,
        int dividerX) {
    // One right gutter for headings, fields, full-width actions and compact code.
    static final int CONTENT_GUTTER = 12;
    // Nested lists reserve their own six-unit scrollbar and two-unit clearance.
    static final int LIST_GUTTER = 8;
    static final int CONTROL_GAP = 4;
    static final int ROW_PITCH = 24;
    static final int CONTROL_HEIGHT = 20;
    static final int SURFACE_GAP = 8;
    static final int PANEL_PADDING = 2;
    static final int INFORMATION_PADDING = 4;
    static final int INFORMATION_LINES = 5;
    static final int INFORMATION_LINE_GAP = 2;
    static final int CONTROLS_HINT_HEIGHT = 9;

    int contentWidth() {
        return contentWidth(inspector.width());
    }

    static int contentWidth(int inspectorWidth) {
        return Math.max(0, inspectorWidth - CONTENT_GUTTER);
    }

    static int listWidth(int inspectorWidth) {
        return Math.max(0, contentWidth(inspectorWidth) - LIST_GUTTER);
    }

    Rect toolSeparator() {
        int tabsBottom = toolTabs.top() + 20;
        int lineY = (tabsBottom + inspectorContent.top()) / 2;
        return new Rect(inspector.left(), lineY, inspector.left() + contentWidth(), lineY + 1)
                .intersect(inspector);
    }

    Rect viewportFooter() {
        int top = viewport.bottom() + SURFACE_GAP;
        return new Rect(viewport.left(), top, viewport.right(), top + CONTROL_HEIGHT)
                .intersect(
                        new Rect(
                                0,
                                0,
                                screenBounds.right(),
                                Math.max(0, controlsHint().top() - SURFACE_GAP)));
    }

    Rect controlsHint() {
        return new Rect(
                        status.left(),
                        Math.max(0, status.top() - CONTROLS_HINT_HEIGHT),
                        status.right(),
                        status.top())
                .intersect(screenBounds);
    }

    Rect inspectorSurface() {
        if (!inspectorUsable())
            return new Rect(inspector.left(), viewport.top(), inspector.left(), viewport.top());
        return new Rect(
                        inspector.left() - PANEL_PADDING,
                        inspector.top() - PANEL_PADDING,
                        inspector.right(),
                        inspector.bottom())
                .intersect(screenBounds);
    }

    Rect informationSurface() {
        if (inspector.width() == 0)
            return new Rect(inspector.left(), viewport.top(), inspector.left(), viewport.top());
        return new Rect(
                        inspector.left() - PANEL_PADDING,
                        Math.max(viewport.top(), information.top() - INFORMATION_PADDING),
                        information.right(),
                        viewportFooter().bottom())
                .intersect(screenBounds);
    }

    Rect toolTabSurface() {
        return new Rect(
                        inspector.left() - PANEL_PADDING,
                        viewport.top(),
                        inspector.right(),
                        toolSeparator().top() - PANEL_PADDING)
                .intersect(inspectorSurface());
    }

    static EditorLayout calculate(
            int width,
            int height,
            boolean preview,
            boolean previewSource,
            boolean nested,
            int extraToolbar) {
        return calculate(width, height, preview, previewSource, nested, extraToolbar, 0);
    }

    static EditorLayout calculate(
            int width,
            int height,
            boolean preview,
            boolean previewSource,
            boolean nested,
            int extraToolbar,
            int preferredWidth) {
        return calculate(
                width, height, preview, previewSource, nested, extraToolbar, preferredWidth, 9);
    }

    /** Stable five-line information capacity, not a fit to changing hover/selection text. */
    static int informationHeight(int fontLineHeight) {
        return INFORMATION_LINES * (Math.max(1, fontLineHeight) + INFORMATION_LINE_GAP)
                + CONTROL_GAP;
    }

    static EditorLayout calculate(
            int width,
            int height,
            boolean preview,
            boolean previewSource,
            boolean nested,
            int extraToolbar,
            int preferredWidth,
            int fontLineHeight) {
        width = Math.max(0, width);
        height = Math.max(0, height);
        int requested =
                preferredWidth > 0 ? preferredWidth : Math.min(238, Math.max(180, width / 3 - 12));
        int available = Math.max(0, width - 24);
        // Normal mode keeps the slice button (108), gap (4) and a compact slider (60).
        // Compact mode keeps a 160-unit inspector and a 40-unit scene.
        int side =
                preview
                        ? 0
                        : available >= 180 + 172
                                ? Math.clamp(requested, 180, available - 172)
                                : available >= 160 + 40
                                        ? Math.clamp(requested, 160, available - 40)
                                        : Math.min(Math.max(0, width - 16), requested);
        int right = width - (preview ? 8 : side + 16);
        int top =
                Math.min(
                        height,
                        (preview ? (previewSource ? 34 : 36) : nested ? 62 : 34) + extraToolbar);
        int bottom = Math.max(top, height - 64);
        int x = right + 8, y = top + 2;
        int columnBottom =
                Math.min(
                        bottom + SURFACE_GAP + CONTROL_HEIGHT,
                        Math.max(0, height - 19 - CONTROLS_HINT_HEIGHT - SURFACE_GAP));
        int infoBottom = Math.max(top, columnBottom - INFORMATION_PADDING);
        // Keep tabs and one usable inspector control row before allocating information space.
        int infoMinimumTop = y + 28 + CONTROL_HEIGHT + SURFACE_GAP + INFORMATION_PADDING;
        int infoHeight =
                Math.min(
                        informationHeight(fontLineHeight),
                        Math.max(0, infoBottom - infoMinimumTop));
        int infoTop = infoBottom - infoHeight;
        int panelBottom = Math.max(y, infoTop - INFORMATION_PADDING - SURFACE_GAP);
        return new EditorLayout(
                        new Rect(8, 8, width - 8, top - 4),
                        new Rect(8, top, right, bottom),
                        new Rect(x, y, x + side, panelBottom),
                        new Rect(x, y, x + side, y + 28),
                        new Rect(x, y + 28, x + side, panelBottom),
                        new Rect(x - 1, y + 24, x + side + 1, panelBottom),
                        new Rect(x, infoTop, width - 8, infoBottom),
                        new Rect(8, height - 19, width - 8, height),
                        new Rect(8, bottom, right, height - 19),
                        new Rect(8, top, x - 8, columnBottom),
                        new Rect(
                                12,
                                top + 4,
                                Math.max(12, x - 16),
                                Math.max(top + 4, columnBottom - 6)),
                        new Rect(8, top, x - 8, columnBottom),
                        new Rect(14, top + 6, x - 14, columnBottom - 10),
                        new Rect(0, 0, width, height),
                        (right + x - PANEL_PADDING) / 2)
                .bounded(width, height);
    }

    private EditorLayout bounded(int width, int height) {
        var surface = new Rect(0, 0, width, height);
        var code = expandedCode.intersect(surface);
        if (code.width() < 40 || code.height() < 50)
            code = new Rect(code.left(), code.top(), code.left(), code.top());
        return new EditorLayout(
                documentToolbar.intersect(new Rect(0, 0, width, Math.max(0, height - 32))),
                viewport.intersect(surface),
                inspector.intersect(surface),
                toolTabs.intersect(inspector).intersect(surface),
                inspectorContent.intersect(inspector).intersect(surface),
                inspectorClip.intersect(surface),
                information.intersect(surface),
                status.intersect(surface),
                modeControls.intersect(surface),
                code,
                expandedCodeField.intersect(code),
                expandedInventory.intersect(surface),
                inventoryGrid.intersect(expandedInventory).intersect(surface),
                surface,
                Math.clamp(dividerX, 0, width));
    }

    Rect divider() {
        if (!sceneUsable() || !inspectorUsable())
            return new Rect(dividerX, inspector.top(), dividerX, inspector.top());
        return new Rect(dividerX - 2, viewport.top(), dividerX + 3, viewportFooter().bottom());
    }

    Rect dividerLine(boolean highlighted) {
        var hit = divider();
        return new Rect(
                        dividerX - (highlighted ? 1 : 0),
                        hit.top(),
                        dividerX + (highlighted ? 2 : 1),
                        hit.bottom())
                .intersect(hit);
    }

    boolean sceneUsable() {
        return viewport.width() >= 40 && viewport.height() >= 40;
    }

    boolean inspectorUsable() {
        return inspector.width() >= 160 && inspectorContent.height() >= 20;
    }

    Rect codeDock(int documentY, int scroll, int height) {
        return new Rect(
                inspector.left(),
                documentY - scroll,
                inspector.left() + contentWidth(),
                documentY - scroll + height);
    }

    int codeDockHeight() {
        return Math.min(inspectorContent.height(), Math.clamp(inspector.height() - 110, 140, 220));
    }

    record Rect(int left, int top, int right, int bottom) {
        Rect {
            right = Math.max(left, right);
            bottom = Math.max(top, bottom);
        }

        Rect intersect(Rect other) {
            int x = Math.clamp(left, other.left, other.right);
            int y = Math.clamp(top, other.top, other.bottom);
            return new Rect(
                    x,
                    y,
                    Math.max(x, Math.min(right, other.right)),
                    Math.max(y, Math.min(bottom, other.bottom)));
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        float centerX() {
            return (left + right) / 2f;
        }

        float centerY() {
            return (top + bottom) / 2f;
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        void scissor(GuiGraphics graphics) {
            graphics.enableScissor(left, top, right, bottom);
        }

        void fill(GuiGraphics graphics, int color) {
            graphics.fill(left, top, right, bottom, color);
        }

        void apply(LuaEditBox box) {
            box.bounds(left, top, width(), height());
        }
    }
}
