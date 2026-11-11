package com.eynny.npships.client.editor.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Raised bevel text actions, sharing the editor palette without persistent mouse-focus borders. */
public class EditorButton extends Button {
    private boolean held;
    private boolean splitArrow, leftAligned, listSelection;

    public EditorButton leftAligned() {
        leftAligned = true;
        return this;
    }

    public EditorButton listRow(java.util.function.BooleanSupplier selection) {
        leftAligned = true;
        listSelection = true;
        selected = selection;
        return this;
    }

    /** An explicit split-button role: its chevron must fit even in a seven-unit hit region. */
    public EditorButton splitArrow() {
        splitArrow = true;
        return this;
    }

    private Tooltip overflowTooltip;
    private Component overflowMessage;
    private java.util.function.BooleanSupplier selected = () -> false;

    public EditorButton selected(java.util.function.BooleanSupplier value) {
        selected = value;
        return this;
    }

    private final EditorButtonTheme.Fade fade = new EditorButtonTheme.Fade();
    private java.util.function.Supplier<Component> tooltipSource;
    private Component previousTooltip;

    public EditorButton tooltip(java.util.function.Supplier<Component> source) {
        tooltipSource = source;
        refreshTooltip();
        return this;
    }

    public Component tooltipText() {
        return tooltipSource == null ? getMessage() : tooltipSource.get();
    }

    private void refreshTooltip() {
        if (tooltipSource == null) return;
        var text = tooltipSource.get();
        if (!text.equals(previousTooltip)) {
            previousTooltip = text;
            setTooltip(Tooltip.create(text));
        }
    }

    protected EditorButton(
            int x, int y, int w, int h, Component text, OnPress press, CreateNarration narration) {
        super(x, y, w, h, text, press, narration);
    }

    public static Builder flat(Component text, OnPress action) {
        return new Builder(text, action);
    }

    public static final class Builder {
        private final Component text;
        private final OnPress action;
        private int x, y, w = 150, h = 20;
        private Tooltip tooltip;

        private Builder(Component text, OnPress action) {
            this.text = text;
            this.action = action;
        }

        public Builder bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(int w) {
            this.w = w;
            return this;
        }

        public Builder size(int w, int h) {
            this.w = w;
            this.h = h;
            return this;
        }

        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public EditorButton build() {
            var b = new EditorButton(x, y, w, h, text, action, DEFAULT_NARRATION);
            b.setTooltip(tooltip);
            return b;
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (active && button == 0 && isMouseOver(x, y)) held = true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        held = false;
        return super.mouseReleased(x, y, button);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
        refreshTooltip();
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                        Minecraft.getInstance().getWindow().getWindow(), 0)
                != 1) held = false;
        boolean down = held && isMouseOver(mx, my);
        EditorButtonTheme.draw(
                g,
                getX(),
                getY(),
                width,
                height,
                true,
                active,
                down,
                selected.getAsBoolean(),
                fade.hover(isHovered() && active),
                null);
        if (isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard())
            g.fill(
                    getX() + 3,
                    getY() + height - 2,
                    getX() + width - 3,
                    getY() + height - 1,
                    EditorButtonTheme.accent());
        g.pose().pushPose();
        if (down) g.pose().translate(0, 1, 0);
        int color = active ? EditorButtonTheme.text() : EditorButtonTheme.surface(0xff77818b);
        if (splitArrow) {
            int centerX = getX() + width / 2, centerY = getY() + height / 2;
            if (width < 9) {
                g.fill(centerX - 1, centerY - 1, centerX + 2, centerY, color);
                g.fill(centerX, centerY, centerX + 1, centerY + 1, color);
            } else arrow(g, centerX, centerY, color, false);
        } else if (leftAligned) {
            int gutter = listSelection ? 16 : 6;
            if (listSelection && selected.getAsBoolean())
                g.fill(
                        getX() + 6,
                        getY() + height / 2 - 2,
                        getX() + 10,
                        getY() + height / 2 + 2,
                        color);
            var font = Minecraft.getInstance().font;
            g.drawString(
                    font,
                    net.minecraft.locale.Language.getInstance()
                            .getVisualOrder(
                                    font.substrByWidth(
                                            getMessage(), Math.max(0, width - gutter - 6))),
                    getX() + gutter,
                    getY() + (height - font.lineHeight) / 2,
                    color,
                    false);
        } else renderString(g, Minecraft.getInstance().font, color);
        g.pose().popPose();
    }

    /** Code-drawn arrows share exact pixel geometry; they do not depend on the font. */
    public static void arrow(GuiGraphics g, int x, int y, int color, boolean right) {
        if (right) {
            g.fill(x - 1, y - 2, x, y + 3, color);
            g.fill(x, y - 1, x + 1, y + 2, color);
            g.fill(x + 1, y, x + 2, y + 1, color);
        } else {
            g.fill(x - 2, y - 1, x + 3, y, color);
            g.fill(x - 1, y, x + 2, y + 1, color);
            g.fill(x, y + 1, x + 1, y + 2, color);
        }
    }

    public static void cell(GuiGraphics g, int x, int y, boolean hover, boolean selected) {
        cell(g, x, y, hover, selected, true);
    }

    public static void cell(
            GuiGraphics g, int x, int y, boolean hover, boolean selected, boolean active) {
        g.fill(
                x,
                y,
                x + 18,
                y + 18,
                !active
                        ? EditorButtonTheme.muted()
                        : selected
                                ? EditorButtonTheme.accent()
                                : hover
                                        ? EditorButtonTheme.surface(0xff93aabe)
                                        : EditorButtonTheme.surface(0xff596a7c));
        g.fill(
                x + 1,
                y + 1,
                x + 17,
                y + 17,
                !active
                        ? EditorButtonTheme.panel()
                        : selected
                                ? EditorButtonTheme.mix(
                                        EditorButtonTheme.panel(),
                                        EditorButtonTheme.accent(),
                                        hover ? .6f : .35f)
                                : hover
                                        ? EditorButtonTheme.surface(0xff304457)
                                        : EditorButtonTheme.surface(0xff18232f));
    }

    boolean ownsOverflowTooltip() {
        return getTooltip() != null && getTooltip() == overflowTooltip;
    }

    @Override
    public void renderString(GuiGraphics g, net.minecraft.client.gui.Font font, int color) {
        int available = Math.max(0, width - 8);
        boolean overflow = font.width(getMessage()) > available;
        if (getTooltip() == null || getTooltip() == overflowTooltip) {
            if (overflow && !getMessage().equals(overflowMessage)) {
                overflowMessage = getMessage().copy();
                overflowTooltip = Tooltip.create(overflowMessage);
                setTooltip(overflowTooltip);
            } else if (!overflow && overflowTooltip != null) {
                overflowMessage = null;
                overflowTooltip = null;
                setTooltip(null);
            }
        }
        if (available < font.width("…")) return;
        var text =
                overflow
                        ? net.minecraft.util.FormattedCharSequence.composite(
                                net.minecraft.locale.Language.getInstance()
                                        .getVisualOrder(
                                                font.substrByWidth(
                                                        getMessage(),
                                                        Math.max(0, available - font.width("…")))),
                                Component.literal("…").getVisualOrderText())
                        : getMessage().getVisualOrderText();
        g.drawCenteredString(
                font, text, getX() + width / 2, getY() + (height - font.lineHeight) / 2, color);
    }
}
