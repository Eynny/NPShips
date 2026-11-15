package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.mixin.NPShipsFontAccessor;
import com.eynny.npships.mixin.NPShipsFontSetAccessor;
import com.eynny.npships.mixin.NPShipsMultilineAccessor;
import com.eynny.npships.mixin.NPShipsMultilineWidthAccessor;
import com.eynny.npships.mixin.NPShipsTextLineAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native text/scroll state with logical line numbers and an internal scrollbar. */
public class EditorMultilineEditBox extends MultiLineEditBox {
    // Minecraft 1.21.1 multiline layout, cursor and scrolling use a nine-unit line pitch.
    static final int LINE_HEIGHT = 9;
    private static final int SCROLLBAR_WIDTH = 8;
    private final Font font;
    private final TextInputHistory history;
    private Consumer<String> valueListener = value -> {};
    private String numberedText;
    private int[] logicalLineStarts = {0};
    private int numberWidth, numberDigits;
    private double layoutScale = Double.NaN;
    private Object layoutFont;
    private String bracketText;
    private boolean bracketLua;
    private TextBracketPairs brackets;

    public EditorMultilineEditBox(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component placeholder,
            Component message) {
        this(font, x, y, width, height, placeholder, message, new TextInputHistory());
    }

    public EditorMultilineEditBox(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component placeholder,
            Component message,
            TextInputHistory history) {
        super(font, x, y, width, height, placeholder, message);
        this.font = font;
        this.history = history;
        super.setValueListener(
                value -> {
                    refreshTextLayout();
                    valueListener.accept(value);
                });
        refreshTextLayout();
    }

    protected final TextInputHistory textHistory() {
        return history;
    }

    private TextInputHistory.Snapshot textSnapshot() {
        var field = textField();
        return new TextInputHistory.Snapshot(
                getValue(),
                field.cursor(),
                ((NPShipsMultilineWidthAccessor) (Object) field).npships$selectionCursor(),
                scrollAmount());
    }

    /**
     * A compound edit records its original caret before completion, indentation or native input.
     */
    protected final boolean editText(BooleanSupplier edit) {
        var before = isFocused() && active && visible ? history.begin(textSnapshot()) : null;
        try {
            return edit.getAsBoolean();
        } finally {
            history.end(before, textSnapshot());
        }
    }

    protected final boolean textHistoryKeyPressed(int key, int modifiers) {
        if (!isFocused()
                || !active
                || !visible
                || (modifiers & GLFW.GLFW_MOD_CONTROL) == 0 && !Screen.hasControlDown()
                || key != GLFW.GLFW_KEY_Z && key != GLFW.GLFW_KEY_Y) return false;
        var value =
                history.take(
                        key == GLFW.GLFW_KEY_Y
                                || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0
                                || Screen.hasShiftDown(),
                        textSnapshot());
        if (value != null) {
            history.restoring = true;
            try {
                setValue(value.text());
                var field = textField();
                field.setSelecting(false);
                field.seekCursor(Whence.ABSOLUTE, value.anchor());
                field.setSelecting(true);
                field.seekCursor(Whence.ABSOLUTE, value.cursor());
                field.setSelecting(false);
                if (!Double.isNaN(value.scroll())) setScrollAmount(value.scroll());
                setScrollAmount(
                        TextInputHistory.visibleLineScroll(
                                scrollAmount(),
                                field.getLineAtCursor(),
                                LINE_HEIGHT,
                                getHeight(),
                                totalInnerPadding()));
            } finally {
                history.restoring = false;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return textHistoryKeyPressed(key, modifiers)
                || editText(() -> super.keyPressed(key, scan, modifiers));
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return editText(() -> super.charTyped(character, modifiers));
    }

    @Override
    public void setValue(String value) {
        editText(
                () -> {
                    super.setValue(value);
                    return true;
                });
    }

    protected final MultilineTextField textField() {
        return ((NPShipsMultilineAccessor) (Object) this).npships$textField();
    }

    @Override
    public void setValueListener(Consumer<String> listener) {
        valueListener = listener;
    }

    /** Only newline characters define document lines; native wrapping remains the sole layout. */
    protected final boolean refreshTextLayout() {
        String value = getValue();
        if (value != numberedText) {
            numberedText = value;
            int count = 1;
            for (int index = 0; index < value.length(); index++)
                if (value.charAt(index) == '\n') count++;
            logicalLineStarts = new int[count];
            for (int index = 0, line = 1; index < value.length(); index++)
                if (value.charAt(index) == '\n') logicalLineStarts[line++] = index + 1;
        }
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        var providers =
                ((NPShipsFontSetAccessor)
                                (Object)
                                        ((NPShipsFontAccessor) (Object) font)
                                                .npships$fontSet(Style.DEFAULT_FONT))
                        .npships$providers();
        boolean fontChanged = layoutScale != scale || layoutFont != providers;
        int digits = Integer.toString(logicalLineStarts.length).length();
        if (fontChanged || digits != numberDigits) {
            int widest = 0;
            for (char digit = '0'; digit <= '9'; digit++)
                widest = Math.max(widest, font.width(String.valueOf(digit)));
            numberDigits = digits;
            numberWidth = widest * digits + 8;
        }
        layoutScale = scale;
        layoutFont = providers;
        var text = (NPShipsMultilineWidthAccessor) (Object) textField();
        if (text.npships$width() == textWidth(getWidth()) && !fontChanged) return false;
        text.npships$width(textWidth(getWidth()));
        text.npships$reflow();
        setScrollAmount(scrollAmount());
        return true;
    }

    protected final int gutterWidth() {
        return getWidth() - totalInnerPadding() - SCROLLBAR_WIDTH >= numberWidth + 12
                ? numberWidth
                : 0;
    }

    protected final int textLeft() {
        return getX() + innerPadding() + gutterWidth();
    }

    final int textWidth(int outerWidth) {
        int available = outerWidth - totalInnerPadding() - SCROLLBAR_WIDTH;
        int gutter = available >= numberWidth + 12 ? numberWidth : 0;
        return Math.max(1, available - gutter);
    }

    /** Zero marks a wrapped continuation, so it cannot be confused with a parser line number. */
    final int lineNumberAtVisualLine(int visualLine) {
        if (visualLine < 0 || visualLine >= textField().getLineCount()) return 0;
        var line = (NPShipsTextLineAccessor) (Object) textField().getLineView(visualLine);
        int logical = Arrays.binarySearch(logicalLineStarts, line.npships$begin());
        return logical >= 0 ? logical + 1 : 0;
    }

    protected final void renderLineNumbers(GuiGraphics graphics) {
        if (gutterWidth() == 0) return;
        int first = Math.max(0, (int) (scrollAmount() / LINE_HEIGHT) - 1);
        int end =
                Math.min(
                        textField().getLineCount(),
                        (int) ((scrollAmount() + getHeight()) / LINE_HEIGHT) + 1);
        int color =
                EditorButtonTheme.mix(EditorButtonTheme.panel(), EditorButtonTheme.text(), .55f);
        for (int row = first; row < end; row++) {
            int number = lineNumberAtVisualLine(row);
            int y = getY() + innerPadding() + row * LINE_HEIGHT;
            if (number == 0 || !withinContentAreaTopBottom(y, y + LINE_HEIGHT)) continue;
            String label = Integer.toString(number);
            graphics.drawString(font, label, textLeft() - 6 - font.width(label), y, color, false);
        }
    }

    protected boolean luaSyntax() {
        return false;
    }

    final int matchingBracket(int offset) {
        String value = getValue();
        boolean lua = luaSyntax();
        if (value != bracketText || lua != bracketLua || brackets == null) {
            bracketText = value;
            bracketLua = lua;
            brackets = TextBracketPairs.scan(value, lua);
        }
        return brackets.matching(offset);
    }

    final int activeBracket() {
        var field = textField();
        if (field.hasSelection()) {
            var selected = (NPShipsTextLineAccessor) (Object) field.getSelected();
            int begin = selected.npships$begin();
            return selected.npships$end() == begin + 1 && matchingBracket(begin) >= 0 ? begin : -1;
        }
        int cursor = field.cursor();
        if (matchingBracket(cursor) >= 0) return cursor;
        return cursor > 0 && matchingBracket(cursor - 1) >= 0 ? cursor - 1 : -1;
    }

    protected final void renderBracketHighlights(GuiGraphics graphics) {
        if (!isFocused()) return;
        int selected = activeBracket();
        if (selected < 0) return;
        int partner = matchingBracket(selected);
        int first = Math.max(0, (int) (scrollAmount() / LINE_HEIGHT) - 1);
        int end =
                Math.min(
                        textField().getLineCount(),
                        (int) ((scrollAmount() + getHeight()) / LINE_HEIGHT) + 1);
        String value = getValue();
        int color = EditorButtonTheme.accent();
        for (int row = first; row < end; row++) {
            var line = (NPShipsTextLineAccessor) (Object) textField().getLineView(row);
            int begin = line.npships$begin(), finish = line.npships$end();
            for (int pairIndex = 0; pairIndex < 2; pairIndex++) {
                int offset = pairIndex == 0 ? selected : partner;
                if (offset < begin || offset >= finish) continue;
                int x = textLeft() + font.width(value.substring(begin, offset));
                int y = getY() + innerPadding() + row * LINE_HEIGHT;
                int right = x + Math.max(1, font.width(value.substring(offset, offset + 1)));
                graphics.fill(x - 1, y - 1, right + 1, y, color);
                graphics.fill(x - 1, y + LINE_HEIGHT, right + 1, y + LINE_HEIGHT + 1, color);
                graphics.fill(x - 1, y, x, y + LINE_HEIGHT, color);
                graphics.fill(right, y, right + 1, y + LINE_HEIGHT, color);
            }
        }
    }

    final int preferredCompactHeight(int maximum) {
        return Math.min(
                Math.max(0, maximum),
                Math.max(
                        5 * LINE_HEIGHT + totalInnerPadding(),
                        getInnerHeight() + 2 * LINE_HEIGHT + totalInnerPadding()));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        refreshTextLayout();
        super.renderWidget(graphics, mouseX, mouseY, tick);
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        if (gutterWidth() == 0) return;
        int separator = textLeft() - 4;
        graphics.fill(
                getX() + 1, getY() + 1, separator, getBottom() - 1, EditorButtonTheme.panel());
        graphics.fill(
                separator, getY() + 1, separator + 1, getBottom() - 1, EditorButtonTheme.edge());
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderLineNumbers(graphics);
        int outerWidth = getWidth();
        int gutter = gutterWidth();
        graphics.pose().pushPose();
        graphics.pose().translate(gutter, 0, 0);
        setWidth(Math.max(0, outerWidth - gutter - SCROLLBAR_WIDTH));
        try {
            super.renderContents(graphics, mouseX, mouseY, tick);
        } finally {
            setWidth(outerWidth);
            graphics.pose().popPose();
        }
        renderBracketHighlights(graphics);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!visible
                || !active
                || !isMouseOver(x, y)
                || vertical == 0
                || vertical > 0 && scrollAmount() <= 0
                || vertical < 0 && scrollAmount() >= getMaxScrollAmount()) return false;
        double before = scrollAmount();
        super.mouseScrolled(x, y, horizontal, vertical);
        return scrollAmount() != before;
    }

    @Override
    protected void renderDecorations(GuiGraphics graphics) {
        // Native decoration uses x + width; only its drawing width changes, not editing state.
        int outerWidth = getWidth();
        setWidth(Math.max(0, outerWidth - SCROLLBAR_WIDTH));
        try {
            super.renderDecorations(graphics);
        } finally {
            setWidth(outerWidth);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (!visible || !isMouseOver(x, y)) return false;
        refreshTextLayout();
        // Reuse native scrollbar capture/drag; reject the old external hit region.
        if (scrollbarVisible() && x >= getRight() - SCROLLBAR_WIDTH)
            return super.mouseClicked(x + SCROLLBAR_WIDTH, y, button);
        return super.mouseClicked(Math.max(getX() + innerPadding(), x - gutterWidth()), y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        double textX = isMouseOver(x, y) ? Math.max(getX() + innerPadding(), x - gutterWidth()) : x;
        return super.mouseDragged(textX, y, button, dx, dy);
    }
}
