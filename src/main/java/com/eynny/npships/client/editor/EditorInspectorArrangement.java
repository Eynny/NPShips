package com.eynny.npships.client.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Same-context arrangement snapshots only; editing callbacks remain owned by the panel. */
final class EditorInspectorArrangement {
    private final java.util.Map<AbstractWidget, EditorWidgetArrangement.Binding> placements =
            new java.util.IdentityHashMap<>();
    private EditorLayout arrangementBase;
    private boolean baseExpandedCode;
    private int baseContentHeight,
            baseCursor,
            baseSearchY,
            baseItemY,
            baseLootY,
            baseInventoryY,
            baseDockY,
            baseDockHeight;
    private int baseParameterHeight, baseParameterEnd;
    private int inventoryActionEnd;
    private java.util.List<EditorToolsPanel.Flow> flows = java.util.List.of();
    private java.util.List<NoteBinding> notes = java.util.List.of();
    private java.util.Map<MultiLineTextWidget, Integer> wrapping = java.util.Map.of();

    /** Noninteractive labels can rewrap without reconstructing adjacent editing controls. */
    static final class Note {
        private final Component text;
        private final Font font;
        private final java.util.List<StringWidget> labels = new java.util.ArrayList<>();
        private java.util.List<String> lines = java.util.List.of();
        private int measuredWidth = -1, lastY;

        Note(Component text, Font font, int y, int width) {
            this.text = text;
            this.font = font;
            lastY = y;
            measure(width);
        }

        void measure(int width) {
            width = Math.max(1, width);
            if (width == measuredWidth) return;
            measuredWidth = width;
            lines =
                    font.getSplitter().splitLines(text, width, Style.EMPTY).stream()
                            .map(line -> line.getString())
                            .toList();
        }

        int height() {
            return lines.size() * 12;
        }

        int top(int scroll) {
            return labels.isEmpty() ? lastY : labels.getFirst().getY() + scroll;
        }

        boolean owns(AbstractWidget widget) {
            return labels.contains(widget);
        }

        void place(EditorToolsPanel panel, int x, int y, int width) {
            panel.widgets.removeAll(labels);
            lastY = panel.building ? y : y + panel.scroll;
            while (labels.size() < lines.size())
                labels.add(
                        new StringWidget(x, y, Math.max(1, width), 12, Component.empty(), font)
                                .alignLeft());
            for (int i = 0; i < lines.size(); i++) {
                var label = labels.get(i);
                label.setPosition(x, y + i * 12);
                label.setWidth(Math.max(1, width));
                label.setMessage(Component.literal(lines.get(i)));
                panel.widgets.add(label);
            }
            panel.sections.noteCells(labels);
        }
    }

    private record NoteBinding(Note note, int top, int height) {}

    private String registryQuery, itemQuery;
    private AbstractWidget preferredAnchor;
    private int preferredOffset, preferredScroll;
    private int registryFirst,
            itemFirst,
            lastRegistry,
            lastItem,
            inventoryX,
            inventoryY,
            lastInventoryX,
            lastInventoryY;

    void preferScroll(EditorToolsPanel panel) {
        preferredScroll = panel.scroll;
        preferredAnchor =
                panel.widgets.stream()
                        .filter(
                                w ->
                                        (placements.containsKey(w) || panel.parameters.tree.owns(w))
                                                && panel.sections.inspectorVisible(w)
                                                && !panel.itemCells.contains(w)
                                                && !panel.compactInventoryCells.contains(w))
                        .filter(w -> w != panel.codeEditor || !panel.expandedCode())
                        .filter(
                                w ->
                                        w.getY() + w.getHeight()
                                                        > panel.layout.inspectorContent().top()
                                                && w.getY() < panel.layout.inspector().bottom())
                        .findFirst()
                        .orElse(null);
        preferredOffset =
                preferredAnchor == null
                        ? 0
                        : anchorY(panel, preferredAnchor) - panel.layout.inspectorContent().top();
    }

    private int anchorY(EditorToolsPanel panel, AbstractWidget widget) {
        return widget == panel.codeEditor && !panel.expandedCode()
                ? panel.codeDockY - panel.scroll
                : widget.getY();
    }

    void capture(EditorToolsPanel panel) {
        flows = java.util.List.copyOf(panel.flows);
        placements.clear();
        arrangementBase = panel.layout;
        baseExpandedCode = panel.expandedCode();
        baseContentHeight = panel.contentHeight;
        baseCursor = panel.cursor;
        baseSearchY = panel.searchY;
        baseItemY = panel.itemRowsY;
        baseLootY = panel.lootRowsY;
        baseInventoryY = panel.inventoryRowsY;
        inventoryActionEnd =
                panel.inventoryWorkspaceAction == null
                        ? Integer.MAX_VALUE
                        : panel.inventoryWorkspaceAction.getY()
                                + panel.scroll
                                + EditorLayout.ROW_PITCH;
        baseDockY = panel.codeDockY;
        baseDockHeight = panel.codeDockHeight;
        baseParameterHeight = panel.parameters.tree.height(panel.w);
        var parameterRoot = panel.parameters.tree.root();
        baseParameterEnd =
                parameterRoot == null
                        ? Integer.MAX_VALUE
                        : parameterRoot.getY() + panel.scroll + baseParameterHeight;
        for (var widget : panel.widgets)
            if (!panel.parameters.tree.body(widget))
                placements.put(
                        widget,
                        EditorWidgetArrangement.Binding.capture(
                                widget,
                                panel.layout.inspector(),
                                panel.scroll,
                                panel.horizontalRoles.getOrDefault(
                                        widget, EditorWidgetArrangement.Horizontal.AUTOMATIC)));
        notes =
                panel.notes.stream()
                        .map(note -> new NoteBinding(note, note.top(panel.scroll), note.height()))
                        .toList();
        wrapping = new java.util.IdentityHashMap<>(panel.wrappedText);
        preferScroll(panel);
        registryQuery = panel.query;
        itemQuery = panel.itemQuery;
        registryFirst = lastRegistry = panel.page * panel.quickColumns() + panel.registryRemainder;
        itemFirst = lastItem = panel.itemPage * panel.quickColumns() + panel.itemRemainder;
        inventoryX = lastInventoryX = panel.inventoryOffsetX;
        inventoryY = lastInventoryY = panel.inventoryOffsetY;
    }

    private int flowDeltaBefore(int at, int width) {
        int total = 0;
        for (var flow : flows) {
            var first = placements.get(flow.cells().getFirst());
            if (first == null || first.bounds().top() >= at) continue;
            total += flow.height(width) - flow.height(arrangementBase.inspector().width());
        }
        for (var note : notes) if (note.top() < at) total += note.note().height() - note.height();
        for (var entry : wrapping.entrySet()) {
            var placement = placements.get(entry.getKey());
            if (placement != null && placement.bounds().top() < at)
                total += entry.getKey().getHeight() - placement.bounds().height();
        }
        return total;
    }

    void layout(EditorToolsPanel panel, EditorLayout next) {
        if (arrangementBase == null) capture(panel);
        boolean widthChanged = panel.w != next.inspector().width();
        var visibleAnchor =
                panel.parameters.tree.scrollAnchor(panel.sections.scrollAnchor(preferredAnchor));
        for (var note : notes) note.note().measure(next.contentWidth());
        for (var entry : wrapping.entrySet())
            entry.getKey().setMaxWidth(Math.max(1, next.inspector().width() - entry.getValue()));
        int currentRegistry = panel.page * panel.quickColumns() + panel.registryRemainder;
        int currentItem = panel.itemPage * panel.quickColumns() + panel.itemRemainder;
        if (currentRegistry != lastRegistry
                || !java.util.Objects.equals(registryQuery, panel.query))
            registryFirst = currentRegistry;
        registryQuery = panel.query;
        if (currentItem != lastItem || !java.util.Objects.equals(itemQuery, panel.itemQuery))
            itemFirst = currentItem;
        itemQuery = panel.itemQuery;
        if (panel.inventoryOffsetX != lastInventoryX) inventoryX = panel.inventoryOffsetX;
        if (panel.inventoryOffsetY != lastInventoryY) inventoryY = panel.inventoryOffsetY;
        int dy = next.inspector().top() - arrangementBase.inspector().top();
        int dockHeight =
                panel.codeEditor == null || panel.expandedCode()
                        ? panel.codeDockHeight
                        : panel.codeEditor.compactHeight(
                                next.contentWidth(), next.codeDockHeight());
        int dockDelta =
                panel.codeEditor == null
                        ? 0
                        : (panel.expandedCode() ? 0 : dockHeight + 4)
                                - (baseExpandedCode ? 0 : baseDockHeight + 4);
        int dockBottom = baseDockY + (baseExpandedCode ? 0 : baseDockHeight);
        int parameterDelta =
                panel.parameters.tree.height(next.inspector().width()) - baseParameterHeight;
        int inventoryActionDelta =
                panel.inventoryWorkspaceAction != null
                                && !panel.inventoryExpanded
                                && !panel.inventoryNeedsExpansion(next)
                        ? -EditorLayout.ROW_PITCH
                        : 0;
        java.util.function.IntUnaryOperator natural =
                at ->
                        at
                                + flowDeltaBefore(at, next.inspector().width())
                                + (at >= inventoryActionEnd ? inventoryActionDelta : 0)
                                + (at >= dockBottom ? dockDelta : 0);
        java.util.function.IntUnaryOperator deltaAt =
                at ->
                        flowDeltaBefore(at, next.inspector().width())
                                + (at >= inventoryActionEnd ? inventoryActionDelta : 0)
                                + (at >= dockBottom ? dockDelta : 0)
                                + (at >= baseParameterEnd ? parameterDelta : 0)
                                + panel.sections.deltaBefore(at, natural, next.inspector().width());
        for (var widget : panel.widgets) {
            if (widget == panel.codeEditor
                    || panel.parameters.tree.body(widget)
                    || panel.itemCells.contains(widget)
                    || panel.lootRows.contains(widget)
                    || panel.compactInventoryCells.contains(widget)
                    || notes.stream().anyMatch(note -> note.note().owns(widget))) continue;
            var placement = placements.get(widget);
            if (placement == null) continue;
            int at = placement.bounds().top();
            int delta = deltaAt.applyAsInt(at);
            placement.apply(next.inspector(), delta - panel.scroll, true);
        }
        panel.layout = next;
        panel.x = next.inspector().left();
        panel.y = next.inspector().top();
        panel.w = next.inspector().width();
        panel.h = next.inspector().height();
        for (var flow : flows) {
            var first = placements.get(flow.cells().getFirst());
            if (first == null) continue;
            int start =
                    first.bounds().top()
                            + dy
                            + deltaAt.applyAsInt(first.bounds().top())
                            - panel.scroll;
            flow.place(panel.x, start, panel.w);
        }
        for (int i = 0; i < panel.toolbar.size(); i++) {
            panel.toolbar.get(i).setX(panel.x + i * panel.toolbarPitch());
            panel.toolbar.get(i).setY(panel.y);
        }
        panel.searchY = baseSearchY + dy + deltaAt.applyAsInt(baseSearchY);
        panel.itemRowsY = baseItemY + dy + deltaAt.applyAsInt(baseItemY);
        panel.lootRowsY = baseLootY + dy + deltaAt.applyAsInt(baseLootY);
        for (int i = 0; i < panel.lootRows.size(); i++) {
            var row = panel.lootRows.get(i);
            row.setPosition(panel.x, panel.lootRowsY - panel.scroll + i * 24);
            row.setWidth(Math.max(1, EditorLayout.listWidth(panel.w)));
        }
        panel.inventoryRowsY = baseInventoryY + dy + deltaAt.applyAsInt(baseInventoryY);
        panel.codeDockY =
                baseDockY
                        + dy
                        + flowDeltaBefore(baseDockY, panel.w)
                        + (baseDockY >= inventoryActionEnd ? inventoryActionDelta : 0)
                        + panel.sections.deltaBefore(baseDockY, natural, panel.w);
        panel.contentHeight = baseContentHeight + deltaAt.applyAsInt(Integer.MAX_VALUE);
        panel.cursor = baseCursor + dy + deltaAt.applyAsInt(Integer.MAX_VALUE);
        for (var note : notes)
            note.note()
                    .place(
                            panel,
                            panel.x,
                            note.top() + dy + deltaAt.applyAsInt(note.top()) - panel.scroll,
                            panel.contentWidth());
        if (panel.codeEditor != null) {
            panel.codeDockHeight = dockHeight;
            if (panel.expandedCode()) next.expandedCodeField().apply(panel.codeEditor);
            else
                next.codeDock(panel.codeDockY, panel.scroll, panel.codeDockHeight)
                        .intersect(next.inspectorContent())
                        .apply(panel.codeEditor);
        }
        if (panel.searchStart >= 0) {
            int columns = panel.quickColumns();
            panel.page = registryFirst / columns;
            panel.registryRemainder = registryFirst % columns;
            if (widthChanged) panel.updateSearch(panel.searchTypes);
            else
                for (int i = panel.searchStart; i < panel.searchEnd; i++) {
                    var cell = panel.widgets.get(i);
                    int slot = i - panel.searchStart;
                    cell.setPosition(
                            panel.x + slot % columns * 18,
                            panel.searchY - panel.scroll + slot / columns * 18);
                }
        }
        if (!panel.itemCells.isEmpty()) {
            panel.itemPage = itemFirst / panel.quickColumns();
            panel.itemRemainder = itemFirst % panel.quickColumns();
            if (widthChanged) panel.updateItemRows();
            else
                for (int i = 0; i < panel.itemCells.size(); i++)
                    panel.itemCells
                            .get(i)
                            .setPosition(
                                    panel.x + i % panel.quickColumns() * 18,
                                    panel.itemRowsY - panel.scroll + i / panel.quickColumns() * 18);
        }
        panel.inventoryOffsetX = inventoryX;
        panel.inventoryOffsetY = inventoryY;
        panel.layoutInventory(next);
        panel.sections.contentCells(panel.compactInventoryCells);
        lastInventoryX = panel.inventoryOffsetX;
        lastInventoryY = panel.inventoryOffsetY;
        lastRegistry = panel.page * panel.quickColumns() + panel.registryRemainder;
        lastItem = panel.itemPage * panel.quickColumns() + panel.itemRemainder;
        panel.sections.arrangeActions();
        if (inventoryActionDelta != 0) {
            panel.inventoryWorkspaceAction.visible = false;
            if (panel.focusedWidget() == panel.inventoryWorkspaceAction)
                panel.focusWidget(panel.sections.find("contents").header);
        }
        panel.parameters.tree.arrange();
        int anchoredScroll = preferredScroll;
        if (visibleAnchor != null && panel.widgets.contains(visibleAnchor))
            anchoredScroll =
                    panel.scroll
                            + anchorY(panel, visibleAnchor)
                            - next.inspectorContent().top()
                            - preferredOffset;
        panel.layoutScroll(
                Math.clamp(
                        anchoredScroll,
                        0,
                        Math.max(0, panel.contentHeight - next.inspectorContent().height())));
        if (panel.codeEditor != null && !panel.expandedCode())
            next.codeDock(panel.codeDockY, panel.scroll, panel.codeDockHeight)
                    .intersect(next.inspectorContent())
                    .apply(panel.codeEditor);
        panel.scrollBars();
    }
}
