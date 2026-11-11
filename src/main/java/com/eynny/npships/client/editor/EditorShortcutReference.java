package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorScrollBar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Read-only Screen-local help. Opening it never removes the editor or accepts a draft. */
final class EditorShortcutReference {
    private boolean open;
    private final EditorScrollBar bar = new EditorScrollBar();
    private EditorLayout.Rect frame = new EditorLayout.Rect(0, 0, 0, 0);
    private EditorLayout.Rect body = frame, close = frame;
    private final List<Row> rows = new ArrayList<>();
    private String measured = "";
    private int total, scroll, preferredScroll;

    private record Row(FormattedCharSequence text, int y, boolean heading) {}

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
    }

    void close() {
        open = false;
        bar.release();
    }

    private List<Component> content() {
        var result = new ArrayList<Component>();
        result.add(Component.translatable("npships.editor.keys.reference_controls"));
        for (var group : List.of("modes", "tools", "scene", "context")) {
            result.add(Component.translatable("npships.editor.keys.reference_" + group));
            for (var action : EditorShortcutDispatcher.Action.values()) {
                String family =
                        switch (action) {
                            case SELECT, CREATE, DELETE_MODE -> "modes";
                            case BLOCKS,
                                            ENTITIES,
                                            SEGMENTS,
                                            ZONES,
                                            LOOT,
                                            PATCHES,
                                            GLUE,
                                            BLOCKS_AREAS ->
                                    "tools";
                            case WORKSPACE, SNBT_REFERENCE -> "context";
                            default -> "scene";
                        };
                if (family.equals(group))
                    result.add(
                            Component.translatable(
                                    "npships.editor.keys.reference_entry",
                                    action.mapping.getDisplayName(),
                                    EditorShortcutDispatcher.binding(action)));
            }
        }
        return result;
    }

    void layout(int width, int height) {
        int w = Math.max(0, Math.min(400, width - 12));
        int h = Math.max(0, Math.min(300, height - 12));
        int x = Math.max(0, (width - w) / 2), y = Math.max(0, (height - h) / 2);
        frame = new EditorLayout.Rect(x, y, x + w, y + h);
        if (w < 80 || h < 48) {
            body = close = new EditorLayout.Rect(x, y, x, y);
            bar.release();
            return;
        }
        close = new EditorLayout.Rect(frame.right() - 20, y + 2, frame.right() - 2, y + 18);
        body = new EditorLayout.Rect(x + 6, y + 22, frame.right() - 6, frame.bottom() - 6);
        var font = Minecraft.getInstance().font;
        var text = content();
        String signature =
                body.width()
                        + ":"
                        + font.lineHeight
                        + ":"
                        + font.width("WMxyz12")
                        + ":"
                        + text.stream().map(Component::getString).toList();
        if (!measured.equals(signature)) {
            measured = signature;
            rows.clear();
            total = 0;
            for (var part : text) {
                boolean heading =
                        part.getContents()
                                        instanceof
                                        net.minecraft.network.chat.contents.TranslatableContents c
                                && c.getKey().startsWith("npships.editor.keys.reference_")
                                && !c.getKey().endsWith("entry")
                                && !c.getKey().endsWith("controls")
                                && !c.getKey().endsWith("scope");
                if (heading) total += EditorLayout.CONTROL_GAP;
                for (var line : font.split(part, Math.max(1, body.width() - 10))) {
                    rows.add(new Row(line, total, heading));
                    total += font.lineHeight + 2;
                }
            }
        }
        scroll = Math.clamp(preferredScroll, 0, Math.max(0, total - body.height()));
        bar.configure(
                body.right() - 6,
                body.top(),
                body.height(),
                body.height(),
                total,
                scroll,
                value -> {
                    preferredScroll = scroll = value;
                });
    }

    void render(GuiGraphics g, int width, int height, int mx, int my) {
        if (!open) return;
        layout(width, height);
        if (body.width() == 0 || body.height() == 0) return;
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(0, 0, 950);
        g.fill(0, 0, width, height, 0x88000000);
        frame.fill(g, EditorButtonTheme.panel());
        g.renderOutline(
                frame.left(), frame.top(), frame.width(), frame.height(), EditorButtonTheme.edge());
        g.drawString(
                font,
                font.plainSubstrByWidth(
                        Component.translatable("npships.editor.keys.reference_title").getString(),
                        Math.max(1, frame.width() - 32)),
                frame.left() + 6,
                frame.top() + 5,
                EditorButtonTheme.text());
        close.fill(
                g, close.contains(mx, my) ? EditorButtonTheme.accent() : EditorButtonTheme.panel());
        g.drawCenteredString(
                font, "x", (int) close.centerX(), close.top() + 3, EditorButtonTheme.text());
        g.enableScissor(body.left(), body.top(), body.right() - 8, body.bottom());
        for (var row : rows) {
            int top = body.top() + row.y - scroll;
            if (top + font.lineHeight > body.top() && top < body.bottom())
                g.drawString(
                        font,
                        row.text,
                        body.left(),
                        top,
                        row.heading ? EditorButtonTheme.accent() : EditorButtonTheme.text());
        }
        g.disableScissor();
        bar.render(g);
        g.pose().popPose();
    }

    void click(double x, double y, int button) {
        if (button == 0 && close.contains(x, y) || !frame.contains(x, y)) close();
        else bar.click(x, y, button);
    }

    void drag(double y) {
        bar.drag(y);
    }

    void release() {
        bar.release();
    }

    void wheel(double delta) {
        preferredScroll =
                scroll =
                        Math.clamp(
                                scroll - (int) (delta * 24), 0, Math.max(0, total - body.height()));
    }

    void key(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) close();
        else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)
            wheel(key == GLFW.GLFW_KEY_UP ? 1 : -1);
        else if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)
            wheel((key == GLFW.GLFW_KEY_PAGE_UP ? 1 : -1) * Math.max(1, body.height() / 24));
        else if (key == GLFW.GLFW_KEY_HOME) preferredScroll = scroll = 0;
        else if (key == GLFW.GLFW_KEY_END)
            preferredScroll = scroll = Math.max(0, total - body.height());
    }
}
