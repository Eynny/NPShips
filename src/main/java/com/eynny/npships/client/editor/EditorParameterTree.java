package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.RegistryEditBox;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A sign tree's hidden rows use the inspector's existing Binding and Flow arrangements. */
final class EditorParameterTree {
    record Path(List<String> keys) {
        Path {
            keys = List.copyOf(keys);
        }

        Path child(String key) {
            var parts = new ArrayList<>(keys);
            parts.add(key);
            return new Path(parts);
        }

        String display() {
            return keys.stream()
                    .map(k -> "/" + k.replace("~", "~0").replace("/", "~1"))
                    .collect(java.util.stream.Collectors.joining());
        }
    }

    enum Kind {
        GROUP,
        PALETTE,
        FIELDS
    }

    record Key(Path path, Kind kind) {}

    private interface Entry {
        int height(int width);

        void place(int top, boolean visible);

        void save();

        void widgets(List<AbstractWidget> target);
    }

    final class Node implements Entry {
        final Key key;
        final Node parent;
        final EditorButton header;
        final List<Entry> children = new ArrayList<>();
        final Consumer<Node> builder;
        final Row heading;
        boolean open, built;
        boolean stale;
        List<Byte> listShape;
        java.util.Set<String> compoundKeys;
        AbstractWidget suspended;
        long focusSequence;

        Node(Node parent, Key key, Component title, Consumer<Node> builder) {
            this.parent = parent;
            this.key = key;
            this.builder = builder;
            open = preferences.contains(key);
            header =
                    EditorButton.flat(title, b -> expansion(this, !open))
                            .bounds(panel.x, 0, Math.max(1, panel.contentWidth()), 20)
                            .build()
                            .selected(() -> open);
            if (parent != null) {
                header.setTooltip(
                        net.minecraft.client.gui.components.Tooltip.create(
                                Component.literal(key.path().display())));
                updateTitle(title);
            }
            panel.widgets.add(header);
            nodes.add(this);
            panel.stretch(header);
            owners.put(header, this);
            panel.host.validation.register(header, section(key.path()));
            heading = new Row(this, List.of(header), 24, panel.w, List.of(), List.of());
            if (open) ensureBuilt();
        }

        private Component title;

        void updateTitle(Component text) {
            title = text;
            header.setMessage(Component.literal(open ? "\u25bc " : "\u25b6 ").append(text));
        }

        void ensureBuilt() {
            if (built || expired || stale) return;
            built = true;
            builder.accept(this);
        }

        @Override
        public int height(int width) {
            return 24
                    + (open && !stale ? children.stream().mapToInt(e -> e.height(width)).sum() : 0);
        }

        @Override
        public void place(int top, boolean visible) {
            heading.place(top, visible);
            int next = top + 24;
            for (var child : children) {
                child.place(next, visible && open && !stale);
                next += child.height(panel.w);
            }
        }

        @Override
        public void save() {
            children.forEach(Entry::save);
        }

        @Override
        public void widgets(List<AbstractWidget> target) {
            target.add(header);
            children.forEach(e -> e.widgets(target));
        }
    }

    private final class Row implements Entry {
        final List<AbstractWidget> cells;
        final List<EditorWidgetArrangement.Binding> bindings;
        final List<EditorToolsPanel.Flow> flows;
        final List<Runnable> savers;
        final int baseHeight, baseWidth;
        final Node owner;
        final List<Boolean> active;

        Row(
                Node owner,
                List<AbstractWidget> cells,
                int height,
                int width,
                List<EditorToolsPanel.Flow> flows,
                List<Runnable> savers) {
            this.cells = List.copyOf(cells);
            this.owner = owner;
            active = cells.stream().map(w -> w.active).toList();
            this.flows = List.copyOf(flows);
            this.savers = List.copyOf(savers);
            baseHeight = height;
            baseWidth = width;
            var bounds = new EditorLayout.Rect(panel.x, 0, panel.x + width, height);
            bindings =
                    cells.stream()
                            .map(
                                    w -> {
                                        owners.put(w, owner);
                                        return EditorWidgetArrangement.Binding.capture(
                                                w,
                                                bounds,
                                                0,
                                                panel.horizontalRoles.getOrDefault(
                                                        w,
                                                        EditorWidgetArrangement.Horizontal
                                                                .AUTOMATIC));
                                    })
                            .toList();
        }

        @Override
        public int height(int width) {
            return baseHeight
                    + flows.stream().mapToInt(f -> f.height(width) - f.height(baseWidth)).sum();
        }

        @Override
        public void place(int top, boolean visible) {
            var bounds =
                    new EditorLayout.Rect(panel.x, top, panel.x + panel.w, top + height(panel.w));
            boolean obsolete = expired;
            for (var node = owner; node != null; node = node.parent)
                if (node.stale && !cells.contains(node.header)) obsolete = true;
            for (int i = 0; i < bindings.size(); i++) {
                var binding = bindings.get(i);
                binding.apply(bounds, 0, true);
                binding.widget().visible = visible && !expired;
                binding.widget().active = active.get(i) && !obsolete;
            }
            for (var flow : flows) {
                var first =
                        bindings.stream()
                                .filter(b -> b.widget() == flow.cells().getFirst())
                                .findFirst()
                                .orElseThrow();
                flow.place(panel.x, top + first.bounds().top(), panel.w);
            }
        }

        @Override
        public void save() {
            savers.forEach(Runnable::run);
        }

        @Override
        public void widgets(List<AbstractWidget> target) {
            target.addAll(cells);
        }
    }

    private final EditorToolsPanel panel;
    private final java.util.Set<Key> preferences = new java.util.HashSet<>();
    private final Map<AbstractWidget, Node> owners = new IdentityHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private net.minecraft.nbt.CompoundTag acceptedData;
    private Node root, pending;
    private boolean pendingOpen, expired;
    private String scope = "";

    EditorParameterTree(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void reset() {
        expire();
        owners.clear();
        nodes.clear();
        root = pending = null;
        expired = false;
    }

    void build(
            String scope,
            Component title,
            net.minecraft.nbt.CompoundTag acceptedData,
            Consumer<Node> builder) {
        if (!this.scope.equals(scope)) {
            this.scope = scope;
            preferences.clear();
        }
        int top = panel.cursor;
        this.acceptedData = acceptedData;
        root = new Node(null, new Key(new Path(List.of()), Kind.GROUP), title, builder);
        root.header.setY(top);
        panel.cursor = top + height(panel.w);
        reorder();
        arrange();
    }

    Node node(Node parent, Path path, Kind kind, Component title, Consumer<Node> builder) {
        var node = new Node(parent, new Key(path, kind), title, builder);
        parent.children.add(node);
        return node;
    }

    void row(Node node, Runnable builder) {
        int cursor = panel.cursor,
                cells = panel.widgets.size(),
                flows = panel.flows.size(),
                savers = panel.fieldSavers.size();
        var last = panel.lastField;
        panel.cursor = 0;
        try {
            builder.run();
            var row =
                    new Row(
                            node,
                            panel.widgets.subList(cells, panel.widgets.size()),
                            panel.cursor,
                            panel.w,
                            panel.flows.subList(flows, panel.flows.size()),
                            panel.fieldSavers.subList(savers, panel.fieldSavers.size()));
            node.children.add(row);
        } finally {
            panel.flows.subList(flows, panel.flows.size()).clear();
            panel.fieldSavers.subList(savers, panel.fieldSavers.size()).clear();
            panel.cursor = cursor;
            panel.lastField = last;
        }
    }

    int height(int width) {
        return root == null || expired ? 0 : root.height(width);
    }

    AbstractWidget root() {
        return root == null ? null : root.header;
    }

    boolean owns(AbstractWidget widget) {
        return owners.containsKey(widget);
    }

    boolean body(AbstractWidget widget) {
        return owns(widget) && widget != root();
    }

    String section(Path path) {
        return "parameter:" + scope + path.display();
    }

    void saveFields() {
        if (!expired && root != null) root.save();
    }

    void structure(Node node, net.minecraft.nbt.Tag source) {
        if (source instanceof net.minecraft.nbt.CollectionTag<?> list) node.listShape = shape(list);
        if (node.parent != null && source instanceof net.minecraft.nbt.CompoundTag compound)
            node.compoundKeys = java.util.Set.copyOf(compound.getAllKeys());
        checkStructure(node);
    }

    private List<Byte> shape(net.minecraft.nbt.CollectionTag<?> list) {
        return java.util.stream.IntStream.range(0, list.size())
                .mapToObj(i -> list.get(i).getId())
                .toList();
    }

    net.minecraft.nbt.Tag value(net.minecraft.nbt.Tag tag, Path path) {
        for (var key : path.keys()) {
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) tag = compound.get(key);
            else if (tag instanceof net.minecraft.nbt.CollectionTag<?> list) {
                try {
                    int index = Integer.parseInt(key);
                    tag = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else return null;
        }
        return tag;
    }

    boolean stale() {
        return nodes.stream().anyMatch(n -> n.stale);
    }

    private void checkStructure(Node node) {
        if (node.listShape == null && node.compoundKeys == null) return;
        var tag = value(acceptedData, node.key.path());
        node.stale =
                node.listShape != null
                        ? !(tag instanceof net.minecraft.nbt.CollectionTag<?> list)
                                || !node.listShape.equals(shape(list))
                        : !(tag instanceof net.minecraft.nbt.CompoundTag compound)
                                || !node.compoundKeys.equals(compound.getAllKeys());
    }

    void acceptedLists(net.minecraft.nbt.CompoundTag data) {
        acceptedData = data;
        for (var node : List.copyOf(nodes)) {
            boolean previous = node.stale;
            checkStructure(node);
            if (previous == node.stale) continue;
            if (node.stale) {
                node.header.setTooltip(
                        net.minecraft.client.gui.components.Tooltip.create(
                                Component.literal(node.key.path().display())
                                        .append("\n")
                                        .append(
                                                Component.translatable(
                                                        "npships.error.sign_compound_conflict",
                                                        node.key.path().keys().getFirst(),
                                                        scope))));
                if (descendant(owners.get(panel.focusedWidget()), node))
                    panel.focusWidget(node.header);
            } else
                node.header.setTooltip(
                        net.minecraft.client.gui.components.Tooltip.create(
                                Component.literal(node.key.path().display())));
        }
        panel.layoutOnly(panel.layout());
    }

    AbstractWidget field(Path path) {
        return owners.entrySet().stream()
                .filter(
                        e ->
                                e.getValue().key.equals(new Key(path, Kind.GROUP))
                                        && e.getKey() == e.getValue().header)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(root());
    }

    void arrange() {
        if (root == null) return;
        // The root's ordinary inspector binding has already positioned it.
        int next = root.header.getY() + 24;
        root.header.visible = !expired;
        for (var child : root.children) {
            child.place(next, root.open && !expired);
            next += child.height(panel.w);
        }
    }

    private void reorder() {
        var widgets = new ArrayList<AbstractWidget>();
        root.widgets(widgets);
        int at = panel.widgets.indexOf(root.header);
        panel.widgets.removeAll(widgets);
        panel.widgets.addAll(at, widgets);
    }

    private boolean descendant(Node node, Node ancestor) {
        for (; node != null; node = node.parent) if (node == ancestor) return true;
        return false;
    }

    void expansion(Node node, boolean open) {
        if (expired || node.open == open) return;
        if (panel.captureActive()) {
            pending = node;
            pendingOpen = open;
            return;
        }
        panel.preferInspectorScroll();
        node.open = open;
        if (open) preferences.add(node.key);
        else preferences.remove(node.key);
        if (node.title != null) node.updateTitle(node.title);
        node.ensureBuilt();
        reorder();
        var focused = panel.focusedWidget();
        if (!open && focused != node.header && descendant(owners.get(focused), node)) {
            node.suspended = focused;
            panel.focusWidget(node.header);
            node.focusSequence = panel.focusSequence;
        }
        panel.layoutOnly(panel.layout());
        if (open
                && node.suspended != null
                && panel.focusedWidget() == node.header
                && panel.focusSequence == node.focusSequence
                && node.suspended.visible) {
            var restore = node.suspended;
            node.suspended = null;
            if (restore instanceof RegistryEditBox box) box.focusRetained();
            panel.focusWidget(restore);
        }
    }

    void released() {
        if (pending == null || panel.captureActive()) return;
        var node = pending;
        pending = null;
        expansion(node, pendingOpen);
    }

    boolean click(double x, double y, int button) {
        if (expired || button != 0) return false;
        for (var node : List.copyOf(nodes))
            if (node.header.visible && node.header.isMouseOver(x, y)) {
                expansion(node, !node.open);
                return true;
            }
        return false;
    }

    AbstractWidget scrollAnchor(AbstractWidget widget) {
        var node = owners.get(widget);
        var result = widget;
        for (; node != null; node = node.parent) if (!node.open || node.stale) result = node.header;
        return result;
    }

    boolean reveal(AbstractWidget widget) {
        var node = owners.get(widget);
        if (node == null || expired) return false;
        var ancestors = new ArrayList<Node>();
        for (var at = node; at != null; at = at.parent) ancestors.addFirst(at);
        for (var ancestor : ancestors)
            if (ancestor.stale) {
                panel.validationNavigation++;
                panel.revealWidget(ancestor.header);
                panel.focusWidget(ancestor.header);
                return true;
            }
        for (var ancestor : ancestors) expansion(ancestor, true);
        panel.validationNavigation++;
        panel.revealWidget(widget);
        if (widget instanceof RegistryEditBox box) box.focusRetained();
        panel.focusWidget(widget);
        return true;
    }

    void expire() {
        expired = true;
        for (var widget : owners.keySet()) {
            widget.active = widget.visible = false;
            panel.host.validation.forget(widget);
        }
        if (owns(panel.focusedWidget())) panel.focusWidget(null);
        pending = null;
    }
}
