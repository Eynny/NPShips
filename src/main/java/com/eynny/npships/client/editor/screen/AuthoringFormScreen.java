package com.eynny.npships.client.editor.screen;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.client.editor.widget.ProfileDropdown;
import com.eynny.npships.client.editor.widget.RegistryEditBox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small validated forms shared by region, import and weight editors. */
public final class AuthoringFormScreen extends Screen {
    private final Screen parent;
    private final String titleKey;
    private final List<String> labels;
    private final List<String> values;
    private final Consumer<List<String>> submit;
    private Component error = Component.empty();

    public AuthoringFormScreen(
            Screen parent,
            String title,
            List<String> labels,
            List<String> values,
            Consumer<List<String>> submit) {
        super(Component.translatable(title));
        this.parent = parent;
        titleKey = title;
        this.labels = labels;
        this.values = new ArrayList<>(values);
        this.submit = submit;
    }

    private boolean coordinate(int i) {
        return labels.get(i).startsWith("npships.creation.size_");
    }

    private int axis(int i) {
        return coordinate(i) ? i - labels.indexOf("npships.creation.size_x") : 0;
    }

    private int row(int i) {
        int first = labels.indexOf("npships.creation.size_x");
        return first < 0 || i < first ? i : coordinate(i) ? first : i - 2;
    }

    @Override
    protected void init() {
        int x = Math.max(12, (width - 400) / 2), w = width - x * 2;
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            if (labels.get(i).equals("npships.creation.profile")) {
                addRenderableWidget(
                        new ProfileDropdown(
                                x, 47 + row(i) * 38, w, values.get(i), v -> values.set(index, v)));
                continue;
            }
            String key = labels.get(i).substring(labels.get(i).lastIndexOf('.') + 1);
            var field =
                    new RegistryEditBox(
                            x + axis(i) * ((w + 4) / 3),
                            47 + row(i) * 38,
                            coordinate(i) ? (w - 8) / 3 : w,
                            Component.translatable(labels.get(i)),
                            () -> RegistryEditBox.names(key));

            field.setMaxLength(256);
            field.setValue(values.get(i));
            field.setResponder(
                    v -> {
                        values.set(index, v);
                        error = Component.empty();
                        try {
                            if (labels.get(index).startsWith("npships.creation.size_")) {
                                if (Integer.parseInt(v) <= 0)
                                    throw invalid(
                                            "creation_size", "Size must be a positive integer", v);
                            } else if (index == 0
                                    && (titleKey.equals("npships.authoring.new")
                                            || titleKey.contains("rename")
                                            || titleKey.contains("duplicate")))
                                com.eynny.npships.authoring.io.AuthoringCreation.filename(v);
                        } catch (IllegalArgumentException ex) {
                            error = Component.literal(EditorErrorText.message(ex));
                        }
                        field.setTextColor(error.getString().isEmpty() ? 0xffffff : 0xff6666);
                    });
            addRenderableWidget(field);
            if (i == 0
                    && (key.equals("name")
                            || titleKey.contains("rename")
                            || titleKey.contains("duplicate"))) {
                setInitialFocus(field);
                field.selectName();
            }
        }
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("gui.done"),
                                b -> {
                                    try {
                                        submit.accept(List.copyOf(values));
                                    } catch (Exception e) {
                                        error = Component.literal(EditorErrorText.message(e));
                                    }
                                })
                        .bounds(x, height - 36, w / 2 - 4, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(x + w / 2 + 4, height - 36, w / 2 - 4, 20)
                        .build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float tick) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        int left = Math.max(12, (width - 400) / 2);
        for (int i = 0; i < labels.size(); i++)
            g.drawString(
                    font,
                    Component.translatable(labels.get(i)),
                    left + axis(i) * ((width - left * 2 + 4) / 3),
                    35 + row(i) * 38,
                    0x9caabd);
        g.drawString(
                font,
                font.plainSubstrByWidth(error.getString(), width - left * 2),
                left,
                height - 51,
                0xffaaaa);
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, tick);
        if (getFocused() instanceof RegistryEditBox box) box.suggestions(g);
        if (y >= height - 55 && y < height - 36 && !error.getString().isEmpty())
            EditorTooltips.show(font, font.split(error, width - 24), x, y);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (com.eynny.npships.client.editor.widget.EditorMessageCopy.click(
                minecraft, error.getString(), x, y, button, 12, height - 55, width - 24, 19))
            return true;
        if (getFocused() instanceof RegistryEditBox box && box.choose(x, y, button)) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
