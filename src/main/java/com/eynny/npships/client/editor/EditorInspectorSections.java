package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.FittedPlaneLayers;
import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.eynny.npships.client.editor.widget.RegistryEditBox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/** Session presentation state. Bounds describe the unfolded arrangement, never clamped widget Y. */
final class EditorInspectorSections {
    static final int HEADER_HEIGHT = 16;

    static EditorLayout.Rect disclosureStroke(boolean expanded, int column) {
        return expanded
                ? new EditorLayout.Rect(column, column, 5 - column, column + 1)
                : new EditorLayout.Rect(column, column - 2, column + 1, 3 - column);
    }

    final class Section {
        final String id, preference;
        final int bodyStart;
        final Header header;
        int end;
        boolean expanded;
        boolean operation;
        BooleanSupplier workflow;
        Supplier<Component> workflowHint, workflowLabel;
        EditorToolsPanel.Flow completion;
        BooleanSupplier completionVisible;
        AbstractWidget suspendedFocus, workspaceAction;
        long focusSequence;

        Section(String id, Component title, boolean open) {
            this.id = id;
            String compatible =
                    id.equals("data")
                                    || id.equals("position")
                                    || id.equals("tables")
                                    || id.equals("transform")
                            ? family.substring(0, family.indexOf(':')) + ":selected"
                            : family;
            preference = compatible + "/" + id;
            expanded = preferences.computeIfAbsent(preference, ignored -> open);
            header = new Header(this, title);
            bodyStart = panel.cursor + HEADER_HEIGHT;
        }

        String modeLabel() {
            return workflow != null && workflow.getAsBoolean() && workflowLabel != null
                    ? workflowLabel.get().getString()
                    : "";
        }
    }

    private final EditorToolsPanel panel;
    private final Map<String, Boolean> preferences = new LinkedHashMap<>();
    private final Map<String, Section> groups = new LinkedHashMap<>();
    private final Map<AbstractWidget, Section> owners = new IdentityHashMap<>();
    private final Map<AbstractWidget, Boolean> visibility = new IdentityHashMap<>();
    private String family = "";
    private Section current, pending;
    private boolean pendingExpansion;
    private String registry = "", contents = "";

    EditorInspectorSections(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void reset() {
        groups.clear();
        owners.clear();
        visibility.clear();
        current = pending = null;
        registry = contents = "";
        family =
                switch (panel.tool) {
                    case BLOCKS ->
                            "blocks:"
                                    + (panel.pos() == null
                                            ? "placement"
                                            : panel.doc().blockName(panel.pos()));
                    case ENTITIES ->
                            "entities:"
                                    + (panel.entityIndex < 0
                                            ? "placement"
                                            : panel.doc()
                                                    .entitiesCopy()
                                                    .getCompound(panel.entityIndex)
                                                    .getCompound("nbt")
                                                    .getString("id"));
                    case SEGMENTS -> "segments:parent";
                    case ZONES -> "zones:editor";
                    case GLUE -> "glue:editor";
                    case LOOT ->
                            "loot:"
                                    + (panel.pos() == null
                                            ? "catalog"
                                            : panel.doc().blockName(panel.pos()));
                    case PATCHES -> "patches:" + (panel.pos() == null ? "devices" : patchFamily());
                    case AREA -> areaFamily();
                    case STYLES -> "styles:gallery";
                    default -> "";
                };
    }

    void reset(String context) {
        reset();
        family = context;
    }

    private String patchFamily() {
        String kind =
                com.eynny.npships.authoring.patches.PatchDevices.kind(panel.doc(), panel.pos());
        return kind.isEmpty() ? panel.doc().blockName(panel.pos()) : kind;
    }

    private String areaFamily() {
        var layer = panel.layers.find(panel.selectedAreaId());
        return "areas:"
                + (!panel.inspectedArea.isEmpty()
                        ? "inspection"
                        : FittedPlaneLayers.plane(layer)
                                ? FittedPlaneLayers.locked(layer) ? "locked_plane" : "live_plane"
                                : "custom");
    }

    boolean enabled() {
        return !family.isEmpty() && !panel.massRegionEditing;
    }

    boolean any() {
        return !groups.isEmpty();
    }

    String currentId() {
        return current == null ? "" : current.id;
    }

    void forget(List<AbstractWidget> widgets) {
        for (var widget : widgets) {
            owners.remove(widget);
            visibility.remove(widget);
        }
    }

    void begin(String id, String title, boolean open) {
        if (!enabled()) return;
        if (current != null) throw new IllegalStateException("Nested inspector sections: " + id);
        current = new Section(id, panel.tr(title), open);
        groups.put(id, current);
        panel.widgets.add(current.header);
        panel.cursor += HEADER_HEIGHT;
    }

    void end() {
        if (current == null) return;
        current.end = panel.cursor;
        for (var widget : panel.widgets)
            if (widget != current.header
                    && widget.getY() >= current.bodyStart
                    && widget.getY() < current.end) own(widget, current);
        current = null;
    }

    void group(String id, String title, boolean open, Runnable builder) {
        begin(id, title, open);
        try {
            builder.run();
        } finally {
            end();
        }
    }

    private void own(AbstractWidget widget, Section section) {
        if (section == null) return;
        owners.put(widget, section);
        visibility.putIfAbsent(widget, widget.visible);
    }

    void registry() {
        registry = current == null ? "" : current.id;
    }

    void contents() {
        contents = current == null ? "" : current.id;
    }

    void registryCells(List<AbstractWidget> cells) {
        for (var widget : cells) own(widget, groups.get(registry));
        applyVisibility();
    }

    void contentCells(List<AbstractWidget> cells) {
        for (var widget : cells) own(widget, groups.get(contents));
        applyVisibility();
    }

    void tableCells(List<AbstractWidget> cells) {
        for (var widget : cells) own(widget, groups.get("tables"));
        applyVisibility();
    }

    void noteCells(List<? extends AbstractWidget> cells) {
        var section = current;
        if (section == null)
            for (var cell : cells)
                if (owners.containsKey(cell)) {
                    section = owners.get(cell);
                    break;
                }
        for (var cell : cells) own(cell, section);
        applyVisibility();
    }

    boolean tablesVisible() {
        return !groups.containsKey("table_editor") && open("tables");
    }

    boolean registryVisible() {
        return open(registry);
    }

    boolean contentsVisible() {
        return open(contents);
    }

    private boolean open(String id) {
        var section = groups.get(id);
        return section == null || section.expanded;
    }

    void workspaceAction(AbstractWidget widget) {
        if (current != null) current.workspaceAction = widget;
    }

    void code(LuaEditBox editor) {
        own(editor, current);
    }

    void operationAction(AbstractWidget widget) {
        if (current == null) return;
        current.operation = true;
        current.workspaceAction = widget;
    }

    /** Read current operation state; the section never acquires or changes that operation. */
    void workflow(BooleanSupplier active, Supplier<Component> hint) {
        if (current == null) return;
        current.workflow = active;
        current.workflowHint = hint;
    }

    void workflowLabel(Supplier<Component> label) {
        if (current != null) current.workflowLabel = label;
    }

    /** Pin the same completion controls; callbacks and operation state stay with the tool. */
    void completionRow(EditorToolsPanel.Flow row, BooleanSupplier visible) {
        if (current == null) return;
        current.completion = row;
        current.completionVisible = visible;
    }

    private boolean completion(Section section) {
        return section.completion != null && section.completionVisible.getAsBoolean();
    }

    private int retainedHeight(Section section, int width) {
        int height = pinned(section) ? 24 : 0;
        if (completion(section)) {
            var row = section.completion;
            height += row.height(width);
        }
        return height;
    }

    void revealCodeSection() {
        var section = owners.get(panel.codeEditor);
        if (section != null) expansion(section.id, true);
    }

    private boolean workspace(Section section) {
        return owners.get(panel.codeEditor) == section && panel.expandedCode()
                || section.id.equals("contents") && panel.inventoryExpanded;
    }

    private boolean pinned(Section section) {
        return workspace(section) || section.operation && panel.linkRole != null;
    }

    int deltaBefore(int at, IntUnaryOperator natural, int width) {
        int delta = 0;
        for (var section : groups.values())
            if (!section.expanded && at >= section.end)
                delta -=
                        Math.max(
                                0,
                                natural.applyAsInt(section.end)
                                        - natural.applyAsInt(section.bodyStart)
                                        - retainedHeight(section, width));
        return delta;
    }

    void applyVisibility() {
        for (var entry : owners.entrySet()) {
            var widget = entry.getKey();
            var section = entry.getValue();
            widget.visible =
                    visibility.getOrDefault(widget, true)
                            && (widget != panel.inventoryWorkspaceAction
                                    || panel.inventoryExpanded
                                    || panel.inventoryNeedsExpansion(panel.layout()))
                            && (section.expanded
                                    || widget == panel.codeEditor && panel.expandedCode()
                                    || widget == section.workspaceAction && pinned(section)
                                    || completion(section)
                                            && section.completion.cells().contains(widget));
        }
    }

    void arrangeActions() {
        for (var section : groups.values()) {
            if (!section.expanded && pinned(section) && section.workspaceAction != null)
                section.workspaceAction.setY(section.header.getY() + HEADER_HEIGHT);
            if (!section.expanded && completion(section)) {
                var row = section.completion;
                int top = section.header.getY() + HEADER_HEIGHT + (pinned(section) ? 24 : 0);
                row.place(panel.x, top, panel.w);
            }
        }
        applyVisibility();
    }

    boolean inspectorVisible(AbstractWidget widget) {
        return widget.visible && (widget != panel.codeEditor || !panel.expandedCode());
    }

    boolean inputAvailable(AbstractWidget widget) {
        return widget != null && widget.visible;
    }

    boolean sameSection(AbstractWidget first, AbstractWidget second) {
        return first != null
                && second != null
                && owners.containsKey(first)
                && owners.get(first) == owners.get(second);
    }

    AbstractWidget scrollAnchor(AbstractWidget widget) {
        var section = owners.get(widget);
        return section != null && !section.expanded ? section.header : widget;
    }

    Section find(String id) {
        return groups.get(id);
    }

    void expansion(String id, boolean expanded) {
        var section = groups.get(id);
        if (section == null || section.expanded == expanded) return;
        if (panel.captureActive()) {
            pending = section;
            pendingExpansion = expanded;
            return;
        }
        panel.preferInspectorScroll();
        section.expanded = expanded;
        preferences.put(section.preference, expanded);
        if (!expanded
                && owners.get(panel.focusedWidget()) == section
                && !(panel.focusedWidget() == panel.codeEditor && panel.expandedCode())) {
            section.suspendedFocus = panel.focusedWidget();
            panel.focusWidget(section.header);
            section.focusSequence = panel.focusSequence;
        }
        panel.layoutOnly(panel.layout());
        if (expanded
                && section.suspendedFocus != null
                && panel.focusedWidget() == section.header
                && panel.focusSequence == section.focusSequence) {
            var restore = section.suspendedFocus;
            section.suspendedFocus = null;
            if (restore instanceof RegistryEditBox box) box.focusRetained();
            panel.focusWidget(restore);
        }
    }

    void released() {
        panel.parameters.tree.released();
        if (pending == null || panel.captureActive()) return;
        var section = pending;
        pending = null;
        expansion(section.id, pendingExpansion);
    }

    boolean click(double x, double y, int button) {
        if (!panel.layout().inspectorClip().contains(x, y)) return false;
        for (var section : groups.values())
            if (section.header.isMouseOver(x, y) && button == 0) {
                var before = panel.focusedWidget();
                boolean opening = !section.expanded;
                expansion(section.id, !section.expanded);
                if (pending == section) return true;
                boolean restored =
                        opening
                                && before == section.header
                                && owners.get(panel.focusedWidget()) == section;
                if (!restored && !(before == panel.codeEditor && panel.expandedCode()))
                    panel.focusWidget(section.header);
                return true;
            }
        return panel.parameters.tree.click(x, y, button);
    }

    boolean headerKey(int key, int scan, int modifiers) {
        if (!(panel.focusedWidget() instanceof Header header)) return false;
        if (key == GLFW.GLFW_KEY_SPACE
                || key == GLFW.GLFW_KEY_ENTER
                || key == GLFW.GLFW_KEY_KP_ENTER) {
            expansion(header.section.id, !header.section.expanded);
            return true;
        }
        return false;
    }

    void reveal(Object field) {
        if (!(field instanceof AbstractWidget widget)) return;
        if (panel.parameters.tree.reveal(widget)) return;
        var section = owners.get(widget);
        if (section == null) return;
        panel.validationNavigation++;
        // Failed explicit action only; live validation never invokes this navigation.
        if (!section.expanded) expansion(section.id, true);
        panel.revealWidget(widget);
        panel.focusWidget(widget);
    }

    private boolean active(Section section) {
        var focus = panel.focusedWidget();
        return section.operation && panel.linkRole != null
                || section.workflow != null && section.workflow.getAsBoolean()
                || owners.get(focus) == section && inputAvailable(focus)
                || panel.host.creating()
                        && (section.id.equals("brush")
                                || section.id.equals("list")
                                        && (panel.tool == EditorToolsPanel.Tool.SEGMENTS
                                                || panel.tool == EditorToolsPanel.Tool.ZONES
                                                || panel.tool == EditorToolsPanel.Tool.GLUE));
    }

    private boolean expandedWorkspace(Section section) {
        return workspace(section) || section.id.equals("contents") && panel.inventoryExpanded;
    }

    private String failure(Section section) {
        return panel.host.validation.sectionError(section.id);
    }

    record HeaderGeometry(
            String title,
            String mode,
            int titleX,
            int modeX,
            int badgeX,
            int textY,
            EditorLayout.Rect separator) {}

    HeaderGeometry headerGeometry(String id, String badge) {
        var section = groups.get(id);
        var header = section.header;
        var font = panel.mc.font;
        int available = Math.max(0, header.getWidth() - 14 - font.width(badge));
        String mode = font.plainSubstrByWidth(section.modeLabel(), available / 2);
        int modeWidth = mode.isEmpty() ? 0 : font.width(mode) + EditorLayout.CONTROL_GAP;
        String title =
                font.plainSubstrByWidth(
                        header.getMessage().getString(), Math.max(0, available - modeWidth));
        int titleX = Math.min(header.getRight(), header.getX() + 11);
        int badgeX = header.getRight() - font.width(badge) - 2;
        int modeX = badgeX - modeWidth;
        int end =
                Math.max(
                        titleX,
                        mode.isEmpty()
                                ? badge.isEmpty()
                                        ? header.getRight() - 2
                                        : badgeX - EditorLayout.CONTROL_GAP
                                : modeX - EditorLayout.CONTROL_GAP);
        int start = Math.min(end, titleX + font.width(title) + EditorLayout.CONTROL_GAP);
        int lineY = header.getY() + HEADER_HEIGHT / 2;
        return new HeaderGeometry(
                title,
                mode,
                titleX,
                modeX,
                badgeX,
                header.getY() + 3,
                new EditorLayout.Rect(start, lineY, end, lineY + 1));
    }

    private final class Header extends Button {
        final Section section;
        private String tooltipState;

        Header(Section section, Component title) {
            super(
                    panel.x,
                    panel.cursor,
                    Math.max(1, panel.contentWidth()),
                    HEADER_HEIGHT,
                    title,
                    b -> expansion(section.id, !section.expanded),
                    DEFAULT_NARRATION);
            this.section = section;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
            var error = failure(section);
            boolean current = active(section);
            String badge =
                    error != null
                            ? "!"
                            : pending == section
                                    ? "..."
                                    : expandedWorkspace(section)
                                            ? "+"
                                            : panel.host.validation.sectionPending(section.id)
                                                    ? "~"
                                                    : "";
            String help =
                    panel.tr("section_hint").getString()
                            + (error == null ? "" : "\n" + error)
                            + (expandedWorkspace(section)
                                    ? "\n" + panel.tr("section_workspace_hint").getString()
                                    : "")
                            + (section.workflow != null
                                            && section.workflow.getAsBoolean()
                                            && section.workflowHint != null
                                    ? "\n" + section.workflowHint.get().getString()
                                    : "");
            if (!help.equals(tooltipState)) {
                tooltipState = help;
                setTooltip(Tooltip.create(Component.literal(help)));
            }
            if (current || isHovered())
                g.fill(
                        getX(),
                        getY(),
                        getRight(),
                        getBottom() - 1,
                        EditorButtonTheme.mix(
                                EditorButtonTheme.panel(),
                                EditorButtonTheme.accent(),
                                current ? .18f : .08f));
            int color = isFocused() ? EditorButtonTheme.accent() : EditorButtonTheme.muted();
            int ax = getX() + 2, ay = getY() + 5;
            for (int i = 0; i < 3; i++) {
                var stroke = disclosureStroke(section.expanded, i);
                g.fill(
                        ax + stroke.left(),
                        ay + stroke.top(),
                        ax + stroke.right(),
                        ay + stroke.bottom(),
                        color);
            }
            var geometry = headerGeometry(section.id, badge);
            g.drawString(
                    panel.mc.font,
                    geometry.title(),
                    geometry.titleX(),
                    geometry.textY(),
                    EditorButtonTheme.text(),
                    false);
            if (!geometry.mode().isEmpty())
                g.drawString(
                        panel.mc.font,
                        geometry.mode(),
                        geometry.modeX(),
                        geometry.textY(),
                        EditorButtonTheme.accent(),
                        false);
            if (!badge.isEmpty())
                g.drawString(
                        panel.mc.font,
                        badge,
                        geometry.badgeX(),
                        geometry.textY(),
                        error != null ? 0xffff7070 : EditorButtonTheme.accent(),
                        false);
            geometry.separator()
                    .fill(g, isFocused() ? EditorButtonTheme.accent() : EditorButtonTheme.edge());
        }
    }
}
