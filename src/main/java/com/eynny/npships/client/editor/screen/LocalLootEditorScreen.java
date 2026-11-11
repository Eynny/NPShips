package com.eynny.npships.client.editor.screen;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Consumer;

public final class LocalLootEditorScreen extends JsonCollectionScreen {
    public LocalLootEditorScreen(Screen parent, JsonObject config, Consumer<JsonObject> accept) {
        super(parent, "npships.loot.local", config, accept);
    }

    @Override
    protected com.eynny.npships.authoring.document.DataSuggestions suggestions(JsonObject value) {
        return com.eynny.npships.authoring.document.LootDataSuggestions.create(
                value, minecraft.level.registryAccess());
    }

    @Override
    protected List<String> names() {
        return EmbeddedLoot.read(config).keySet().stream().sorted().toList();
    }

    @Override
    protected JsonObject value(String name) {
        return EmbeddedLoot.read(config).getAsJsonObject(name);
    }

    @Override
    protected void put(String name, JsonObject value) {
        config = EmbeddedLoot.assign(config, name, value);
    }

    @Override
    protected void validate() {
        EmbeddedLoot.read(config);
        var lookup = minecraft.level.registryAccess();
        for (var entry : EmbeddedLoot.read(config).entrySet())
            net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                    .parse(lookup.createSerializationContext(JsonOps.INSTANCE), entry.getValue())
                    .getOrThrow();
        for (var assignment : LootAssignments.read(config)) check(assignment.table());
        for (var segment : StructureSegments.read(config))
            for (var variant : segment.pool())
                for (var assignment : variant.loot()) check(assignment.table());
    }

    private void check(ResourceLocation id) {
        if (id.getNamespace().equals("npships")
                && id.getPath().startsWith("local/")
                && !EmbeddedLoot.read(config).has(id.toString()))
            throw invalid("loot_assigned", "Table is still assigned to a container: " + id, id);
    }

    @Override
    protected void create() {
        if (!commit()) return;
        int n = 1;
        while (names().contains("npships:local/loot_" + n)) n++;
        String id = "npships:local/loot_" + n;
        put(
                id,
                JsonParser.parseString(
                                "{\"type\":\"minecraft:chest\",\"pools\":[{\"rolls\":1,\"entries\":[]}]}")
                        .getAsJsonObject());
        select(id);
    }

    private String rolls = "1", items = "";

    @Override
    protected boolean supportsForm(JsonObject table) {
        try {
            var pool = table.getAsJsonArray("pools").get(0).getAsJsonObject();
            if (!formInteger(pool.get("rolls"), 0, 256)) return false;
            for (var value : pool.getAsJsonArray("entries")) {
                var entry = value.getAsJsonObject();
                if (!entry.get("type").getAsString().equals("minecraft:item")) return false;
                if (entry.keySet().stream()
                        .anyMatch(k -> !Set.of("type", "name", "weight", "functions").contains(k)))
                    return false;
                if (entry.has("weight") && !formInteger(entry.get("weight"), 1, 1000000))
                    return false;
                if (entry.has("functions"))
                    for (var fn : entry.getAsJsonArray("functions")) {
                        var f = fn.getAsJsonObject();
                        if (!f.get("function").getAsString().equals("minecraft:set_count")
                                || !formInteger(f.get("count"), 1, 64)
                                || f.size() != 2) return false;
                    }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Native loot accepts number providers and values outside this convenience form.
    // Keep those definitions in JSON instead of opening a form that cannot save them.
    private static boolean formInteger(JsonElement value, int min, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            return false;
        try {
            int number = value.getAsBigDecimal().intValueExact();
            return number >= min && number <= max;
        } catch (ArithmeticException | NumberFormatException error) {
            return false;
        }
    }

    @Override
    protected void prepareForm(JsonObject table) {
        if (!supportsForm(table)) return;
        var pool = table.getAsJsonArray("pools").get(0).getAsJsonObject();
        rolls = Integer.toString(pool.get("rolls").getAsInt());
        var lines = new ArrayList<String>();
        for (var value : pool.getAsJsonArray("entries")) {
            var e = value.getAsJsonObject();
            int count = 1;
            if (e.has("functions"))
                for (var fn : e.getAsJsonArray("functions"))
                    count = fn.getAsJsonObject().get("count").getAsInt();
            lines.add(
                    e.get("name").getAsString()
                            + " "
                            + count
                            + " "
                            + (e.has("weight") ? e.get("weight").getAsInt() : 1));
        }
        items = String.join("\n", lines);
    }

    @Override
    protected void buildForm() {
        field("npships.loot.rolls", 50, rolls, v -> rolls = v);
        multiline("npships.loot.entries", 90, items, v -> items = v);
    }

    @Override
    protected JsonObject formJson() {
        int roll = Integer.parseInt(rolls.trim());
        if (roll < 0 || roll > 256) throw invalid("loot_rolls", "Rolls: 0..256.", roll);
        var table = value(selected).deepCopy();
        var pool = table.getAsJsonArray("pools").get(0).getAsJsonObject();
        pool.addProperty("rolls", roll);
        var entries = new JsonArray();
        for (String line : items.split("\\R")) {
            if (line.isBlank()) continue;
            var v = line.trim().split("\\s+");
            if (v.length != 3)
                throw invalid("loot_entry", "Each line: item_id count weight.", line);
            var id = ResourceLocation.parse(v[0]);
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id))
                throw invalid("unknown_item", "Unknown item: " + id, id);
            int count = Integer.parseInt(v[1]), weight = Integer.parseInt(v[2]);
            if (count < 1 || count > 64 || weight < 1 || weight > 1000000)
                throw invalid("loot_standalone", "Count 1..64, weight 1..1000000.", count, weight);
            var entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", id.toString());
            entry.addProperty("weight", weight);
            var functions = new JsonArray();
            var f = new JsonObject();
            f.addProperty("function", "minecraft:set_count");
            f.addProperty("count", count);
            functions.add(f);
            entry.add("functions", functions);
            entries.add(entry);
        }
        pool.add("entries", entries);
        return table;
    }

    @Override
    protected void extras() {
        if (selected.isEmpty()) return;
        button(
                "npships.loot.add_item",
                panelX,
                height - 40,
                panelW,
                () -> {
                    if (!commit()) return;
                    var table = value(selected).deepCopy();
                    var pools = table.getAsJsonArray("pools");
                    if (pools.isEmpty()) throw invalid("loot_pool", "Create a pool in JSON first.");
                    var entry = new JsonObject();
                    entry.addProperty("type", "minecraft:item");
                    entry.addProperty("name", "minecraft:iron_ingot");
                    entry.addProperty("weight", 1);
                    pools.get(0).getAsJsonObject().getAsJsonArray("entries").add(entry);
                    put(selected, table);
                    select(selected);
                });
    }
}
