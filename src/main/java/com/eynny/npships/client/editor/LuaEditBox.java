package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SnbtPropertyValues;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.mixin.NPShipsTextLineAccessor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Small Lua/reference completion list; no execution or claims of full Lua language analysis. */
public final class LuaEditBox extends EditorMultilineEditBox {
    private static final int REFERENCE_RESIZE_BORDER = 4;
    private final Font font;
    private com.eynny.npships.authoring.document.DataSuggestions dataSuggestions;
    private List<com.eynny.npships.authoring.document.DataSuggestions.Entry> dataEntries =
            List.of();
    private int replaceEnd, helpScroll;
    private com.eynny.npships.authoring.document.DataSuggestions.Help hoveredField;
    private String hoverText;
    private int hoverOffset = -1, hoverX, hoverY, hoverWidth, hoverHeight, hoverScroll;
    private long hoverSince;
    private int hoverClipTop, hoverClipBottom;
    private List<net.minecraft.util.FormattedCharSequence> hoverLines = List.of();
    private boolean hoverVisible;
    private boolean generalHelp;
    private LuaEditBox referenceText;
    private boolean referenceSelecting;
    private boolean helpPositioned, helpDragging, helpResizing;
    private int resizeEdges;
    private int helpScreenWidth, helpScreenHeight;
    private EditorLayout.Rect preferredHelp;
    private boolean helpAvailable = true;

    private void preferHelp() {
        preferredHelp =
                new EditorLayout.Rect(hoverX, hoverY, hoverX + hoverWidth, hoverY + hoverHeight);
    }

    private java.util.function.Consumer<String> clipboard = value -> {};

    public void clipboard(java.util.function.Consumer<String> sink) {
        clipboard = sink;
    }

    public boolean dragHelp(double dx, double dy) {
        if (!helpAvailable || !generalHelp || !helpDragging && !helpResizing) return false;
        if (helpResizing) {
            int right = hoverX + hoverWidth, bottom = hoverY + hoverHeight;
            int minWidth = Math.min(200, helpScreenWidth - 16);
            int minHeight = Math.min(100, helpScreenHeight - 16);
            if ((resizeEdges & 1) != 0) hoverX = Math.clamp(hoverX + (int) dx, 8, right - minWidth);
            if ((resizeEdges & 2) != 0)
                right = Math.clamp(right + (int) dx, hoverX + minWidth, helpScreenWidth - 8);
            if ((resizeEdges & 4) != 0)
                hoverY = Math.clamp(hoverY + (int) dy, 8, bottom - minHeight);
            if ((resizeEdges & 8) != 0)
                bottom = Math.clamp(bottom + (int) dy, hoverY + minHeight, helpScreenHeight - 8);
            hoverWidth = right - hoverX;
            hoverHeight = bottom - hoverY;
            hoverText = null;
        } else {
            hoverX =
                    Math.clamp(hoverX + (int) dx, 8, Math.max(8, helpScreenWidth - hoverWidth - 8));
            hoverY =
                    Math.clamp(
                            hoverY + (int) dy, 8, Math.max(8, helpScreenHeight - hoverHeight - 8));
        }
        preferHelp();
        return true;
    }

    public boolean referenceKeyPressed(int key, int scan, int modifiers) {
        if (!generalHelp || referenceText == null || !referenceText.isFocused() || key == 256)
            return false;
        boolean control =
                Screen.hasControlDown() || (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && key == 67) {
            var range = (NPShipsTextLineAccessor) (Object) referenceText.text().getSelected();
            if (referenceText.text().hasSelection())
                clipboard.accept(
                        referenceText
                                .getValue()
                                .substring(range.npships$begin(), range.npships$end()));
        } else if (control && key == 65) {
            referenceText.text().setSelecting(false);
            referenceText.text().seekCursor(Whence.ABSOLUTE, 0);
            referenceText.text().setSelecting(true);
            referenceText.text().seekCursor(Whence.ABSOLUTE, referenceText.getValue().length());
            referenceText.text().setSelecting(false);
        } else if (key >= 262 && key <= 269) {
            referenceText.keyPressed(key, scan, modifiers);
        }
        return true;
    }

    public boolean referenceTyping() {
        return generalHelp && referenceText != null && referenceText.isFocused();
    }

    public boolean selectReference(double x, double y, int button, double dx, double dy) {
        return generalHelp
                && referenceSelecting
                && referenceText != null
                && referenceText.mouseDragged(x, y, button, dx, dy);
    }

    public boolean pointerCaptured() {
        return helpDragging || helpResizing || referenceSelecting;
    }

    public boolean popupContains(double x, double y) {
        return referenceContains(x, y)
                || !generalHelp
                        && isFocused()
                        && !matches.isEmpty()
                        && x >= popupX
                        && x < popupX + popupWidth
                        && popupRows > 0
                        && popupWidth >= 24
                        && y >= popupY + (valuePickerOpen() ? 0 : 12)
                        && y < popupY + 14 + popupRows * 12 + popupDetailsHeight();
    }

    boolean referenceContains(double x, double y) {
        return generalHelp
                && (inHover(x, y)
                        || referenceText != null && referenceText.fieldHelpContains(x, y));
    }

    private boolean fieldHelpContains(double x, double y) {
        return !generalHelp && hoverVisible && inHover(x, y);
    }

    public boolean referenceOpen() {
        return generalHelp;
    }

    boolean keyboardPopup() {
        return generalHelp || isFocused() && !matches.isEmpty();
    }

    private boolean completionAcceptKey(int key, int modifiers) {
        return key == 258 && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0
                || key == 257 && explicitCompletion;
    }

    boolean keyboardPopupActivation(int key, int modifiers) {
        return isFocused() && !matches.isEmpty() && completionAcceptKey(key, modifiers);
    }

    void renderReference(GuiGraphics g, int mx, int my) {
        if (generalHelp) hoverHelp(g, 0, g.guiHeight(), mx, my);
    }

    public void releaseHelp() {
        helpDragging = helpResizing = referenceSelecting = false;
    }

    private boolean explicitCompletion;
    private boolean suppressSpace;

    private final List<String> candidates;
    private List<String> matches = List.of();
    private int choice, prefixStart;
    private int popupX, popupY, popupWidth, popupRows, popupStart;
    private static boolean autoSuggestions = true;
    private java.util.function.BiFunction<String, Integer, SnbtPropertyValues.Result> valueSource;
    private SnbtPropertyValues.Result valuePicker;
    private int valueAnchor;
    private boolean completingValues;
    private Runnable valueFocus;

    void valueFocus(Runnable owner) {
        valueFocus = owner;
    }

    private int valuePopupWidth;
    private int popupScreenWidth, popupClipTop, popupClipBottom;
    private String analysedText;
    private LuaCodeSyntax.Analysis analysis;

    void dataSuggestions(com.eynny.npships.authoring.document.DataSuggestions suggestions) {
        var retained = valuePickerOpen() ? valuePicker : null;
        boolean completing = completingValues;
        boolean ordinary = retained == null && isFocused() && !matches.isEmpty();
        boolean explicit = explicitCompletion;
        String selected = matches.isEmpty() ? null : matches.get(choice);
        dataSuggestions = suggestions;
        if (referenceText != null) referenceText.dataSuggestions(suggestions);
        // Live acceptance refreshes help, not input ownership. Recompute only an open,
        // focused completion; a dismissed list must stay dismissed.
        if (retained != null && openValues(valueAnchor, completing)) {
            int index = matches.indexOf(selected);
            if (valuePicker.source().equals(retained.source())
                    && valuePicker.start() == retained.start()
                    && valuePicker.end() == retained.end()
                    && valuePicker.property().equals(retained.property())
                    && index >= 0) {
                choice = index;
                arrangePopup();
            } else clearCompletion();
        } else if (ordinary) {
            suggest(explicit);
            int index = matches.indexOf(selected);
            if (index >= 0) choice = index;
            arrangePopup();
        } else clearCompletion();
        hoverText = null;
        hoverVisible = false;
    }

    public void toggleStructureHelp() {
        generalHelp = !generalHelp;
        releaseHelp();
        if (referenceText != null) referenceText.setFocused(false);
        if (generalHelp) helpPositioned = false;
        hoverText = null;
        hoverVisible = false;
        hoverScroll = 0;
        clearCompletion();
        if (referenceText != null) referenceText.clearFieldHover();
    }

    private void clearFieldHover() {
        hoveredField = null;
        hoverText = null;
        hoverOffset = -1;
        hoverVisible = false;
    }

    @Override
    protected boolean luaSyntax() {
        return dataSuggestions == null;
    }

    boolean dataMode() {
        return dataSuggestions != null;
    }

    boolean suggestionsEnabled() {
        return autoSuggestions;
    }

    void toggleSuggestions() {
        autoSuggestions = !autoSuggestions;
        if (valuePickerOpen()) return;
        if (autoSuggestions) suggest(false);
        else clearCompletion();
    }

    static boolean automaticSuggestions() {
        return autoSuggestions;
    }

    static void toggleAutomaticSuggestions() {
        autoSuggestions = !autoSuggestions;
    }

    void valueSource(
            java.util.function.BiFunction<String, Integer, SnbtPropertyValues.Result> source) {
        valueSource = source;
        valuePicker = null;
    }

    public boolean valuePickerOpen() {
        return valuePicker != null && !matches.isEmpty() && valuePicker.source().equals(getValue());
    }

    /**
     * Uses native wrapped-line ranges and glyph advances; never moves the caret just to inspect.
     */
    boolean pickValue(double x, double y) {
        if (valueSource == null
                || !visible
                || !active
                || !isMouseOver(x, y)
                || x >= getX() + getWidth() - 8) return false;
        int offset = sourceOffset((int) x, (int) y, getY(), getY() + getHeight());
        return offset >= 0 && openValues(offset);
    }

    private int sourceOffset(int x, int y, int clipTop, int clipBottom) {
        int left = textLeft();
        if (x < left
                || x >= getRight() - 8
                || y < Math.max(getY(), clipTop)
                || y >= Math.min(getY() + getHeight(), clipBottom)) return -1;
        int rowY = getY() + innerPadding() - (int) scrollAmount();
        for (Object raw : (Iterable<?>) text().iterateLines()) {
            var line = (NPShipsTextLineAccessor) raw;
            if (y >= rowY && y < rowY + 9) {
                String row = getValue().substring(line.npships$begin(), line.npships$end());
                return x - left < font.width(row)
                        ? line.npships$begin() + font.plainSubstrByWidth(row, x - left).length()
                        : -1;
            }
            rowY += 9;
        }
        return -1;
    }

    private boolean openValues(int offset) {
        return openValues(offset, false);
    }

    private boolean openValues(int offset, boolean completing) {
        if (valueSource == null) return false;
        var result = valueSource.apply(getValue(), offset);
        if (result == null && dataSuggestions != null)
            result = dataSuggestions.nativeValues(getValue(), offset);
        if (result == null) return false;
        if (completing) result = SnbtPropertyValues.forCompletion(result, text().cursor());
        completingValues = completing;
        valuePicker = result;
        valueAnchor = offset;
        prefixStart = result.start();
        replaceEnd = result.end();
        dataEntries = result.entries();
        matches =
                dataEntries.stream()
                        .map(com.eynny.npships.authoring.document.DataSuggestions.Entry::insert)
                        .toList();
        measureValues();
        choice = Math.max(0, matches.indexOf(getValue().substring(prefixStart, replaceEnd)));
        helpScroll = 0;
        explicitCompletion = true;
        generalHelp = hoverVisible = false;
        if (valueFocus == null) setFocused(true);
        else valueFocus.run();
        arrangePopup();
        return true;
    }

    public void popupSurface(int screenWidth, int top, int bottom) {
        popupScreenWidth = Math.max(0, screenWidth);
        popupClipTop = top;
        popupClipBottom = Math.max(top, bottom);
        arrangePopup();
    }

    private int popupDetailsHeight() {
        return dataSuggestions == null || valuePickerOpen() ? 0 : 36;
    }

    private void measureValues() {
        valuePopupWidth = Math.max(180, matches.stream().mapToInt(font::width).max().orElse(0) + 8);
    }

    private void arrangePopup() {
        if (matches.isEmpty()) {
            popupRows = 0;
            return;
        }
        popupStart = Math.max(0, choice - 4);
        popupRows =
                Math.min(
                        Math.min(6, matches.size() - popupStart),
                        Math.max(
                                0,
                                (popupClipBottom - popupClipTop - 16 - popupDetailsHeight()) / 12));
        int desiredWidth =
                valuePickerOpen() ? Math.min(310, valuePopupWidth) : Math.max(180, getWidth());
        popupWidth = Math.max(0, Math.min(desiredWidth, popupScreenWidth - 12));
        int anchorX = textLeft(),
                anchorY =
                        getY()
                                + innerPadding()
                                + text().getLineAtCursor() * 9
                                - (int) scrollAmount();
        if (valuePickerOpen()) {
            int row = 0;
            for (Object raw : (Iterable<?>) text().iterateLines()) {
                var line = (NPShipsTextLineAccessor) raw;
                if (valueAnchor >= line.npships$begin() && valueAnchor < line.npships$end()) {
                    anchorX =
                            textLeft()
                                    + font.width(
                                            getValue()
                                                    .substring(line.npships$begin(), valueAnchor));
                    anchorY = getY() + innerPadding() + row * 9 - (int) scrollAmount();
                    break;
                }
                row++;
            }
        }
        popupX = Math.max(6, Math.min(anchorX, popupScreenWidth - popupWidth - 6));
        int height = 14 + popupRows * 12 + popupDetailsHeight();
        popupY = Math.max(popupClipTop, Math.min(anchorY + 12, popupClipBottom - height));
    }

    private void clearCompletion() {
        matches = List.of();
        valuePicker = null;
        popupRows = 0;
    }

    private LuaCodeSyntax.Analysis analysis() {
        if (!getValue().equals(analysedText)) {
            analysedText = getValue();
            analysis = LuaCodeSyntax.analyse(analysedText);
        }
        return analysis;
    }

    private String snbtText;
    private int snbtColor;
    private int[] snbtColors;

    /** Lexical colors depend on text and theme, not font metrics or scroll position. */
    private int[] snbtColours() {
        String text = getValue();
        int color = EditorButtonTheme.text();
        if (!text.equals(snbtText) || snbtColor != color) {
            snbtText = text;
            snbtColor = color;
            snbtColors = SnbtCodeSyntax.colours(text, color);
        }
        return snbtColors;
    }

    public boolean scrollSuggestions(double x, double y, double delta) {
        if (generalHelp && referenceText != null && referenceText.scrollSuggestions(x, y, delta))
            return true;
        if (!generalHelp
                && hoverVisible
                && hoveredField != null
                && Objects.equals(hoverText, getValue())) {
            int offset = sourceOffset((int) x, (int) y, hoverClipTop, hoverClipBottom);
            int rows = Math.max(1, (hoverHeight - 8) / 10);
            if (offset >= hoveredField.start()
                    && offset < hoveredField.end()
                    && hoverLines.size() > rows) {
                hoverScroll =
                        Math.clamp(
                                hoverScroll - (int) Math.signum(delta) * 3,
                                0,
                                hoverLines.size() - rows);
                return true;
            }
        }
        if (generalHelp && referenceText != null && inHover(x, y)) {
            referenceText.mouseScrolled(x, y, 0, delta);
            return true;
        }
        if (!isFocused()
                || matches.isEmpty()
                || popupRows == 0
                || x < popupX
                || x >= popupX + popupWidth
                || y < popupY
                || y >= popupY + 14 + popupRows * 12 + popupDetailsHeight()) return false;
        if (popupDetailsHeight() > 0 && y >= popupY + 14 + popupRows * 12) {
            helpScroll = Math.max(0, helpScroll - (int) Math.signum(delta));
            return true;
        }
        helpScroll = 0;
        choice = Math.max(0, Math.min(matches.size() - 1, choice - (int) Math.signum(delta)));
        return true;
    }

    private double preferredScroll;
    private boolean arranging;

    @Override
    protected void setScrollAmount(double amount) {
        super.setScrollAmount(amount);
        if (!arranging) preferredScroll = scrollAmount();
    }

    void bounds(int x, int y, int width, int height) {
        if (!generalHelp
                && (x != getX() || y != getY() || width != getWidth() || height != getHeight()))
            clearFieldHover();
        arranging = true;
        try {
            setX(x);
            setY(y);
            setHeight(Math.max(0, height));
            setWidth(width);
            if (refreshTextLayout() && valuePickerOpen()) measureValues();
            super.setScrollAmount(preferredScroll);
        } finally {
            arranging = false;
        }
    }

    int compactHeight(int width, int maximum) {
        bounds(getX(), getY(), width, getHeight());
        return preferredCompactHeight(maximum);
    }

    @Override
    protected double scrollRate() {
        return 36;
    }

    public boolean choose(double x, double y, int button) {
        if (generalHelp) {
            if (referenceText != null && referenceText.fieldHelpContains(x, y)) return true;
            if (!inHover(x, y)) {
                if (referenceText != null) referenceText.setFocused(false);
                return false;
            }
            if (button == 0) {
                resizeEdges = referenceResizeEdges(x, y);
                if (resizeEdges != 0) {
                    helpResizing = true;
                } else if (y < hoverY + 16 && x >= hoverX + hoverWidth - 18) {
                    generalHelp = hoverVisible = false;

                } else if (y < hoverY + 16) helpDragging = true;
                else if (referenceText != null) {
                    referenceText.setFocused(true);
                    referenceSelecting = referenceText.mouseClicked(x, y, button);
                }
            }
            return true;
        }
        if (!isFocused()
                || matches.isEmpty()
                || button != 0
                || x < popupX
                || x >= popupX + popupWidth
                || y < popupY + 12
                || y >= popupY + 12 + popupRows * 12) return false;
        choice = popupStart + (int) ((y - popupY - 12) / 12);
        accept();
        return true;
    }

    @Override
    public void setValueListener(java.util.function.Consumer<String> listener) {
        super.setValueListener(
                value -> {
                    if (valuePicker != null && !valuePicker.source().equals(value)) {
                        clearCompletion();
                    }
                    listener.accept(value);
                });
    }

    public LuaEditBox(
            Font font,
            int x,
            int y,
            int width,
            com.eynny.npships.authoring.document.DataSuggestions suggestions) {
        this(font, x, y, width, List.of());
        dataSuggestions = suggestions;
        setMessage(Component.literal(dataSuggestions.jsonMode() ? "JSON" : "SNBT"));
        valueSource((text, offset) -> dataSuggestions.nativeValues(text, offset));
    }

    LuaEditBox(Font font, int x, int y, int width, List<String> references) {
        super(
                font,
                x,
                y,
                width,
                140,
                Component.empty(),
                Component.literal("Lua"),
                new TextInputHistory(32, 4L * 1024 * 1024));
        this.font = font;
        var values = new TreeSet<>(references);
        values.addAll(
                List.of(
                        "peripheral.wrap(\"\")",
                        "peripheral.find(\"\")",
                        "peripheral.getNames()",
                        "rednet.open(\"\")",
                        "rednet.send()",
                        "rednet.receive()",
                        "os.getComputerID()",
                        "os.pullEvent()",
                        "sleep(1)",
                        "print()",
                        "assert()",
                        "fs.open(\"\", \"r\")",
                        "turtle.getFuelLevel()",
                        "turtle.getItemDetail(1)",
                        "turtle.turnLeft()",
                        "turtle.turnRight()",
                        "local ",
                        "function ",
                        "return "));
        values.addAll(LuaCodeSyntax.KEYWORDS);
        values.addAll(
                List.of(
                        "pairs()",
                        "ipairs()",
                        "tonumber()",
                        "tostring()",
                        "type()",
                        "pcall()",
                        "error()",
                        "require(\"\")",
                        "table.insert()",
                        "table.remove()",
                        "table.concat()",
                        "string.format()",
                        "string.find()",
                        "string.sub()",
                        "math.floor()",
                        "math.ceil()",
                        "math.min()",
                        "math.max()",
                        "math.random()",
                        "os.startTimer()",
                        "os.cancelTimer()",
                        "os.clock()",
                        "os.shutdown()",
                        "os.reboot()",
                        "rednet.broadcast()",
                        "rednet.lookup()",
                        "rednet.close()",
                        "peripheral.isPresent()",
                        "peripheral.getType()",
                        "peripheral.getMethods()",
                        "redstone.setOutput()",
                        "redstone.getInput()",
                        "redstone.setAnalogOutput()",
                        "redstone.getAnalogInput()",
                        "term.clear()",
                        "term.setCursorPos()",
                        "term.write()",
                        "term.setTextColor()",
                        "term.setBackgroundColor()",
                        "term.getSize()",
                        "term.redirect()",
                        "fs.exists()",
                        "fs.list()",
                        "fs.delete()",
                        "fs.makeDir()",
                        "textutils.serialize()",
                        "textutils.unserialize()",
                        "textutils.serializeJSON()",
                        "textutils.unserializeJSON()",
                        "turtle.forward()",
                        "turtle.back()",
                        "turtle.up()",
                        "turtle.down()",
                        "turtle.inspect()",
                        "turtle.inspectUp()",
                        "turtle.inspectDown()",
                        "turtle.dig()",
                        "turtle.place()",
                        "turtle.select(1)",
                        "turtle.refuel()",
                        "turtle.getItemCount()"));
        candidates = List.copyOf(values);
        setCharacterLimit(2 * 1024 * 1024);
    }

    private MultilineTextField text() {
        return textField();
    }

    @Override
    public void setValue(String value) {
        super.setValue(value.replace("\r\n", "\n").replace("\r", "\n"));
    }

    void insert(String value) {
        editText(
                () -> {
                    text().insertText(value);
                    clearCompletion();
                    return true;
                });
    }

    public void complete() {
        setFocused(true);
        if (openValues(text().cursor(), true)
                || text().cursor() > 0 && openValues(text().cursor() - 1, true)) return;
        if (dataSuggestions != null) {
            int next = text().cursor();
            while (next < getValue().length() && Character.isWhitespace(getValue().charAt(next)))
                next++;
            if (next != text().cursor() && openValues(next, true)) return;
        }
        suggest(true);
    }

    private void suggest(boolean explicit) {
        explicitCompletion = explicit;
        clearCompletion();
        if (!explicit && !autoSuggestions) return;
        String value = getValue();
        int end = text().cursor();
        if (dataSuggestions != null) {
            var result = dataSuggestions.complete(value, end);
            prefixStart = result.start();
            replaceEnd = result.end();
            dataEntries = result.entries();
            matches =
                    dataEntries.stream()
                            .map(com.eynny.npships.authoring.document.DataSuggestions.Entry::insert)
                            .toList();
            choice = 0;
            helpScroll = 0;
            return;
        }
        var parsed = analysis();
        if (!explicit && end > 0 && parsed.colours()[end - 1] == LuaCodeSyntax.COMMENT) return;
        var available = new TreeSet<>(candidates);
        available.addAll(parsed.identifiers());
        prefixStart = end;
        while (prefixStart > 0
                && (Character.isLetterOrDigit(value.charAt(prefixStart - 1))
                        || "_.(\"".indexOf(value.charAt(prefixStart - 1)) >= 0)) prefixStart--;
        clearCompletion();
        for (int start = prefixStart; start <= end; start++) {
            String prefix = value.substring(start, end);
            if (!explicit && prefix.isEmpty() || prefix.isEmpty() && start != prefixStart) continue;
            var found =
                    available.stream()
                            .filter(c -> c.startsWith(prefix) && !c.equals(prefix))
                            .toList();
            if (!found.isEmpty()) {
                matches = found;
                prefixStart = start;
                break;
            }
        }
        if (explicit && matches.isEmpty()) {
            prefixStart = end;
            matches = List.copyOf(available);
        }
        choice = 0;
    }

    public boolean dismissCompletion() {
        if (generalHelp) {
            generalHelp = false;
            hoverVisible = false;
            return true;
        }
        if (matches.isEmpty()) return false;
        clearCompletion();
        return true;
    }

    private void accept() {
        editText(
                () -> {
                    acceptCompletion();
                    return true;
                });
    }

    private void acceptCompletion() {
        if (valuePicker != null && !valuePickerOpen()) {
            clearCompletion();
            return;
        }
        String replacement = matches.get(choice);
        int end = dataSuggestions == null ? text().cursor() : replaceEnd;
        text().setSelecting(false);
        text().seekCursor(Whence.ABSOLUTE, prefixStart);
        text().setSelecting(true);
        text().seekCursor(Whence.ABSOLUTE, end);
        text().insertText(replacement);
        text().setSelecting(false);
        clearCompletion();
        int separator = replacement.lastIndexOf(": ");
        String insertedValue = separator < 0 ? replacement : replacement.substring(separator + 2);
        if (dataSuggestions != null
                && java.util.List.of("{}", "[]", "[B;]", "[I;]", "[L;]", "\"\"", "''")
                        .contains(insertedValue)) {
            // A generated empty value is ready for its contents, rather than the next sibling.
            text().seekCursor(Whence.RELATIVE, -1);
            suggest(false);
        }
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (ch == ' '
                && (suppressSpace || (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0)) {
            suppressSpace = false;
            return true;
        }
        suppressSpace = false;
        if (ch == '/' && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) != 0) return true;
        boolean handled = super.charTyped(ch, modifiers);
        if (handled) suggest(false);
        return handled;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (textHistoryKeyPressed(key, modifiers)) {
            clearCompletion();
            return true;
        }
        return editText(() -> codeKeyPressed(key, scan, modifiers));
    }

    private boolean codeKeyPressed(int key, int scan, int modifiers) {
        suppressSpace = false;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT) return false;
        if (generalHelp && key == 256) {
            generalHelp = false;
            hoverVisible = false;
            return true;
        }
        if (key == 32 && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0) {
            suppressSpace = true;
            complete();
            return true;
        }
        if (!matches.isEmpty()) {
            if (key == 256) {
                clearCompletion();
                return true;
            }
            if (key == 264 || key == 265) {
                choice = Math.floorMod(choice + (key == 264 ? 1 : -1), matches.size());
                return true;
            }
            if (completionAcceptKey(key, modifiers)) {
                accept();
                return true;
            }
        }
        if (key == 257) {
            clearCompletion();
            newline();
            return true;
        }
        if (key == 258 && !Screen.hasControlDown()) {
            indent((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        clearCompletion();
        boolean handled = super.keyPressed(key, scan, modifiers);
        if (handled && (key == 259 || key == 261)) suggest(false);
        return handled;
    }

    private void newline() {
        String code = getValue();
        int caret = text().cursor();
        int start = code.lastIndexOf('\n', caret - 1) + 1;
        int end = start;
        while (end < caret && (code.charAt(end) == ' ' || code.charAt(end) == '\t')) end++;
        String padding = code.substring(start, end);
        String before = code.substring(start, caret).stripTrailing();
        boolean open =
                before.endsWith("{")
                        || before.endsWith("[")
                        || before.endsWith("(")
                        || !dataMode()
                                && (before.matches(".*\\b(then|do|else|repeat)$")
                                        || before.matches(".*\\bfunction\\s*[^\\n]*\\)\\s*$"));
        String closing =
                open && caret < code.length() && "}])".indexOf(code.charAt(caret)) >= 0
                        ? "\n" + padding
                        : "";
        text().insertText("\n" + padding + (open ? "    " : "") + closing);
        if (!closing.isEmpty()) text().seekCursor(Whence.RELATIVE, -closing.length());
    }

    private void indent(boolean backwards) {
        if (!backwards && !text().hasSelection()) {
            insert("    ");
            return;
        }
        String code = getValue();
        var selected = (NPShipsTextLineAccessor) (Object) text().getSelected();
        int begin = text().hasSelection() ? selected.npships$begin() : text().cursor();
        int end = text().hasSelection() ? selected.npships$end() : begin;
        int start = code.lastIndexOf('\n', begin - 1) + 1;
        int finish = code.indexOf('\n', end);
        if (finish < 0) finish = code.length();
        String chunk = code.substring(start, finish);
        String changed =
                java.util.Arrays.stream(chunk.split("\n", -1))
                        .map(
                                line ->
                                        backwards
                                                ? line.replaceFirst("^( {1,4}|\t)", "")
                                                : "    " + line)
                        .collect(java.util.stream.Collectors.joining("\n"));
        boolean hadSelection = text().hasSelection();
        int caret = text().cursor();
        setValue(code.substring(0, start) + changed + code.substring(finish));
        text().setSelecting(false);
        text().seekCursor(
                        Whence.ABSOLUTE,
                        hadSelection
                                ? start
                                : Math.max(start, caret + changed.length() - chunk.length()));
        if (hadSelection) {
            text().setSelecting(true);
            text().seekCursor(Whence.ABSOLUTE, start + changed.length());
            text().setSelecting(false);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && Screen.hasControlDown() && pickValue(x, y)) return true;
        clearCompletion();
        return super.mouseClicked(x, y, button);
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float tick) {
        renderLineNumbers(g);
        String code = getValue();
        int[] colours;
        if (dataSuggestions == null) colours = analysis().colours();
        else {
            colours = snbtColours();
        }
        int y = getY() + innerPadding();
        int left = textLeft();
        int caret = text().cursor();
        boolean caretDrawn = false;
        var selected = (NPShipsTextLineAccessor) (Object) text().getSelected();
        for (Object rawLine : (Iterable<?>) text().iterateLines()) {
            var line = (NPShipsTextLineAccessor) rawLine;
            int begin = line.npships$begin(), end = line.npships$end();
            if (withinContentAreaTopBottom(y, y + 9)) {
                if (text().hasSelection()) {
                    int from = Math.max(begin, selected.npships$begin()),
                            to = Math.min(end, selected.npships$end());
                    if (from < to)
                        g.fill(
                                left + font.width(code.substring(begin, from)),
                                y,
                                left + font.width(code.substring(begin, to)),
                                y + 9,
                                EditorButtonTheme.mix(
                                        EditorButtonTheme.panel(),
                                        EditorButtonTheme.accent(),
                                        .6f));
                }
                int x = left;
                for (int pos = begin; pos < end; ) {
                    int finish = pos + 1;
                    while (finish < end && colours[finish] == colours[pos]) finish++;
                    String run = code.substring(pos, finish);
                    g.drawString(font, run, x, y, colours[pos], false);
                    x += font.width(run);
                    pos = finish;
                }
                if (!caretDrawn && isFocused() && caret >= begin && caret <= end) {
                    caretDrawn = true;
                    int cx = left + font.width(code.substring(begin, caret));
                    if (net.minecraft.Util.getMillis() / 500 % 2 == 0)
                        g.fill(cx, y - 1, cx + 1, y + 9, 0xffeeeeee);
                }
            }
            y += 9;
        }
        renderBracketHighlights(g);
    }

    @Override
    protected void renderDecorations(GuiGraphics g) {
        // The vanilla character counter overflows into the next panel control.
        int limit = text().characterLimit();
        text().setCharacterLimit(Integer.MAX_VALUE);
        try {
            super.renderDecorations(g);
        } finally {
            text().setCharacterLimit(limit);
        }
    }

    public void suggestions(GuiGraphics g, int clipTop, int clipBottom, int mouseX, int mouseY) {
        // Finite value choices open only through explicit Ctrl-click or completion command.
        if (dataSuggestions != null && (generalHelp || matches.isEmpty()))
            hoverHelp(g, clipTop, clipBottom, mouseX, mouseY);
        else hoverVisible = false;
        if (generalHelp || !isFocused() || matches.isEmpty()) {
            popupRows = 0;
            return;
        }
        popupSurface(popupScreenWidth > 0 ? popupScreenWidth : g.guiWidth(), clipTop, clipBottom);
        if (popupRows == 0 || popupWidth < 24) return;
        int height = 14 + popupRows * 12 + popupDetailsHeight();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(
                popupX - 1,
                popupY - 1,
                popupX + popupWidth + 1,
                popupY + height + 1,
                EditorButtonTheme.edge());
        g.fill(
                popupX,
                popupY,
                popupX + popupWidth,
                popupY + height,
                SnbtCodeSyntax.HELP_BACKGROUND);
        g.drawString(
                font,
                "Tab / Enter / "
                        + Component.translatable("npships.tools.code_click").getString()
                        + "  "
                        + (choice + 1)
                        + "/"
                        + matches.size(),
                popupX + 4,
                popupY + 2,
                SnbtCodeSyntax.HELP_MUTED,
                false);
        for (int i = 0; i < popupRows; i++) {
            int y = popupY + 12 + i * 12;
            boolean hovered =
                    mouseX >= popupX
                            && mouseX < popupX + popupWidth
                            && mouseY >= y
                            && mouseY < y + 12;
            if (hovered || popupStart + i == choice)
                g.fill(
                        popupX + 1,
                        y,
                        popupX + popupWidth - 1,
                        y + 12,
                        EditorButtonTheme.mix(
                                SnbtCodeSyntax.HELP_BACKGROUND,
                                EditorButtonTheme.accent(),
                                hovered ? .85f : .40f));
            boolean hasColor = dataMode() && dataEntries.get(popupStart + i).color() != null;
            String label =
                    font.plainSubstrByWidth(
                            matches.get(popupStart + i), popupWidth - (hasColor ? 22 : 8));
            Component styled = dataMode() ? SnbtCodeSyntax.styled(label) : Component.literal(label);
            if (hasColor) {
                int color = dataEntries.get(popupStart + i).color();
                styled =
                        Component.literal("\u25a0 ")
                                .withStyle(
                                        style ->
                                                style.withColor(
                                                        SnbtCodeSyntax.readableColor(color)))
                                .append(
                                        Component.literal(label)
                                                .withStyle(
                                                        style ->
                                                                style.withColor(
                                                                        SnbtCodeSyntax.HELP_TEXT)));
            }
            g.drawString(font, styled, popupX + 4, y + 2, SnbtCodeSyntax.HELP_TEXT, false);
            if (valuePickerOpen() && hovered && !label.equals(matches.get(popupStart + i)))
                EditorTooltips.show(
                        font, Component.literal(matches.get(popupStart + i)), mouseX, mouseY);
        }
        if (popupDetailsHeight() > 0 && choice < dataEntries.size()) {
            var lines =
                    font.split(
                            Component.literal(dataEntries.get(choice).detail()), popupWidth - 10);
            helpScroll = Math.min(helpScroll, Math.max(0, lines.size() - 3));
            for (int i = 0; i < 3 && i + helpScroll < lines.size(); i++)
                g.drawString(
                        font,
                        lines.get(i + helpScroll),
                        popupX + 4,
                        popupY + 14 + popupRows * 12 + i * 10,
                        SnbtCodeSyntax.HELP_MUTED,
                        false);
        }
        if (matches.size() > popupRows) {
            int top = popupY + 12;
            int travel = Math.max(1, popupRows * 12 - 8);
            int handle = top + (int) ((double) choice / Math.max(1, matches.size() - 1) * travel);
            g.fill(
                    popupX + popupWidth - 3,
                    top,
                    popupX + popupWidth - 1,
                    top + popupRows * 12,
                    EditorButtonTheme.edge());
            g.fill(
                    popupX + popupWidth - 3,
                    handle,
                    popupX + popupWidth - 1,
                    handle + 8,
                    EditorButtonTheme.accent());
        }
        g.pose().popPose();
    }

    private boolean inHover(double x, double y) {
        return helpAvailable
                && x >= hoverX
                && x < hoverX + hoverWidth
                && y >= hoverY
                && y < hoverY + hoverHeight;
    }

    int referenceResizeEdges(double x, double y) {
        if (!generalHelp || !inHover(x, y)) return 0;
        return (x < hoverX + REFERENCE_RESIZE_BORDER
                        ? 1
                        : x >= hoverX + hoverWidth - REFERENCE_RESIZE_BORDER ? 2 : 0)
                | (y < hoverY + REFERENCE_RESIZE_BORDER
                        ? 4
                        : y >= hoverY + hoverHeight - REFERENCE_RESIZE_BORDER ? 8 : 0);
    }

    void renderReferenceResize(GuiGraphics g, int mx, int my) {
        if (!generalHelp || !helpAvailable) return;
        int edges = helpResizing ? resizeEdges : referenceResizeEdges(mx, my);
        int right = hoverX + hoverWidth, bottom = hoverY + hoverHeight;
        int color = EditorButtonTheme.accent();
        if ((edges & 1) != 0) g.fill(hoverX - 1, hoverY - 1, hoverX, bottom + 1, color);
        if ((edges & 2) != 0) g.fill(right, hoverY - 1, right + 1, bottom + 1, color);
        if ((edges & 4) != 0) g.fill(hoverX - 1, hoverY - 1, right + 1, hoverY, color);
        if ((edges & 8) != 0) g.fill(hoverX - 1, bottom, right + 1, bottom + 1, color);
    }

    boolean layoutReference(int width, int height) {
        helpScreenWidth = width;
        helpScreenHeight = height;
        helpAvailable = width >= 48 && height >= 48;
        if (!helpAvailable) return false;
        if (generalHelp) {
            if (!helpPositioned) {
                hoverWidth = Math.min(480, Math.max(80, width - 16));
                hoverHeight = Math.min(height - 32, 320);
                hoverX = Math.max(8, (width - hoverWidth) / 2);
                hoverY = Math.max(8, (height - hoverHeight) / 2);
                helpPositioned = true;
                preferHelp();
            }
            int previousWidth = hoverWidth;
            hoverWidth = Math.min(preferredHelp.width(), width - 16);
            hoverHeight = Math.min(preferredHelp.height(), height - 16);
            hoverX = Math.clamp(preferredHelp.left(), 8, Math.max(8, width - hoverWidth - 8));
            hoverY = Math.clamp(preferredHelp.top(), 8, Math.max(8, height - hoverHeight - 8));
            if (hoverWidth != previousWidth) hoverText = null;
        }
        return true;
    }

    private void hoverHelp(GuiGraphics g, int clipTop, int clipBottom, int mx, int my) {
        hoverClipTop = clipTop;
        hoverClipBottom = clipBottom;
        if (!layoutReference(g.guiWidth(), g.guiHeight())) return;
        if (generalHelp) {
            if (!Objects.equals(hoverText, getValue())) {
                hoverText = getValue();
                String example = dataSuggestions.exampleText(getValue());
                if (referenceText == null)
                    referenceText =
                            new LuaEditBox(
                                    font,
                                    hoverX + 4,
                                    hoverY + 20,
                                    hoverWidth - 10,
                                    dataSuggestions);
                if (!referenceText.getValue().equals(example)) {
                    var history = referenceText.textHistory();
                    boolean restoring = history.restoring;
                    history.restoring = true;
                    try {
                        referenceText.setValue(example);
                    } finally {
                        history.restoring = restoring;
                    }
                }
            }
        }
        long now = net.minecraft.Util.getMillis();
        if (!generalHelp) {
            int offset = sourceOffset(mx, my, clipTop, clipBottom);
            boolean sameToken =
                    Objects.equals(hoverText, getValue())
                            && hoveredField != null
                            && offset >= hoveredField.start()
                            && offset < hoveredField.end();
            if (!sameToken) {
                hoverText = getValue();
                hoverOffset = offset;
                hoveredField = offset < 0 ? null : dataSuggestions.inspect(hoverText, offset);
                hoverSince = now;
                hoverScroll = 0;
                hoverVisible = false;
                if (hoveredField != null) {
                    hoverWidth = Math.min(310, Math.max(80, g.guiWidth() - 16));
                    var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                    for (var paragraph :
                            DataFieldHelp.structure(dataSuggestions, hoveredField, hoverText))
                        wrapped.addAll(font.split(paragraph, hoverWidth - 14));
                    hoverLines = List.copyOf(wrapped);
                    hoverHeight =
                            Math.min(168, Math.min(hoverLines.size() * 10 + 8, g.guiHeight() - 16));
                    hoverX = Math.clamp(mx + 3, 8, Math.max(8, g.guiWidth() - hoverWidth - 8));
                    hoverY = Math.clamp(my + 12, 8, Math.max(8, g.guiHeight() - hoverHeight - 8));
                }
            }
        }
        if (!generalHelp
                && (hoveredField == null || net.minecraft.Util.getMillis() - hoverSince < 350))
            return;
        hoverVisible = true;
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);
        g.fill(
                hoverX - 1,
                hoverY - 1,
                hoverX + hoverWidth + 1,
                hoverY + hoverHeight + 1,
                EditorButtonTheme.edge());
        g.fill(
                hoverX,
                hoverY,
                hoverX + hoverWidth,
                hoverY + hoverHeight,
                SnbtCodeSyntax.HELP_BACKGROUND);
        if (generalHelp) {
            g.fill(
                    hoverX,
                    hoverY,
                    hoverX + hoverWidth,
                    hoverY + 16,
                    EditorButtonTheme.mix(
                            SnbtCodeSyntax.HELP_BACKGROUND, EditorButtonTheme.edge(), .3f));
            g.drawString(
                    font,
                    font.plainSubstrByWidth(
                            Component.translatable(
                                            dataSuggestions.jsonMode()
                                                    ? "npships.loot.reference"
                                                    : "npships.data_help.window")
                                    .getString(),
                            hoverWidth - 24),
                    hoverX + 4,
                    hoverY + 4,
                    SnbtCodeSyntax.HELP_TEXT,
                    false);
            g.drawString(
                    font,
                    "X",
                    hoverX + hoverWidth - 12,
                    hoverY + 4,
                    SnbtCodeSyntax.HELP_TEXT,
                    false);
        }
        if (generalHelp && referenceText != null) {
            referenceText.bounds(hoverX + 4, hoverY + 20, hoverWidth - 10, hoverHeight - 30);
            referenceText.render(g, mx, my, 0);
            // The selectable example owns its own field descriptions. Drawing text alone never
            // invokes those overlays; render them outside its native text scissor, above this
            // panel.
            referenceText.suggestions(g, hoverY + 20, hoverY + hoverHeight - 10, mx, my);
            renderReferenceResize(g, mx, my);
            g.pose().popPose();
            return;
        }
        int rows = (hoverHeight - (generalHelp ? 24 : 8)) / 10;
        for (int i = 0; i < rows && i + hoverScroll < hoverLines.size(); i++)
            g.drawString(
                    font,
                    hoverLines.get(i + hoverScroll),
                    hoverX + 5,
                    hoverY + (generalHelp ? 20 : 4) + i * 10,
                    SnbtCodeSyntax.HELP_TEXT,
                    false);
        if (hoverLines.size() > rows) {
            int thumb =
                    hoverY
                            + 3
                            + (int)
                                    ((double) hoverScroll
                                            / Math.max(1, hoverLines.size() - rows)
                                            * (hoverHeight - 14));
            g.fill(
                    hoverX + hoverWidth - 4,
                    thumb,
                    hoverX + hoverWidth - 2,
                    thumb + 8,
                    EditorButtonTheme.accent());
        }
        g.pose().popPose();
    }
}
