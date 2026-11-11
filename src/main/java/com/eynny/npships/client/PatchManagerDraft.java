package com.eynny.npships.client;

import com.eynny.npships.network.PatchManagerModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One screen's detached draft and stable-ID selection; never reads the client mod registry. */
final class PatchManagerDraft {
    final List<PatchManagerModel.Row> rows;
    final Map<String, PatchManagerModel.Row> byId;
    final List<String> originalOrder;
    final Set<String> originalDisabled;
    List<String> order;
    Set<String> disabled;
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    String anchor, focused;
    boolean reordered, resetOrder;
    private boolean changedDisabled;

    PatchManagerDraft(List<PatchManagerModel.Row> input) {
        rows = List.copyOf(input);
        var values = new LinkedHashMap<String, PatchManagerModel.Row>();
        for (var row : rows)
            if (values.putIfAbsent(row.id(), row) != null)
                throw new IllegalArgumentException("Duplicate server provider ID: " + row.id());
        byId = Map.copyOf(values);
        originalOrder = PatchManagerModel.knownOrder(rows);
        originalDisabled = PatchManagerModel.configuredDisabled(rows);
        order = originalOrder;
        disabled = originalDisabled;
    }

    boolean dirty() {
        return reordered || resetOrder || changedDisabled;
    }

    List<String> visible(String query) {
        String text = query.toLowerCase(Locale.ROOT).strip();
        var result = new ArrayList<String>();
        for (var id : order) if (matches(byId.get(id), text)) result.add(id);
        for (var row : rows) if (!row.present() && matches(row, text)) result.add(row.id());
        return List.copyOf(result);
    }

    private boolean matches(PatchManagerModel.Row row, String query) {
        return query.isEmpty()
                || row.id().toLowerCase(Locale.ROOT).contains(query)
                || row.owner().toLowerCase(Locale.ROOT).contains(query)
                || row.displayName().toLowerCase(Locale.ROOT).contains(query)
                || row.ownerDisplayName().toLowerCase(Locale.ROOT).contains(query)
                || row.stages().stream()
                        .anyMatch(stage -> stage.toLowerCase(Locale.ROOT).contains(query));
    }

    void select(List<String> visible, String id, boolean control, boolean shift) {
        if (!visible.contains(id)) return;
        if (shift && anchor != null && visible.contains(anchor)) {
            if (!control) selected.clear();
            int from = visible.indexOf(anchor), to = visible.indexOf(id);
            selected.addAll(visible.subList(Math.min(from, to), Math.max(from, to) + 1));
        } else {
            if (!control) selected.clear();
            if (!control || !selected.remove(id)) selected.add(id);
            anchor = id;
        }
        focused = id;
    }

    void selectAll(List<String> visible) {
        selected.clear();
        selected.addAll(visible);
        if (!visible.isEmpty()) {
            anchor = visible.getFirst();
            focused = visible.getLast();
        }
    }

    private String mutableSelection() {
        return mutableSelection(selected);
    }

    private String mutableSelection(Set<String> targets) {
        if (targets.isEmpty()) return "npships.patch_manager.select_first";
        for (var id : targets)
            if (!byId.get(id).present()) return "npships.patch_manager.absent_read_only";
        return "";
    }

    String enabled(boolean enabled, Set<String> targets) {
        String invalid = mutableSelection(targets);
        if (!invalid.isEmpty()) return invalid;
        var candidate = new HashSet<>(disabled);
        if (enabled) candidate.removeAll(targets);
        else candidate.addAll(targets);
        String error =
                PatchManagerModel.validate(rows, originalDisabled, originalOrder, candidate, order);
        if (error.isEmpty()) {
            disabled = Set.copyOf(candidate);
            changedDisabled = !disabled.equals(originalDisabled);
        }
        return error;
    }

    List<String> dropped(int boundary) {
        var moving = order.stream().filter(selected::contains).toList();
        if (moving.isEmpty()) return order;
        boundary = Math.clamp(boundary, 0, order.size());
        int before = 0;
        for (int i = 0; i < boundary; i++) if (!selected.contains(order.get(i))) before++;
        var next = new ArrayList<>(order);
        next.removeAll(selected);
        next.addAll(before, moving);
        return List.copyOf(next);
    }

    String validateDrop(int boundary) {
        String invalid = mutableSelection();
        return !invalid.isEmpty()
                ? invalid
                : PatchManagerModel.validate(
                        rows, originalDisabled, originalOrder, disabled, dropped(boundary));
    }

    String drop(int boundary) {
        String error = validateDrop(boundary);
        if (error.isEmpty()) {
            var next = dropped(boundary);
            if (!next.equals(order)) {
                order = next;
                reordered = !order.equals(originalOrder);
                resetOrder = false;
            }
        }
        return error;
    }

    /** A row arrow changes that row only and preserves the multi-selection. */
    String moveOne(String id, int direction) {
        if (!byId.containsKey(id) || !byId.get(id).present())
            return "npships.patch_manager.absent_read_only";
        int from = order.indexOf(id), to = from + direction;
        if (from < 0 || to < 0 || to >= order.size()) return "";
        var next = new ArrayList<>(order);
        java.util.Collections.swap(next, from, to);
        String error =
                PatchManagerModel.validate(rows, originalDisabled, originalOrder, disabled, next);
        if (error.isEmpty()) {
            order = List.copyOf(next);
            reordered = !order.equals(originalOrder);
            resetOrder = false;
        }
        return error;
    }

    String move(int direction, boolean edge) {
        String invalid = mutableSelection();
        if (!invalid.isEmpty()) return invalid;
        int first = -1, last = -1;
        for (int i = 0; i < order.size(); i++)
            if (selected.contains(order.get(i))) {
                if (first < 0) first = i;
                last = i;
            }
        if (first < 0) return "npships.patch_manager.select_first";
        return drop(
                edge
                        ? direction < 0 ? 0 : order.size()
                        : direction < 0
                                ? Math.max(0, first - 1)
                                : Math.min(order.size(), last + 2));
    }

    String defaults() {
        // Clearing saved known preferences gives every known type the same rank; the server view
        // then breaks display ties by stable ID. Preview that result before Apply as well.
        var defaultOrder =
                rows.stream()
                        .filter(PatchManagerModel.Row::present)
                        .map(PatchManagerModel.Row::id)
                        .sorted()
                        .toList();
        String error = PatchManagerModel.validateDefaults(rows, Set.of(), defaultOrder);
        if (error.isEmpty()) {
            disabled = Set.of();
            changedDisabled = !disabled.equals(originalDisabled);
            order = defaultOrder;
            reordered = false;
            resetOrder = true;
        }
        return error;
    }
}
