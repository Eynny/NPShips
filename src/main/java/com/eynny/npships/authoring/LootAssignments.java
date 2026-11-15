package com.eynny.npships.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Relative container positions; absence of an assignment preserves the original schematic. */
public final class LootAssignments {
    public static final String KEY = "loot_containers";

    public record Entry(BlockPos pos, ResourceLocation table, int rolls) {
        public Entry(BlockPos pos, ResourceLocation table) {
            this(pos, table, 1);
        }

        public Entry {
            if (rolls < 1 || rolls > 256)
                throw new IllegalArgumentException("Loot rolls must be 1..256.");
        }
    }

    private LootAssignments() {}

    public static List<Entry> read(final JsonObject json) {
        if (json == null || !json.has(KEY)) return List.of();
        if (!json.get(KEY).isJsonArray())
            throw new IllegalArgumentException(KEY + " must be an array.");
        var result = new ArrayList<Entry>();
        var positions = new HashSet<BlockPos>();
        for (var element : json.getAsJsonArray(KEY)) {
            if (!element.isJsonObject())
                throw new IllegalArgumentException("Invalid loot container entry.");
            var entry = element.getAsJsonObject();
            if (!entry.has("pos")
                    || !entry.get("pos").isJsonArray()
                    || entry.getAsJsonArray("pos").size() != 3)
                throw new IllegalArgumentException(
                        "Loot container pos must contain three integers.");
            int[] xyz = new int[3];
            for (int i = 0; i < 3; i++) {
                var value = entry.getAsJsonArray("pos").get(i);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException(
                            "Loot container coordinates must be integers.");
                try {
                    xyz[i] = value.getAsBigDecimal().intValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException(
                            "Loot container coordinates must be integers.", e);
                }
                if (xyz[i] < 0)
                    throw new IllegalArgumentException(
                            "Loot container coordinates cannot be negative.");
            }
            var pos = new BlockPos(xyz[0], xyz[1], xyz[2]);
            if (!positions.add(pos))
                throw new IllegalArgumentException("Duplicate loot container at " + pos);
            if (!entry.has("table")
                    || !entry.get("table").isJsonPrimitive()
                    || !entry.getAsJsonPrimitive("table").isString())
                throw new IllegalArgumentException("Loot container table must be a resource ID.");
            var table = ResourceLocation.tryParse(entry.get("table").getAsString());
            if (table == null)
                throw new IllegalArgumentException("Invalid loot table ID: " + entry.get("table"));
            int rolls = 1;
            if (entry.has("rolls"))
                try {
                    rolls =
                            com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                    entry.get("rolls"), "rolls");
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Loot rolls must be an integer.", e);
                }
            result.add(new Entry(pos, table, rolls));
        }
        return List.copyOf(result);
    }

    public static JsonObject assign(
            JsonObject source, BlockPos pos, ResourceLocation table, int rolls) {
        new Entry(pos, table, rolls);
        var result = assign(source, pos, table);
        if (table != null)
            for (var value : result.getAsJsonArray(KEY)) {
                var entry = value.getAsJsonObject();
                var xyz = entry.getAsJsonArray("pos");
                if (xyz.get(0).getAsInt() == pos.getX()
                        && xyz.get(1).getAsInt() == pos.getY()
                        && xyz.get(2).getAsInt() == pos.getZ()) {
                    if (rolls == 1) entry.remove("rolls");
                    else entry.addProperty("rolls", rolls);
                }
            }
        return result;
    }

    public static JsonObject assign(
            final JsonObject source, final BlockPos pos, final ResourceLocation table) {
        read(source); // Do not silently erase malformed assignments.
        var result = source.deepCopy();
        var entries = new JsonArray();
        JsonObject replacement = null;
        if (source.has(KEY))
            for (var element : source.getAsJsonArray(KEY)) {
                var xyz = element.getAsJsonObject().getAsJsonArray("pos");
                if (xyz.get(0).getAsInt() == pos.getX()
                        && xyz.get(1).getAsInt() == pos.getY()
                        && xyz.get(2).getAsInt() == pos.getZ())
                    replacement = element.getAsJsonObject().deepCopy();
                else entries.add(element.deepCopy());
            }
        if (table != null) {
            if (replacement == null) replacement = new JsonObject();
            var xyz = new JsonArray();
            xyz.add(pos.getX());
            xyz.add(pos.getY());
            xyz.add(pos.getZ());
            replacement.add("pos", xyz);
            replacement.addProperty("table", table.toString());
            entries.add(replacement);
        }
        if (entries.isEmpty()) result.remove(KEY);
        else result.add(KEY, entries);
        return result;
    }
}
