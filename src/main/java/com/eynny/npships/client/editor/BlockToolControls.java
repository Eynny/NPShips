package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.AssemblyEligibility;
import com.eynny.npships.authoring.document.BlockSettings;
import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.client.editor.screen.BlockEditorScreen;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class BlockToolControls {
    private final EditorToolsPanel panel;
    private final ConnectionControls connections;

    BlockToolControls(EditorToolsPanel panel) {
        this.panel = panel;
        this.connections = new ConnectionControls(panel);
    }

    void blocks() {
        Runnable[] move = {null};
        AbstractWidget positionField = null;
        panel.placementModes();
        if (panel.pos() == null) {
            panel.sections.begin("brush", "section_brush", true);
            panel.field(
                    "state",
                    panel.state,
                    v -> {
                        panel.state = v;
                        panel.blockBrushData = null;
                        panel.blockBrushPatches = java.util.List.of();
                    });
            panel.registry(false);
            panel.quickRows(false);
            panel.sections.end();
            return;
        }
        if (panel.pos() != null) {
            String[]
                    target =
                            {
                                panel.pos().getX()
                                        + " "
                                        + panel.pos().getY()
                                        + " "
                                        + panel.pos().getZ()
                            },
                    material = {"minecraft:stone"};
            panel.sections.begin("position", "position", true);
            positionField = panel.field("position", target[0], v -> target[0] = v, false);
            panel.sections.end();
            String initialPosition = target[0];
            move[0] =
                    () -> {
                        if (target[0].equals(initialPosition)) return;
                        var index = panel.doc().blocks().get(panel.pos());
                        if (index == null)
                            throw new IllegalArgumentException(panel.tr("select_hint").getString());
                        var original =
                                NbtUtils.readBlockState(
                                        panel.mc.level.holderLookup(Registries.BLOCK),
                                        panel.doc().paletteCopy().getCompound(index));
                        var destination = BlockEditorScreen.position(target[0]);
                        panel.commit(
                                SchematicBlocks.put(
                                        panel.doc(), panel.pos(), destination, original, null));
                        panel.host.select(destination);
                    };
            var chosen = panel.selectedBlockState();
            if (AssemblyEligibility.excluded(chosen, panel.mc.level, panel.pos())) {
                var warning = Component.translatable("npships.tools.assembly_excluded");
                panel.button(
                                "npships.tools.assembly_excluded_short",
                                () -> panel.mc.keyboardHandler.setClipboard(warning.getString()))
                        .setTooltip(Tooltip.create(warning));
            }
            panel.sections.group("data", "section_data", true, this::textData);
            var index = panel.cached.blocks().get(panel.pos());
            if (index != null) {
                var actual =
                        net.minecraft.nbt.NbtUtils.readBlockState(
                                panel.mc.level.holderLookup(Registries.BLOCK),
                                panel.cached.paletteCopy().getCompound(index));
                BlockInspectorContributions.build(
                        BlockInspectorContribution.Slot.BEFORE_DATA, panel, actual, null, material);
            }
        }
        panel.blockData();
        // Position is saved after all other fields, as before.
        if (move[0] != null) panel.defer(positionField, move[0]);
    }

    void placementModes() {
        var hint =
                new StringWidget(
                                panel.x,
                                panel.cursor,
                                panel.contentWidth(),
                                12,
                                Component.literal(
                                        panel.mc.font.plainSubstrByWidth(
                                                panel.tr("modifier_hint").getString(),
                                                panel.contentWidth())),
                                panel.mc.font)
                        .alignLeft();
        hint.setTooltip(Tooltip.create(panel.tr("modifier_hint")));
        panel.widgets.add(hint);
        panel.cursor += 16;
    }

    void registry(boolean types) {
        panel.sections.registry();
        panel.field(
                "search",
                panel.query,
                v -> {
                    panel.query = v;
                    panel.page = 0;
                    panel.registryRemainder = 0;
                    panel.updateSearch(types);
                });
        panel.searchTypes = types;
        panel.searchStart = panel.widgets.size();
        panel.searchY = panel.cursor;
        panel.appendRegistry(types);
    }

    void updateSearch(boolean types) {
        // Keep the focused search field alive while updating only registry cells.
        if (panel.searchStart < 0
                || panel.searchEnd < panel.searchStart
                || panel.searchEnd > panel.widgets.size()) return;
        panel.sections.forget(
                List.copyOf(panel.widgets.subList(panel.searchStart, panel.searchEnd)));
        for (int i = panel.searchEnd - 1; i >= panel.searchStart; i--) panel.widgets.remove(i);
        int oldCursor = panel.cursor;
        panel.cursor = panel.searchY;
        panel.appendRegistry(types);
        panel.cursor = oldCursor;
        for (int i = panel.searchStart; i < panel.searchEnd; i++)
            panel.widgets.get(i).setY(panel.widgets.get(i).getY() - panel.scroll);
    }

    void appendRegistry(boolean types) {
        String q = panel.query.toLowerCase(Locale.ROOT);
        var ids =
                types
                        ? BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                                .filter(
                                        id ->
                                                panel.isGlueId(id.toString())
                                                        == (panel.tool
                                                                == EditorToolsPanel.Tool.GLUE))
                                .filter(
                                        id ->
                                                id.toString().contains(q)
                                                        || BuiltInRegistries.ENTITY_TYPE
                                                                .get(id)
                                                                .getDescription()
                                                                .getString()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(q))
                                .map(Object::toString)
                                .sorted()
                                .toList()
                        : EditorBlockCatalog.entries(panel.mc.level).stream()
                                .filter(
                                        e ->
                                                e.id().contains(q)
                                                        || e.state().contains(q)
                                                        || e.icon()
                                                                .getHoverName()
                                                                .getString()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(q))
                                .sorted(Comparator.comparing(EditorBlockCatalog.Entry::id))
                                .map(EditorBlockCatalog.Entry::state)
                                .toList();
        int columns = panel.quickColumns(), count = columns * 5;
        panel.registryMax = Math.max(0, (ids.size() + columns - 1) / columns - 5);
        panel.page = Math.min(panel.page, panel.registryMax);
        var cells = new ArrayList<AbstractWidget>();
        for (int i = 0;
                i < count && panel.page * columns + panel.registryRemainder + i < ids.size();
                i++) {
            var id = ids.get(panel.page * columns + panel.registryRemainder + i);
            cells.add(
                    panel.catalogCell(
                            types,
                            id.toString(),
                            panel.x + (i % columns) * 18,
                            panel.cursor + (i / columns) * 18,
                            false));
        }
        panel.widgets.addAll(panel.searchStart, cells);
        panel.sections.registryCells(cells);
        panel.searchEnd = panel.searchStart + cells.size();
        panel.cursor += 94;
    }

    void quickRows(boolean types) {
        var lists =
                List.of(
                        types ? panel.recentEntities : panel.recent,
                        types ? panel.pinnedEntities : panel.pinnedBlocks);
        int columns = panel.quickColumns();
        for (int row = 0; row < 2; row++) {
            panel.widgets.add(
                    new StringWidget(
                                    panel.x,
                                    panel.cursor,
                                    panel.contentWidth(),
                                    12,
                                    panel.tr(row == 0 ? "recent" : "pinned"),
                                    panel.mc.font)
                            .alignLeft());
            panel.cursor += 14;
            int firstCell = panel.widgets.size();
            var list = lists.get(row);
            panel.trimQuickRow(list);
            for (int i = 0; i < columns; i++) {
                if (i < list.size())
                    panel.widgets.add(
                            panel.catalogCell(
                                    types, list.get(i), panel.x + i * 18, panel.cursor, row == 1));
                else {
                    var empty =
                            new Button(
                                    panel.x + i * 18,
                                    panel.cursor,
                                    18,
                                    18,
                                    Component.empty(),
                                    b -> {},
                                    supplier -> supplier.get()) {
                                @Override
                                protected void renderWidget(
                                        GuiGraphics g, int mx, int my, float t) {
                                    EditorButton.cell(g, getX(), getY(), isHovered(), false);
                                }
                            };
                    empty.active = false;
                    panel.widgets.add(empty);
                }
            }
            panel.flows.add(
                    new EditorToolsPanel.Flow(
                            List.copyOf(panel.widgets.subList(firstCell, panel.widgets.size())),
                            18,
                            false));
            panel.cursor += 22;
        }
    }

    private void textData() {
        var selectedDataPosition = panel.pos();
        var initial =
                com.eynny.npships.authoring.document.BlockDataEditor.read(
                        panel.doc(), selectedDataPosition, panel.mc.level);
        panel.dataEditor(
                new net.minecraft.nbt.SnbtPrinterTagVisitor().visit(initial),
                previous ->
                        com.eynny.npships.authoring.document.BlockDataEditor.suggestions(
                                panel.doc(), selectedDataPosition, panel.mc.level, previous),
                value -> {},
                value -> {
                    try {
                        panel.liveCommit(
                                com.eynny.npships.authoring.document.BlockDataEditor.apply(
                                        panel.doc(),
                                        selectedDataPosition,
                                        com.eynny.npships.authoring.document.SnbtText.parse(value),
                                        panel.mc.level));
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        throw com.eynny.npships.authoring.validation.ValidationProblem.describe(
                                new IllegalArgumentException(e.getMessage(), e), "snbt_syntax");
                    }
                });
    }

    void blockData() {
        if (panel.pos() == null) return;
        if (panel.tool != EditorToolsPanel.Tool.PATCHES
                && !PatchDevices.kind(panel.doc(), panel.pos()).isEmpty())
            panel.action(
                    "device_settings",
                    () -> {
                        var selected = panel.pos();
                        panel.change(EditorToolsPanel.Tool.PATCHES);
                        panel.host.select(selected);
                        panel.build();
                    });
        var index = panel.cached.blocks().get(panel.pos());
        if (index == null) return;
        var block =
                BuiltInRegistries.BLOCK.get(
                        ResourceLocation.parse(
                                panel.cached.paletteCopy().getCompound(index).getString("Name")));
        if (!(block instanceof net.minecraft.world.level.block.EntityBlock)) return;
        var saved = panel.cached.blockEntityData(panel.pos());
        var selected = panel.pos();
        var baseline =
                BlockSettings.defaults(panel.cached, selected, panel.mc.level.registryAccess());
        if (block instanceof net.minecraft.world.level.block.AbstractBannerBlock)
            panel.sections.group(
                    "appearance.banner",
                    "section_banner",
                    true,
                    () -> panel.banners.build(block, saved));
        BlockInspectorContributions.build(
                BlockInspectorContribution.Slot.AFTER_DATA, panel, null, saved, null);
        connections.build();
        panel.inventory();
        if (panel.tool == EditorToolsPanel.Tool.LOOT)
            panel.sections.group("data", "section_data", true, this::textData);
        if (block instanceof net.minecraft.world.level.block.SignBlock) {
            var signState = panel.cached.paletteCopy().getCompound(index).copy();
            var sign = new CompoundTag();
            for (String key : List.of("front_text", "back_text", "is_waxed"))
                if (baseline.contains(key)) sign.put(key, baseline.get(key).copy());
            panel.parameters.build(
                    "sign:" + selected + ":" + panel.doc().blockName(selected),
                    sign,
                    baseline,
                    () ->
                            BlockSettings.defaults(
                                    panel.doc(), selected, panel.mc.level.registryAccess()),
                    () ->
                            selected.equals(panel.pos())
                                    && panel.doc().blocks().containsKey(selected)
                                    && signState.equals(
                                            panel.doc()
                                                    .paletteCopy()
                                                    .getCompound(
                                                            panel.doc().blocks().get(selected))),
                    (before, changed) ->
                            panel.commit(
                                    BlockSettings.apply(
                                            panel.doc(),
                                            selected,
                                            before,
                                            changed,
                                            panel.mc.level.registryAccess())));
        }
    }
}
