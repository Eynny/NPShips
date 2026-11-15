package com.eynny.npships.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached server metadata and pure draft validation; not another provider registry or policy. */
public final class PatchManagerModel {
    private PatchManagerModel() {}

    public record StageOrder(List<String> before, List<String> after) {
        public StageOrder {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    public record Row(
            String id,
            String owner,
            boolean builtin,
            boolean present,
            boolean available,
            String availability,
            String execution,
            String disabledBehavior,
            boolean configuredEnabled,
            boolean effectiveEnabled,
            int configuredRank,
            int effectiveRank,
            List<String> stages,
            List<String> requires,
            List<String> conflicts,
            Map<String, StageOrder> ordering,
            boolean authoring,
            String displayName,
            String ownerDisplayName,
            com.eynny.npships.api.patches.PatchMetadata metadata) {
        public Row(
                String id,
                String owner,
                boolean builtin,
                boolean present,
                boolean available,
                String availability,
                String execution,
                String disabledBehavior,
                boolean configuredEnabled,
                boolean effectiveEnabled,
                int configuredRank,
                int effectiveRank,
                List<String> stages,
                List<String> requires,
                List<String> conflicts,
                Map<String, StageOrder> ordering,
                boolean authoring,
                String displayName,
                String ownerDisplayName) {
            this(
                    id,
                    owner,
                    builtin,
                    present,
                    available,
                    availability,
                    execution,
                    disabledBehavior,
                    configuredEnabled,
                    effectiveEnabled,
                    configuredRank,
                    effectiveRank,
                    stages,
                    requires,
                    conflicts,
                    ordering,
                    authoring,
                    displayName,
                    ownerDisplayName,
                    null);
        }

        public Row(
                String id,
                String owner,
                boolean builtin,
                boolean present,
                boolean available,
                String availability,
                String execution,
                String disabledBehavior,
                boolean configuredEnabled,
                boolean effectiveEnabled,
                int configuredRank,
                int effectiveRank,
                List<String> stages,
                List<String> requires,
                List<String> conflicts,
                Map<String, StageOrder> ordering,
                boolean authoring) {
            this(
                    id,
                    owner,
                    builtin,
                    present,
                    available,
                    availability,
                    execution,
                    disabledBehavior,
                    configuredEnabled,
                    effectiveEnabled,
                    configuredRank,
                    effectiveRank,
                    stages,
                    requires,
                    conflicts,
                    ordering,
                    authoring,
                    id,
                    owner);
        }

        public Row {
            stages = List.copyOf(stages);
            requires = List.copyOf(requires);
            conflicts = List.copyOf(conflicts);
            ordering = Map.copyOf(ordering);
        }

        public JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("owner", owner);
            json.addProperty("display_name", displayName);
            json.addProperty("owner_display_name", ownerDisplayName);
            json.addProperty("builtin", builtin);
            json.addProperty("present", present);
            json.addProperty("available", available);
            json.addProperty("availability", availability);
            json.addProperty("execution", execution);
            json.addProperty("disabled_behavior", disabledBehavior);
            json.addProperty("configured_enabled", configuredEnabled);
            json.addProperty("effective_enabled", effectiveEnabled);
            json.addProperty("configured_rank", configuredRank);
            json.addProperty("effective_rank", effectiveRank);
            json.add("stages", array(stages));
            json.add("requires", array(requires));
            json.add("conflicts", array(conflicts));
            var graph = new JsonObject();
            ordering.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> {
                                var edges = new JsonObject();
                                edges.add("before", array(entry.getValue().before()));
                                edges.add("after", array(entry.getValue().after()));
                                graph.add(entry.getKey(), edges);
                            });
            json.add("ordering", graph);
            json.addProperty("authoring", authoring);
            if (metadata != null) {
                var display = new JsonObject();
                display.addProperty("name_key", metadata.nameKey());
                display.addProperty("name", metadata.name());
                display.addProperty("description_key", metadata.descriptionKey());
                display.addProperty("description", metadata.description());
                var subjects = new JsonArray();
                for (var subject : metadata.subjects()) {
                    var value = new JsonObject();
                    value.addProperty("domain", subject.domain().toString());
                    value.addProperty("id", subject.id().toString());
                    value.addProperty("label_key", subject.labelKey());
                    value.addProperty("label", subject.label());
                    subjects.add(value);
                }
                display.add("subjects", subjects);
                json.add("display", display);
            }

            return json;
        }

        public static Row fromJson(JsonObject json) {
            var graph = new LinkedHashMap<String, StageOrder>();
            json.getAsJsonObject("ordering")
                    .entrySet()
                    .forEach(
                            entry -> {
                                var edges = entry.getValue().getAsJsonObject();
                                graph.put(
                                        entry.getKey(),
                                        new StageOrder(
                                                strings(edges.getAsJsonArray("before")),
                                                strings(edges.getAsJsonArray("after"))));
                            });
            return new Row(
                    json.get("id").getAsString(),
                    json.get("owner").getAsString(),
                    json.get("builtin").getAsBoolean(),
                    json.get("present").getAsBoolean(),
                    json.get("available").getAsBoolean(),
                    json.get("availability").getAsString(),
                    json.get("execution").getAsString(),
                    json.get("disabled_behavior").getAsString(),
                    json.get("configured_enabled").getAsBoolean(),
                    json.get("effective_enabled").getAsBoolean(),
                    json.get("configured_rank").getAsInt(),
                    json.get("effective_rank").getAsInt(),
                    strings(json.getAsJsonArray("stages")),
                    strings(json.getAsJsonArray("requires")),
                    strings(json.getAsJsonArray("conflicts")),
                    graph,
                    json.get("authoring").getAsBoolean(),
                    json.has("display_name")
                            ? json.get("display_name").getAsString()
                            : json.get("id").getAsString(),
                    json.has("owner_display_name")
                            ? json.get("owner_display_name").getAsString()
                            : json.get("owner").getAsString(),
                    readMetadata(json));
        }
    }

    private static com.eynny.npships.api.patches.PatchMetadata readMetadata(JsonObject row) {
        if (!row.has("display")) return null;
        var json = row.getAsJsonObject("display");
        var subjects = new ArrayList<com.eynny.npships.api.patches.PatchMetadata.Subject>();
        var values = json.getAsJsonArray("subjects");
        if (values.size() > 64) throw new IllegalArgumentException("Too many display subjects");
        for (var element : values) {
            var v = element.getAsJsonObject();
            subjects.add(
                    new com.eynny.npships.api.patches.PatchMetadata.Subject(
                            net.minecraft.resources.ResourceLocation.parse(
                                    v.get("domain").getAsString()),
                            net.minecraft.resources.ResourceLocation.parse(
                                    v.get("id").getAsString()),
                            v.get("label_key").getAsString(),
                            v.get("label").getAsString()));
        }
        return new com.eynny.npships.api.patches.PatchMetadata(
                json.get("name_key").getAsString(),
                json.get("name").getAsString(),
                json.get("description_key").getAsString(),
                json.get("description").getAsString(),
                subjects);
    }

    public static List<String> knownOrder(List<Row> rows) {
        return rows.stream()
                .filter(Row::present)
                .sorted(
                        java.util.Comparator.comparingInt(Row::configuredRank)
                                .thenComparing(Row::id))
                .map(Row::id)
                .toList();
    }

    public static Set<String> configuredDisabled(List<Row> rows) {
        var ids = new HashSet<String>();
        for (var row : rows) if (row.present() && !row.configuredEnabled()) ids.add(row.id());
        return Set.copyOf(ids);
    }

    /** Restore the real empty known-type defaults, including defaults for absent optional mods. */
    public static String validateDefaults(
            List<Row> rows, Set<String> disabled, List<String> order) {
        return disabled.isEmpty()
                ? validate(rows, Set.of(), order, disabled, order)
                : "npships.patch_manager.invalid";
    }

    /** Existing unavailable/default providers stay editable. New enables require available code. */
    public static String validate(
            List<Row> rows,
            Set<String> previousDisabled,
            List<String> previousOrder,
            Set<String> disabled,
            List<String> order) {
        var metadata = new HashMap<String, Row>();
        for (var row : rows) if (row.present()) metadata.put(row.id(), row);
        if (new HashSet<>(order).size() != order.size()
                || !new HashSet<>(order).equals(metadata.keySet())
                || !metadata.keySet().containsAll(disabled)) return "npships.patch_manager.invalid";
        for (String id : previousDisabled)
            if (!disabled.contains(id) && metadata.containsKey(id)) {
                var pending = new ArrayDeque<String>();
                var visited = new HashSet<String>();
                pending.add(id);
                while (!pending.isEmpty()) {
                    String current = pending.removeFirst();
                    if (!visited.add(current)) continue;
                    var row = metadata.get(current);
                    if (row == null || !row.available() || disabled.contains(current))
                        return "Required provider unavailable or disabled: "
                                + current
                                + " ("
                                + id
                                + ")";
                    pending.addAll(row.requires());
                }
            }
        var ranks = ranks(order);
        var previous = ranks(previousOrder);
        // Preference ranks never move a host phase. Only peers in the same stage/unit constrain a
        // drop.
        for (var row : metadata.values()) {
            for (String stage : row.stages()) {
                for (String required : row.requires()) {
                    var peer = metadata.get(required);
                    if (peer != null && peer.stages().contains(stage)) {
                        String error = inversion(required, row.id(), stage, ranks, previous);
                        if (!error.isEmpty()) return error;
                    }
                }
                var edges = row.ordering().get(stage);
                if (edges == null) continue;
                for (String before : edges.before()) {
                    var peer = metadata.get(before);
                    if (peer != null && peer.stages().contains(stage)) {
                        String error = inversion(row.id(), before, stage, ranks, previous);
                        if (!error.isEmpty()) return error;
                    }
                }
                for (String after : edges.after()) {
                    var peer = metadata.get(after);
                    if (peer != null && peer.stages().contains(stage)) {
                        String error = inversion(after, row.id(), stage, ranks, previous);
                        if (!error.isEmpty()) return error;
                    }
                }
            }
        }
        return "";
    }

    private static Map<String, Integer> ranks(List<String> ids) {
        var result = new HashMap<String, Integer>();
        for (int i = 0; i < ids.size(); i++) result.put(ids.get(i), i);
        return result;
    }

    private static String inversion(
            String before,
            String after,
            String stage,
            Map<String, Integer> current,
            Map<String, Integer> previous) {
        if (!current.containsKey(before) || !current.containsKey(after)) return "";
        boolean invalid = current.get(before) > current.get(after);
        boolean already =
                previous.containsKey(before)
                        && previous.containsKey(after)
                        && previous.get(before) > previous.get(after);
        return invalid && !already
                ? "npships.patch_manager.required_order|" + before + "|" + after + "|" + stage
                : "";
    }

    static JsonArray array(Iterable<String> strings) {
        var array = new JsonArray();
        for (String text : strings) array.add(text);
        return array;
    }

    static List<String> strings(JsonArray array) {
        var result = new ArrayList<String>();
        for (var value : array) result.add(value.getAsString());
        return List.copyOf(result);
    }
}
