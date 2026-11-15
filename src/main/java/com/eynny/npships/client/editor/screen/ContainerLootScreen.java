package com.eynny.npships.client.editor.screen;

import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EditorTooltips;
import com.eynny.npships.network.LootTableNetworking;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Select a server-provided existing table, or remove the override and preserve original NBT. */
public final class ContainerLootScreen extends Screen {
    private final Screen parent;
    private final Consumer<ResourceLocation> assign;
    private final Component original;
    private final int requestId;
    private List<String> tables = List.of();
    private com.google.gson.JsonObject config;
    private final Consumer<com.google.gson.JsonObject> updateConfig;
    private boolean received, truncated;
    private int page;
    private String value;
    private EditBox search;

    public ContainerLootScreen(
            Screen parent,
            BlockPos pos,
            ResourceLocation current,
            Component original,
            com.google.gson.JsonObject config,
            Consumer<com.google.gson.JsonObject> updateConfig,
            Consumer<ResourceLocation> assign) {
        super(Component.translatable("npships.loot.title", pos.getX(), pos.getY(), pos.getZ()));
        this.config = config.deepCopy();
        this.updateConfig = updateConfig;
        this.parent = parent;
        this.assign = assign;
        this.original = original;
        value = current == null ? "" : current.toString();
        requestId = 0;
    }

    @Override
    protected void init() {
        int x = 12, w = width - 24;
        search =
                new EditorEditBox(
                        font, x, 54, w, 20, Component.translatable("npships.loot.search"));
        search.setTooltip(net.minecraft.client.gui.components.Tooltip.create(search.getMessage()));
        search.setMaxLength(256);
        search.setHint(Component.translatable("npships.loot.search"));
        search.setValue(value);
        search.setResponder(
                text -> {
                    value = text;
                    page = 0;
                    rebuildRows();
                });
        addRenderableWidget(search);
        rebuildRows();
        setInitialFocus(search);
    }

    @Override
    public void tick() {
        var reply = LootTableNetworking.clientReply();
        if (reply != null && tables != reply.tables()) {
            received = true;
            tables = reply.tables();
            truncated = reply.truncated();
            rebuildRows();
        }
    }

    private void rebuildRows() {
        for (var child : List.copyOf(children()))
            if (child instanceof Button button) removeWidget(button);
        int w = width - 24, rows = Math.max(1, (height - 184) / 22);
        var available = new java.util.TreeSet<String>(tables);
        available.addAll(com.eynny.npships.authoring.EmbeddedLoot.read(config).keySet());
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.loot.local"),
                                b ->
                                        minecraft.setScreen(
                                                new LocalLootEditorScreen(
                                                        this,
                                                        config,
                                                        updated -> {
                                                            config = updated;
                                                            updateConfig.accept(updated);
                                                        })))
                        .bounds(12, height - 96, w, 20)
                        .build());
        var filtered =
                available.stream()
                        .filter(id -> id.contains(value.trim().toLowerCase(Locale.ROOT)))
                        .toList();
        int pages = Math.max(1, (filtered.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows && page * rows + i < filtered.size(); i++) {
            String id = filtered.get(page * rows + i);
            var button =
                    addRenderableWidget(
                            EditorButton.flat(
                                            Component.literal(id),
                                            b -> {
                                                assign.accept(ResourceLocation.parse(id));
                                                onClose();
                                            })
                                    .bounds(12, 82 + i * 22, w, 20)
                                    .build());
            button.setTooltip(Tooltip.create(Component.literal(id)));
        }
        var prev =
                addRenderableWidget(
                        EditorButton.flat(
                                        Component.literal("<"),
                                        b -> {
                                            page--;
                                            rebuildRows();
                                        })
                                .bounds(12, height - 70, 26, 20)
                                .build());
        prev.active = page > 0;
        var next =
                addRenderableWidget(
                        EditorButton.flat(
                                        Component.literal(">"),
                                        b -> {
                                            page++;
                                            rebuildRows();
                                        })
                                .bounds(42, height - 70, 26, 20)
                                .build());
        next.active = page + 1 < pages;
        int bw = (w - 4) / 2;
        addRenderableWidget(
                EditorButton.flat(
                                Component.translatable("npships.loot.original"),
                                b -> {
                                    assign.accept(null);
                                    onClose();
                                })
                        .bounds(12, height - 44, bw, 20)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("npships.loot.original_hint")))
                        .build());
        addRenderableWidget(
                EditorButton.flat(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(16 + bw, height - 44, bw, 20)
                        .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int x, int y, float tick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        graphics.fill(0, 0, width, height, EditorButtonTheme.surface(0xff151c26));
        graphics.drawCenteredString(font, title, width / 2, 12, EditorButtonTheme.text());
        graphics.drawString(
                font, font.plainSubstrByWidth(original.getString(), width - 24), 12, 32, 0x9caabd);
        Component status =
                Component.translatable(
                        !received
                                ? "npships.editor.loading"
                                : truncated ? "npships.loot.truncated" : "npships.loot.available",
                        tables.size());
        graphics.drawString(
                font,
                font.plainSubstrByWidth(status.getString(), width - 92),
                78,
                height - 64,
                0xffcf80);
        graphics.drawString(
                font, Component.translatable("npships.loot.draft_hint"), 12, height - 15, 0x9caabd);
        EditorButtonTheme.widgets(this);
        super.render(graphics, mouseX, mouseY, tick);
        if (mouseY >= 28 && mouseY < 48)
            EditorTooltips.show(font, font.split(original, width - 24), mouseX, mouseY);
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
