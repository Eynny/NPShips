package com.eynny.npships.client.editor.screen;

import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.EditorErrorText;
import com.eynny.npships.client.editor.EditorMultilineEditBox;
import com.eynny.npships.client.editor.TextInputHistory;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** A detached config transaction: text undo here, document undo in the schematic. */
public final class ConfigEditorScreen extends Screen {
    private final Screen parent;
    private final SchematicDocument source;
    private final java.util.function.Consumer<SchematicDocument> accept;
    private final Runnable save;
    private String text;
    private final String initial;
    private MultiLineEditBox editor;
    private final TextInputHistory textHistory = new TextInputHistory(64, 8_000_000);
    private String error = "";

    public ConfigEditorScreen(
            Screen parent,
            SchematicDocument source,
            java.util.function.Consumer<SchematicDocument> accept,
            Runnable save) {
        super(Component.translatable("npships.editor.config"));
        this.parent = parent;
        this.source = source;
        this.accept = accept;
        this.save = save;
        initial = text = new GsonBuilder().setPrettyPrinting().create().toJson(source.configCopy());
    }

    @Override
    protected void init() {
        editor =
                addRenderableWidget(
                        new EditorMultilineEditBox(
                                font,
                                12,
                                38,
                                width - 24,
                                height - 88,
                                Component.empty(),
                                title,
                                textHistory) {
                            @Override
                            protected double scrollRate() {
                                return font.lineHeight * 3.0;
                            }
                        });
        editor.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("npships.tools.json_hint")));
        editor.setCharacterLimit(16 * 1024 * 1024);
        editor.setValue(text);
        editor.setValueListener(value -> text = value);
        setInitialFocus(editor);
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.loot.save"),
                                b -> {
                                    if (apply()) save.run();
                                })
                        .bounds(width - 180, 8, 80, 20)
                        .build());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(width - 92, 8, 80, 20)
                        .build());
    }

    @Override
    public void setTooltipForNextRenderPass(
            List<net.minecraft.util.FormattedCharSequence> lines,
            net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
            boolean override) {
        // A near-full-screen text field has no useful outer edge for mouse-hover help.
        // Keep native hover timing, focus arbitration and keyboard placement; follow the pointer.
        super.setTooltipForNextRenderPass(
                lines,
                positioner
                                instanceof
                                net.minecraft.client.gui.screens.inventory.tooltip
                                        .MenuTooltipPositioner
                        ? net.minecraft.client.gui.screens.inventory.tooltip
                                .DefaultTooltipPositioner.INSTANCE
                        : positioner,
                override);
    }

    private boolean apply() {
        try {
            if (!text.equals(initial)) {
                accept.accept(validate(source, JsonParser.parseString(text).getAsJsonObject()));
            }
            return true;
        } catch (Exception e) {
            error = EditorErrorText.message(e);
            return false;
        }
    }

    public static SchematicDocument validate(SchematicDocument source, JsonObject c) {
        com.eynny.npships.config.NPShipsStructureConfigLoader.load(c);
        var next = source.withConfig(c);
        StructureCrew.validateBounds(next, c);
        StructureSegments.validate(next.nbtCopy(), StructureSegments.read(c));
        var nbt = next.nbtCopy();
        SchematicPatches.embed(nbt, c);
        SchematicPatches.validate(nbt);
        return next;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float t) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float t) {
        g.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        g.drawString(
                font,
                (text.equals(initial) ? "" : "* ") + title.getString(),
                12,
                14,
                EditorButtonTheme.text());
        EditorButtonTheme.widgets(this);
        super.render(g, x, y, t);
        g.drawWordWrap(
                font,
                Component.literal(error == null ? "" : error),
                12,
                height - 44,
                width - 24,
                0xff5555);
    }
}
