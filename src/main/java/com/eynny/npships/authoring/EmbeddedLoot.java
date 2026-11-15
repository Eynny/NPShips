package com.eynny.npships.authoring;

import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.ValidationContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.*;

/**
 * Ship-scoped table definitions; never insert mutable entries into the world's frozen registries.
 */
public final class EmbeddedLoot {
    public static final String KEY = "loot_tables";

    private EmbeddedLoot() {}

    public static JsonObject read(JsonObject config) {
        if (config == null || !config.has(KEY)) return new JsonObject();
        if (!config.get(KEY).isJsonObject())
            throw new IllegalArgumentException("loot_tables must be an object.");
        var tables = config.getAsJsonObject(KEY);
        if (tables.size() > 128)
            throw new IllegalArgumentException("Maximum 128 local loot tables.");
        for (var entry : tables.entrySet()) {
            var id = ResourceLocation.parse(entry.getKey());
            if (!id.getNamespace().equals("npships") || !id.getPath().startsWith("local/"))
                throw new IllegalArgumentException(
                        "Local loot IDs must start with npships:local/.");
            if (!entry.getValue().isJsonObject())
                throw new IllegalArgumentException("Loot table must contain standard JSON.");
        }
        return JsonStructure.copy(tables);
    }

    public static Map<ResourceLocation, LootTable> compile(JsonObject tables, ServerLevel level) {
        JsonStructure.validate(tables);
        var lookup = level.getServer().reloadableRegistries().lookup();
        var result = new LinkedHashMap<ResourceLocation, LootTable>();
        for (var entry : tables.entrySet()) {
            var table =
                    LootTable.DIRECT_CODEC
                            .parse(
                                    level.registryAccess()
                                            .createSerializationContext(JsonOps.INSTANCE),
                                    entry.getValue())
                            .getOrThrow(IllegalArgumentException::new);
            if (!LootContextParamSets.CHEST
                    .getAllowed()
                    .containsAll(table.getParamSet().getRequired()))
                throw new IllegalArgumentException(
                        "Loot table requires parameters unavailable to a ship container: "
                                + entry.getKey());
            // World/datapack references are resolved by vanilla; local-to-local references are
            // deliberately explicit errors.
            var problems = new net.minecraft.util.ProblemReporter.Collector();
            table.validate(new ValidationContext(problems, LootContextParamSets.CHEST, lookup));
            problems.getReport()
                    .ifPresent(
                            report -> {
                                throw new IllegalArgumentException(entry.getKey() + ": " + report);
                            });
            result.put(ResourceLocation.parse(entry.getKey()), table);
        }
        return Map.copyOf(result);
    }

    /** Explicit editor replacement keeps existing container links when a local table is renamed. */
    public static JsonObject replace(
            JsonObject config, String original, String id, JsonObject table) {
        java.util.Objects.requireNonNull(table, "Replacement loot table");
        var tables = read(config);
        if (!original.isEmpty() && !tables.has(original))
            throw new IllegalArgumentException("The edited local loot table no longer exists.");
        if (!original.equals(id) && tables.has(id))
            throw new IllegalArgumentException("This local loot table name is already used: " + id);
        LootAssignments.read(config);
        if (!original.isEmpty() && !original.equals(id)) tables.remove(original);
        JsonStructure.validate(table);
        tables.add(id, table.deepCopy());
        var next = config.deepCopy();
        next.add(KEY, tables);
        read(next);
        return !original.isEmpty() && !original.equals(id)
                ? remapAssignments(next, original, id)
                : next;
    }

    /** Delete one local definition and its known assignments; preserve raw container contents. */
    public static JsonObject remove(JsonObject config, String id) {
        if (!read(config).has(id))
            throw new IllegalArgumentException("The local loot table no longer exists.");
        LootAssignments.read(config);
        return remapAssignments(assign(config, id, null), id, null);
    }

    private static JsonObject remapAssignments(JsonObject config, String from, String to) {
        remapContainerList(config, from, to);
        if (config.has("segments"))
            for (var value : config.getAsJsonArray("segments")) {
                var segment = value.getAsJsonObject();
                if (segment.has("pool"))
                    for (var variant : segment.getAsJsonArray("pool"))
                        remapContainerList(variant.getAsJsonObject(), from, to);
            }
        return config;
    }

    private static void remapContainerList(JsonObject config, String from, String to) {
        LootAssignments.read(config);
        var entries = new JsonArray();
        if (config.has(LootAssignments.KEY))
            for (var value : config.getAsJsonArray(LootAssignments.KEY)) {
                var entry = value.getAsJsonObject().deepCopy();
                if (entry.get("table").getAsString().equals(from)) {
                    if (to == null) continue;
                    entry.addProperty("table", to);
                }
                entries.add(entry);
            }
        if (entries.isEmpty()) config.remove(LootAssignments.KEY);
        else config.add(LootAssignments.KEY, entries);
    }

    public static JsonObject assign(JsonObject config, String id, JsonObject table) {
        var next = config.deepCopy();
        var tables = read(config);
        if (table == null) tables.remove(id);
        else tables.add(id, table.deepCopy());
        if (tables.isEmpty()) next.remove(KEY);
        else next.add(KEY, tables);
        read(next);
        return next;
    }
}
