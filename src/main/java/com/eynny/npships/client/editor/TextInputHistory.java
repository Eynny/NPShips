package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.config.NPShipsEditorConfig;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.eynny.npships.mixin.NPShipsConfigSectionAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Bounded widget-local text history. It never retains a Screen, world or accepted document. */
public final class TextInputHistory {
    public record Snapshot(String text, int cursor, int anchor, double scroll) {
        public Snapshot(String text, int cursor, int anchor) {
            this(text, cursor, anchor, Double.NaN);
        }
    }

    public record Frame(Snapshot before) {}

    private record Change(Snapshot before, Snapshot after) {}

    private final ArrayDeque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final int maximumEntries;
    private final long maximumCharacters;
    private int depth;

    public TextInputHistory() {
        this(128, 4L * 1024 * 1024);
    }

    public TextInputHistory(int maximumEntries, long maximumCharacters) {
        if (maximumEntries < 1 || maximumCharacters < 1)
            throw new IllegalArgumentException("Text history limits must be positive");
        this.maximumEntries = maximumEntries;
        this.maximumCharacters = maximumCharacters;
    }

    public boolean restoring;

    public Frame begin(Snapshot before) {
        return restoring ? null : new Frame(depth++ == 0 ? before : null);
    }

    public void end(Frame frame, Snapshot after) {
        if (frame == null) return;
        if (depth <= 0) throw new IllegalStateException("Unbalanced text mutation");
        depth--;
        var before = frame.before();
        if (before != null && !before.text().equals(after.text())) {
            undo.addLast(new Change(before, after));
            redo.clear();
            trim(undo);
        }
    }

    public Snapshot take(boolean forward, Snapshot current) {
        var from = forward ? redo : undo;
        var to = forward ? undo : redo;
        if (from.isEmpty()) return null;
        var change = from.peekLast();
        String expected = forward ? change.before().text() : change.after().text();
        if (!expected.equals(current.text())) {
            undo.clear();
            redo.clear();
            return null;
        }
        from.removeLast();
        to.addLast(change);
        trim(to);
        return forward ? change.after() : change.before();
    }

    private void trim(ArrayDeque<Change> values) {
        while (values.size() > 1
                && (values.size() > maximumEntries
                        || retainedCharacters(values) > maximumCharacters)) values.removeFirst();
    }

    private static long retainedCharacters(ArrayDeque<Change> values) {
        var strings = new IdentityHashMap<String, Boolean>();
        long characters = 0;
        for (var change : values) {
            if (strings.put(change.before().text(), true) == null)
                characters += change.before().text().length();
            if (strings.put(change.after().text(), true) == null)
                characters += change.after().text().length();
        }
        return characters;
    }

    /** Keep the saved viewport when it still shows the edit line after native reflow. */
    public static double visibleLineScroll(
            double desired, int line, int lineHeight, int height, int padding) {
        if (line < 0) return desired;
        double top = (double) line * lineHeight;
        if (top < desired) return top;
        double bottom = top + lineHeight;
        return bottom > desired + height - padding
                ? Math.max(0, bottom - height + padding)
                : desired;
    }

    public static boolean owns(Object widget) {
        if (widget instanceof EditorEditBox) return true;
        if (widget instanceof EditorMultilineEditBox) return false;
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof NPShipsConfigSectionAccessor nativeSection) {
            var spec = nativeSection.npships$context().modSpec();
            return spec == NPShipsRuntimeConfig.SPEC || spec == NPShipsEditorConfig.SPEC;
        }
        return screen != null
                && screen.getClass().getName().startsWith("com.eynny.npships.client.");
    }
}
