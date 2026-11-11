package com.eynny.npships.client.editor.screen;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.DataSuggestions;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.EditorMultilineEditBox;
import com.eynny.npships.client.editor.LuaEditBox;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.client.editor.widget.RegistryEditBox;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Collection navigation remains visible while editing a complete standard JSON definition. */
abstract class JsonCollectionScreen extends Screen {
    protected final Screen parent;
    protected JsonObject config;
    protected final Consumer<JsonObject> accept;
    protected String selected = "", text = "", error = "";
    protected int panelX, panelW, page;
    private MultiLineEditBox editor;
    private LuaEditBox richEditor;
    protected boolean formMode;
    private final List<Runnable> formLabels = new ArrayList<>();
    private GuiGraphics drawing;

    protected DataSuggestions suggestions(JsonObject value) {
        return null;
    }

    protected boolean supportsForm(JsonObject value) {
        return false;
    }

    protected void prepareForm(JsonObject value) {}

    protected void buildForm() {}

    protected JsonObject formJson() {
        return value(selected);
    }

    protected void field(String label, int y, String value, Consumer<String> change) {
        formLabels.add(
                () ->
                        drawing.drawString(
                                font, Component.translatable(label), panelX, y - 12, 0xaabbd0));
        var box =
                new RegistryEditBox(
                        panelX, y, panelW, Component.translatable(label), java.util.List::of);
        box.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable(
                                "npships.tools.field_hint", Component.translatable(label))));
        box.setMaxLength(1024);
        box.setValue(value);
        box.setResponder(change);
        addRenderableWidget(box);
    }

    protected void multiline(String label, int y, String value, Consumer<String> change) {
        formLabels.add(
                () ->
                        drawing.drawString(
                                font, Component.translatable(label), panelX, y - 12, 0xaabbd0));
        var box =
                new EditorMultilineEditBox(
                        font,
                        panelX,
                        y,
                        panelW,
                        Math.max(28, height - y - 72),
                        Component.empty(),
                        Component.translatable(label));
        box.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable(
                                "npships.tools.field_hint", Component.translatable(label))));
        box.setCharacterLimit(1000000);
        box.setValue(value);
        box.setValueListener(change);
        addRenderableWidget(box);
    }

    protected JsonCollectionScreen(
            Screen parent, String title, JsonObject config, Consumer<JsonObject> accept) {
        super(Component.translatable(title));
        this.parent = parent;
        this.config = config.deepCopy();
        this.accept = accept;
    }

    protected abstract List<String> names();

    protected abstract JsonObject value(String name);

    protected abstract void put(String name, JsonObject value);

    protected abstract void create();

    protected abstract void validate();

    protected void extras() {}

    protected void button(String key, int x, int y, int w, Runnable action) {
        addRenderableWidget(
                EditorButton.flat(Component.translatable(key), b -> run(action))
                        .bounds(x, y, w, 20)
                        .build());
    }

    protected void run(Runnable action) {
        try {
            error = "";
            action.run();
        } catch (Exception e) {
            error = EditorErrorText.message(e);
        }
    }

    protected void select(String name) {
        selected = name;
        prepareForm(value(name));
        formMode = supportsForm(value(name));
        text =
                new GsonBuilder()
                        .setPrettyPrinting()
                        .disableHtmlEscaping()
                        .create()
                        .toJson(value(name));
        init();
    }

    @Override
    protected void init() {
        if (richEditor != null) richEditor.releaseHelp();
        richEditor = null;
        editor = null;
        clearWidgets();
        formLabels.clear();
        panelX = width / 2 + 4;
        panelW = width - panelX - 12;
        button("gui.back", width - 88, 8, 76, this::onClose);
        button("npships.authoring.new", 12, 38, width / 2 - 20, this::create);
        int rows = Math.max(1, (height - 132) / 24),
                pages = Math.max(1, (names().size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < names().size(); i++) {
            var name = names().get(page * rows + i);
            button(
                    name,
                    12,
                    66 + i * 24,
                    width / 2 - 20,
                    () -> {
                        if (commit()) select(name);
                    });
        }
        if (page > 0)
            button(
                    "<",
                    12,
                    height - 44,
                    28,
                    () -> {
                        page--;
                        init();
                    });
        if (page + 1 < pages)
            button(
                    ">",
                    44,
                    height - 44,
                    28,
                    () -> {
                        page++;
                        init();
                    });
        if (!selected.isEmpty()) {
            button(
                    formMode ? "JSON" : "npships.authoring.simple",
                    panelX,
                    8,
                    60,
                    () -> {
                        if (!commit()) return;
                        if (!formMode && !supportsForm(value(selected)))
                            throw invalid(
                                    "simple_form",
                                    "This definition has advanced fields; use JSON to preserve them.");
                        formMode = !formMode;
                        prepareForm(value(selected));
                        init();
                    });
            if (formMode) buildForm();
            else {
                var help = suggestions(value(selected));
                if (help == null) {
                    editor =
                            new EditorMultilineEditBox(
                                    font,
                                    panelX,
                                    62,
                                    panelW,
                                    Math.max(30, height - 132),
                                    Component.empty(),
                                    Component.literal("JSON"));
                } else {
                    richEditor = new LuaEditBox(font, panelX, 62, panelW, help);
                    richEditor.setHeight(Math.max(30, height - 132));
                    richEditor.clipboard(minecraft.keyboardHandler::setClipboard);
                    editor = richEditor;
                    button(
                            "npships.loot.reference",
                            panelX,
                            height - 64,
                            (panelW - 4) / 2,
                            () -> richEditor.toggleStructureHelp());
                }
                editor.setTooltip(
                        net.minecraft.client.gui.components.Tooltip.create(
                                Component.translatable("npships.tools.json_hint")));
                editor.setCharacterLimit(1_000_000);
                editor.setValue(text);
                editor.setValueListener(v -> text = v);
                addRenderableWidget(editor);
            }
            button(
                    "npships.authoring.delete",
                    panelX + (panelW + 4) / 2,
                    height - 64,
                    (panelW - 4) / 2,
                    () -> {
                        var before = config.deepCopy();
                        put(selected, null);
                        try {
                            validate();
                        } catch (Exception e) {
                            config = before;
                            throw e;
                        }
                        selected = "";
                        text = "";
                        init();
                    });
        }
        extras();
    }

    protected boolean commit() {
        if (selected.isEmpty()) return true;
        var before = config.deepCopy();
        try {
            var updated = formMode ? formJson() : JsonStructure.parse(text).getAsJsonObject();
            put(selected, updated);
            validate();
            text = new GsonBuilder().setPrettyPrinting().create().toJson(updated);
            return true;
        } catch (Exception e) {
            config = before;
            error = EditorErrorText.message(e);
            return false;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float tick) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.fill(
                width / 2 - 3,
                34,
                width / 2 - 1,
                height - 28,
                EditorButtonTheme.surface(0xff405060));
        g.drawString(font, title, 12, 12, EditorButtonTheme.text());
        if (!formMode)
            g.drawString(font, font.plainSubstrByWidth(selected, panelW), panelX, 42, 0x73dfcc);
        drawing = g;
        formLabels.forEach(Runnable::run);
        drawing = null;
        g.drawString(
                font,
                font.plainSubstrByWidth(
                        error.isEmpty()
                                ? Component.translatable("npships.authoring.json_hint").getString()
                                : error,
                        width - 24),
                12,
                height - 18,
                error.isEmpty() ? 0xaabbd0 : 0xffaaaa);
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, tick);
        if (richEditor != null) {
            richEditor.popupSurface(width, 34, height - 28);
            richEditor.suggestions(g, 34, height - 28, x, y);
        }
        if (!error.isEmpty() && y >= height - 26)
            EditorTooltips.show(font, font.split(Component.literal(error), width - 24), x, y);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (commit()) {
            accept.accept(config);
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (richEditor != null) {
            if (richEditor.popupContains(x, y)) {
                richEditor.choose(x, y, button);
                return true;
            }
            if (richEditor.valuePickerOpen()) {
                richEditor.dismissCompletion();
                return true;
            }
            // An outside press releases reference text focus before native form routing.
            richEditor.choose(x, y, button);
        }
        if (com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                minecraft, error, x, y, button, 12, height - 26, width - 24, 26)) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (richEditor != null
                && (richEditor.scrollSuggestions(x, y, vertical) || richEditor.popupContains(x, y)))
            return true;
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (richEditor != null
                && (richEditor.selectReference(x, y, button, dx, dy)
                        || richEditor.dragHelp(dx, dy)
                        || richEditor.pointerCaptured())) return true;
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (richEditor != null) {
            boolean captured = richEditor.pointerCaptured();
            richEditor.releaseHelp();
            if (captured) {
                setDragging(false);
                return true;
            }
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (richEditor != null) {
            if (richEditor.referenceKeyPressed(key, scan, modifiers)) return true;
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && richEditor.dismissCompletion())
                return true;
            if (getFocused() == richEditor
                    && key == org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
                    && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) != 0) {
                richEditor.complete();
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (richEditor != null && richEditor.referenceTyping()) return true;
        return super.charTyped(character, modifiers);
    }

    @Override
    public void removed() {
        if (richEditor != null) richEditor.releaseHelp();
        super.removed();
    }
}
