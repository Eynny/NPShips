package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Small action menu owned by the screen, so clicks cannot fall through into the scene. */
public final class EditorPopupMenu {
    public record Entry(
            Component label,
            BooleanSupplier selected,
            Runnable action,
            java.util.function.Supplier<Component> hint,
            java.util.function.Supplier<List<Entry>> children,
            boolean split,
            BooleanSupplier enabled) {
        public Entry(Component label, BooleanSupplier selected, Runnable action) {
            this(label, selected, action, null);
        }

        public Entry(
                Component label,
                BooleanSupplier selected,
                Runnable action,
                java.util.function.Supplier<Component> hint) {
            this(label, selected, action, hint, null, false, () -> true);
        }

        public Entry submenu(java.util.function.Supplier<List<Entry>> entries) {
            return new Entry(label, selected, action, hint, entries, false, enabled);
        }

        public Entry splitMenu(java.util.function.Supplier<List<Entry>> entries) {
            return new Entry(label, selected, action, hint, entries, true, enabled);
        }

        public Entry enabledWhen(BooleanSupplier value) {
            return new Entry(label, selected, action, hint, children, split, value);
        }

        public Component tooltipText() {
            return hint == null ? label : hint.get();
        }
    }

    private static final Predicate<Entry> ALWAYS_AVAILABLE = entry -> true;
    private List<Entry> entries = List.of();
    private Predicate<Entry> availability = ALWAYS_AVAILABLE;
    private int keyboardFocus = -1;
    private boolean fitTextContents;
    private boolean suspended;
    private EditorPopupMenu parent, child;
    private int childIndex = -1, screenWidth, screenHeight;
    private boolean sideChild;

    private void closeChild() {
        if (child != null) child.close();
        child = null;
        childIndex = -1;
    }

    private EditorPopupMenu root() {
        return parent == null ? this : parent.root();
    }

    private boolean ownContains(double mx, double my) {
        return !suspended
                && !entries.isEmpty()
                && mx >= x
                && mx < x + width
                && my >= y
                && my < y + rows() * cell;
    }

    private void openChild(int index) {
        if (!available(index) || entries.get(index).children() == null) return;
        if (child != null && childIndex == index) return;
        closeChild();
        var items = entries.get(index).children().get();
        if (items.isEmpty()) return;
        child = new EditorPopupMenu();
        childIndex = index;
        keyboardFocus = index;
        child.openText(
                x + width, y + (index / columns) * cell, 120, screenWidth, screenHeight, items);
        child.parent = this;
        child.sideChild = true;
        child.callout = false;
        child.fitTextContents = true;
        child.reposition(screenWidth, screenHeight);
    }

    private void layoutSide() {
        int available = Math.max(13, screenHeight - 12), capacity = Math.max(1, available / 13);
        columns = Math.max(1, (entries.size() + capacity - 1) / capacity);
        cell = Math.clamp(available / Math.max(1, rows()), 13, 20);
        width = Math.min(Math.max(columns, screenWidth - 12), columns * textWidth(screenWidth));
        rowWidth = Math.max(1, width / columns);
        int right = parent.x + parent.width + 2;
        int rightSpace = Math.max(0, screenWidth - 6 - right),
                leftSpace = Math.max(0, parent.x - 8);
        boolean useRight = rightSpace >= width || rightSpace >= leftSpace;
        width = Math.max(1, Math.min(width, useRight ? rightSpace : leftSpace));
        rowWidth = Math.max(1, width / columns);
        x = useRight ? right : parent.x - width - 2;
        y =
                Math.clamp(
                        parent.y + (parent.childIndex / parent.columns) * parent.cell,
                        6,
                        Math.max(6, screenHeight - rows() * cell - 6));
    }

    public boolean isOpen() {
        return !entries.isEmpty();
    }

    public boolean isSuspended() {
        return suspended;
    }

    /** Retain choices while their inspector trigger is temporarily hidden. */
    public void suspend(boolean value) {
        suspended = value;
    }

    private int x, y, width, anchor, columns, anchorY = 8, triggerWidth = 28;
    private List<EditorIconButton> icons = List.of();
    private int cell = 24;
    private int rowWidth = 24;
    private boolean textRows;
    private boolean callout;
    private int hovered = -1;
    private long hoverSince;

    public int anchorX() {
        return anchor;
    }

    public int anchorY() {
        return anchorY;
    }

    public void reanchor(int left, int top, int screenWidth, int screenHeight) {
        int dx = left - anchor, dy = top - anchorY;
        anchor = left;
        anchorY = top;
        x += dx;
        y += dy;
        reposition(screenWidth, screenHeight);
    }

    /** Move an open popup without replacing its entries or hover selection. */
    public void reposition(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        if (entries.isEmpty()) return;
        if (sideChild) layoutSide();
        else if (textRows) layoutText(screenWidth, screenHeight);
        else {
            x = Math.max(6, Math.min(x, screenWidth - width - 6));
            y = Math.max(6, Math.min(y, screenHeight - rows() * cell - 6));
        }
        // Each descendant anchors to its parent's final bounds, including edge clamping.
        repositionChild();
    }

    private void repositionChild() {
        if (child != null) child.reposition(screenWidth, screenHeight);
    }

    public void open(int anchor, int screenWidth, int screenHeight, List<Entry> items) {
        closeChild();
        sideChild = false;
        parent = null;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        suspended = false;
        availability = ALWAYS_AVAILABLE;
        keyboardFocus = -1;
        fitTextContents = false;
        this.anchor = anchor;
        entries = List.copyOf(items);
        triggerWidth = 28;
        cell = 24;
        rowWidth = 24;
        textRows = false;
        callout = true;
        var buttons = new java.util.ArrayList<EditorIconButton>();
        for (var entry : entries) {
            if (entry.label().getContents()
                    instanceof net.minecraft.network.chat.contents.TranslatableContents text) {
                String key = text.getKey();
                buttons.add(
                        new EditorIconButton(
                                        key.substring(key.lastIndexOf('.') + 1),
                                        entry.label(),
                                        0,
                                        0,
                                        () -> {})
                                .selected(entry.selected()));
            } else buttons.add(null);
        }
        icons = buttons;
        hovered = -1;
        columns = Math.max(1, Math.min(items.size(), (screenWidth - 12) / cell));
        width = columns * cell;
        rowWidth = cell;
        anchorY = 8;
        x = Math.max(6, Math.min(anchor, screenWidth - width - 6));
        y = Math.max(30, Math.min(32, screenHeight - 30));
    }

    public void openText(
            int anchor,
            int top,
            int trigger,
            int screenWidth,
            int screenHeight,
            List<Entry> items) {
        open(anchor, screenWidth, screenHeight, items);
        textRows = true;
        callout = false;
        triggerWidth = trigger;
        anchorY = top;
        layoutText(screenWidth, screenHeight);
    }

    private void layoutText(int screenWidth, int screenHeight) {
        int gap = callout ? 25 : 20;
        int below = Math.max(0, screenHeight - anchorY - gap - 6);
        int above = Math.max(0, anchorY - 6);
        boolean down = below >= above;
        int available = Math.max(13, Math.max(below, above));
        int rowCapacity = Math.max(1, available / 13);
        columns = Math.max(1, (entries.size() + rowCapacity - 1) / rowCapacity);
        int rows = rows();
        cell = Math.clamp(available / Math.max(1, rows), 13, 20);
        width =
                callout || fitTextContents
                        ? Math.min(screenWidth - 12, columns * textWidth(screenWidth))
                        : textWidth(screenWidth);
        width = Math.max(columns, width);
        rowWidth = Math.max(1, width / columns);
        x = Math.max(6, Math.min(anchor + 1, screenWidth - width - 6));
        y = down ? anchorY + gap : anchorY - rows * cell - (callout ? 6 : 0);
        y = Math.max(6, Math.min(y, screenHeight - rows * cell - 6));
    }

    private int textWidth(int screenWidth) {
        // Text triggers own their outer border width; labels elide and retain a full tooltip.
        int preferred = triggerWidth - 2;
        if (callout || fitTextContents)
            preferred =
                    Math.max(
                            callout ? triggerWidth : triggerWidth - 2,
                            entries.stream()
                                    .mapToInt(
                                            e -> Minecraft.getInstance().font.width(e.label()) + 20)
                                    .max()
                                    .orElse(120));
        int maximum =
                callout || fitTextContents
                        ? Math.max(40, (screenWidth - 18) / 2)
                        : screenWidth - 12;
        return Math.max(0, Math.min(maximum, preferred));
    }

    /** Opt-in content sizing for a short text trigger; ordinary text menus retain exact width. */
    public void fitTextContents(int screenWidth, int screenHeight) {
        fitTextContents = true;
        reposition(screenWidth, screenHeight);
    }

    /** Per-open dynamic action policy without changing the Entry record's shape or constructors. */
    public void availableWhen(Predicate<Entry> predicate) {
        availability = java.util.Objects.requireNonNull(predicate);
        if (keyboardFocus >= 0 && !available(keyboardFocus)) keyboardFocus = -1;
    }

    private boolean available(int index) {
        return index >= 0
                && index < entries.size()
                && availability.test(entries.get(index))
                && entries.get(index).enabled().getAsBoolean();
    }

    private void seek(int start, int direction, boolean wrap) {
        int count = entries.size();
        for (int offset = 0; offset < count; offset++) {
            int candidate = start + offset * direction;
            if (wrap) candidate = Math.floorMod(candidate, count);
            else if (candidate < 0 || candidate >= count) return;
            if (available(candidate)) {
                keyboardFocus = candidate;
                return;
            }
        }
    }

    private void moveFocus(int step) {
        int direction = Integer.signum(step);
        seek(
                keyboardFocus < 0 ? direction > 0 ? 0 : entries.size() - 1 : keyboardFocus + step,
                direction,
                false);
    }

    private void activate(int index) {
        if (!available(index)) return;
        var entry = entries.get(index);
        root().close();
        entry.action().run();
    }

    /** The owning Screen arbitrates modal input and activation key edges before calling this. */
    public boolean keyPressed(int key, int modifiers) {
        if (suspended || entries.isEmpty()) return false;
        if (child != null && child.isOpen()) {
            if ((key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_ESCAPE) && child.child == null) {
                closeChild();
                return true;
            }
            return child.keyPressed(key, modifiers);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (parent == null) close();
            else parent.closeChild();
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT
                && keyboardFocus >= 0
                && entries.get(keyboardFocus).children() != null) {
            openChild(keyboardFocus);
            if (child != null) child.seek(0, 1, false);
            return true;
        }
        if (keyboardFocus >= 0 && !available(keyboardFocus)) keyboardFocus = -1;
        switch (key) {
            case GLFW.GLFW_KEY_HOME -> seek(0, 1, false);
            case GLFW.GLFW_KEY_END -> seek(entries.size() - 1, -1, false);
            case GLFW.GLFW_KEY_UP -> moveFocus(-Math.max(1, columns));
            case GLFW.GLFW_KEY_DOWN -> moveFocus(Math.max(1, columns));
            case GLFW.GLFW_KEY_LEFT -> moveFocus(-1);
            case GLFW.GLFW_KEY_RIGHT -> moveFocus(1);
            case GLFW.GLFW_KEY_TAB -> {
                int direction = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
                seek(
                        keyboardFocus < 0
                                ? direction > 0 ? 0 : entries.size() - 1
                                : keyboardFocus + direction,
                        direction,
                        true);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (available(keyboardFocus)
                        && entries.get(keyboardFocus).children() != null
                        && !entries.get(keyboardFocus).split()) {
                    openChild(keyboardFocus);
                    if (child != null) child.seek(0, 1, false);
                } else activate(keyboardFocus);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Icon-triggered menus may have text rows, but only icons receive a callout. */
    public void iconTrigger(int screenWidth, int screenHeight) {
        callout = true;
        reposition(screenWidth, screenHeight);
    }

    /** Keep tool palettes inside their owning panel, including the border. */
    public void within(int left, int right, int maximumColumns) {
        if (textRows) {
            width = Math.max(columns, Math.min(width, Math.max(1, right - left - 2)));
            rowWidth = Math.max(1, width / columns);
            x = Math.max(left + 1, Math.min(anchor + 1, right - width - 1));
            repositionChild();
            return;
        }
        columns = Math.max(1, Math.min(entries.size(), maximumColumns));
        cell = Math.max(16, Math.min(24, (right - left - 2) / columns));
        width = columns * cell;
        rowWidth = cell;
        x = Math.max(left + 1, Math.min(anchor, right - width - 1));
        repositionChild();
    }

    private int rows() {
        return (entries.size() + Math.max(1, columns) - 1) / Math.max(1, columns);
    }

    public void at(int top, int screenHeight) {
        anchorY = top;
        if (textRows) return;
        int offset = callout ? 25 : 20;
        y = Math.max(6, Math.min(top + offset, screenHeight - rows() * cell - 6));
        repositionChild();
    }

    public void updateTextTriggerWidth(int width) {
        if (textRows && !callout) triggerWidth = width;
    }

    private int columnAt(double mx) {
        return Math.clamp((int) ((mx - x) * columns / Math.max(1, width)), 0, columns - 1);
    }

    public void triggerWidth(int width) {
        triggerWidth = width;
        if (width > 32) callout = false;
    }

    public boolean close() {
        closeChild();
        suspended = false;
        boolean had = !entries.isEmpty();
        entries = List.of();
        icons = List.of();
        availability = ALWAYS_AVAILABLE;
        keyboardFocus = -1;
        fitTextContents = false;
        return had;
    }

    public boolean contains(double mx, double my) {
        if (suspended || entries.isEmpty()) return false;
        if (ownContains(mx, my) || child != null && child.contains(mx, my)) return true;
        if (child != null) {
            int left = Math.min(x + width, child.x + child.width), right = Math.max(x, child.x);
            return mx >= left
                    && mx <= right
                    && my >= child.y
                    && my < child.y + child.rows() * child.cell;
        }
        return false;
    }

    public boolean click(double mx, double my, int button) {
        if (suspended || entries.isEmpty()) return false;
        if (child != null && child.contains(mx, my)) return child.click(mx, my, button);
        if (ownContains(mx, my)) {
            int index = columnAt(mx) + (int) (my - y) / cell * columns;
            if (button == 0 && available(index)) {
                var entry = entries.get(index);
                int right = x + (index % columns + 1) * width / columns;
                if (entry.children() != null && (!entry.split() || mx >= right - 14))
                    openChild(index);
                else activate(index);
            }
            return true;
        }
        if (contains(mx, my)) return true;
        root().close();
        return true;
    }

    public void render(GuiGraphics g, int mx, int my) {
        if (suspended || entries.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 800);
        g.fill(x - 1, y - 1, x + width + 1, y + rows() * cell + 1, EditorButtonTheme.popupEdge());
        g.fill(x, y, x + width, y + rows() * cell, EditorButtonTheme.panel());
        if (callout && width >= 7) {
            int center = anchor + triggerWidth / 2;
            boolean down = y >= anchorY + 24;
            boolean up = y + rows() * cell <= anchorY - 5;
            if (down || up)
                for (int row = 0; row <= 5; row++) {
                    int yy = down ? y - 6 + row : y + rows() * cell + 5 - row;
                    g.fill(
                            center - row,
                            yy,
                            center + row + 1,
                            yy + 1,
                            EditorButtonTheme.popupEdge());
                    if (row > 0)
                        g.fill(
                                center - row + 1,
                                yy,
                                center + row,
                                yy + 1,
                                EditorButtonTheme.panel());
                }
        }
        int nextHover = ownContains(mx, my) ? columnAt(mx) + (my - y) / cell * columns : -1;
        if (nextHover >= 0 && nextHover < entries.size()) {
            if (entries.get(nextHover).children() != null) openChild(nextHover);
            else if (child != null && childIndex != nextHover) closeChild();
        }
        if (nextHover != hovered) {
            hovered = nextHover;
            hoverSince = net.minecraft.Util.getMillis();
        }
        for (int i = 0; i < entries.size(); i++) {
            int column = i % columns;
            int left = x + column * width / columns;
            int rowWidth = (column + 1) * width / columns - column * width / columns;
            int top = y + (i / columns) * cell;
            var entry = entries.get(i);
            boolean enabled = available(i);
            int textColor = enabled ? EditorButtonTheme.text() : EditorButtonTheme.muted();
            boolean hover = mx >= left && mx < left + rowWidth && my >= top && my < top + cell;
            g.fill(
                    left + 1,
                    top + 1,
                    left + rowWidth - 1,
                    top + cell - 1,
                    EditorButtonTheme.edge());
            g.fill(
                    left + 2,
                    top + 2,
                    left + rowWidth - 2,
                    top + cell - 2,
                    enabled && (hover || entry.selected().getAsBoolean() || childIndex == i)
                            ? EditorButtonTheme.mix(
                                    EditorButtonTheme.panel(), EditorButtonTheme.accent(), .25f)
                            : EditorButtonTheme.panel());
            var icon = icons.get(i);
            if (textRows) {
                var font = Minecraft.getInstance().font;
                if (entry.selected().getAsBoolean())
                    g.fill(
                            left + 5,
                            top + (cell - 3) / 2,
                            left + 8,
                            top + (cell - 3) / 2 + 3,
                            textColor);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(
                                entry.label().getString(),
                                Math.max(0, rowWidth - (entry.children() == null ? 16 : 28))),
                        left + 12,
                        top + (cell - Minecraft.getInstance().font.lineHeight) / 2,
                        textColor);
                if (entry.children() != null) {
                    int cx = left + rowWidth - 7, cy = top + cell / 2;
                    if (entry.split())
                        g.fill(
                                left + rowWidth - 14,
                                top + 2,
                                left + rowWidth - 13,
                                top + cell - 2,
                                EditorButtonTheme.popupEdge());
                    EditorButton.arrow(g, cx, cy, textColor, true);
                }
            } else if (icon != null) {
                // Popup owns hover/tooltip once; scale the fixed-size icon for narrow panels.
                g.pose().pushPose();
                g.pose().translate(left + 2, top + 2, 0);
                float scale = (cell - 4) / 20f;
                g.pose().scale(scale, scale, 1);
                icon.setX(0);
                icon.setY(0);
                icon.active = enabled;
                icon.selected(entry.selected());
                icon.render(g, -10000, -10000, 0);
                g.pose().popPose();
            } else {
                String label = entry.label().getString();
                var font = Minecraft.getInstance().font;
                float scale = Math.min(1f, (cell - 4f) / Math.max(1, font.width(label)));
                g.pose().pushPose();
                g.pose().translate(left + cell / 2f, top + (cell - 8 * scale) / 2f, 0);
                g.pose().scale(scale, scale, 1);
                g.drawCenteredString(font, label, 0, 0, textColor);
                g.pose().popPose();
            }
            if (enabled && keyboardFocus == i)
                g.renderOutline(
                        left + 1,
                        top + 1,
                        Math.max(1, rowWidth - 2),
                        Math.max(1, cell - 2),
                        EditorButtonTheme.accent());
            if (hover
                    && child == null
                    && (entry.hint() != null && !entry.tooltipText().equals(entry.label())
                            || Minecraft.getInstance().font.width(entry.label())
                                    > Math.max(0, rowWidth - (entry.children() == null ? 16 : 28)))
                    && net.minecraft.Util.getMillis() - hoverSince >= 500)
                EditorTooltips.show(Minecraft.getInstance().font, entry.tooltipText(), mx, my);
        }
        g.pose().popPose();
        if (child != null) {
            // Commit the parent's text batch before an opaque child paints over it.
            g.flush();
            g.pose().pushPose();
            try {
                // Native glyphs/items have foreground depth offsets of their own.
                g.pose().translate(0, 0, 400);
                child.render(g, mx, my);
            } finally {
                g.pose().popPose();
            }
        }
    }
}
