package com.eynny.npships.authoring;

import com.google.gson.JsonObject;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detached per-choice equipment. A tag entry shares its slot pools across its resolved mob types.
 */
public final class CrewEquipment {
    public static final List<String> SLOTS =
            List.of("mainhand", "offhand", "feet", "legs", "chest", "head");

    private static final CrewEquipment EMPTY = new CrewEquipment(Map.of());

    private record Entry(int weight, CompoundTag item) {}

    private record Pool(List<Entry> entries, int totalWeight) {}

    private final Map<String, Pool> slots;

    private CrewEquipment(Map<String, Pool> slots) {
        this.slots = Map.copyOf(slots);
    }

    public static CrewEquipment empty() {
        return EMPTY;
    }

    public static CrewEquipment read(JsonObject choice) {
        if (!choice.has("equipment")) return empty();
        var slots = new LinkedHashMap<String, Pool>();
        for (var slot : choice.getAsJsonObject("equipment").entrySet()) {
            if (!SLOTS.contains(slot.getKey()))
                throw new IllegalArgumentException(
                        "Unsupported crew equipment slot: " + slot.getKey());
            var entries = new ArrayList<Entry>();
            int totalWeight = 0;
            for (var value : slot.getValue().getAsJsonArray()) {
                var entry = value.getAsJsonObject();
                int weight =
                        entry.has("weight")
                                ? com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                        entry.get("weight"), "weight")
                                : 1;
                if (weight < 1)
                    throw new IllegalArgumentException(
                            "Equipment weight must be a positive integer");
                try {
                    totalWeight = Math.addExact(totalWeight, weight);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(
                            "Equipment total weight exceeds native RandomSource.nextInt range",
                            overflow);
                }
                boolean empty = entry.has("empty") && entry.get("empty").getAsBoolean();
                if (empty == entry.has("nbt"))
                    throw new IllegalArgumentException(
                            "Equipment choice needs either empty:true or native item SNBT");
                CompoundTag item;
                try {
                    item =
                            empty
                                    ? new CompoundTag()
                                    : com.eynny.npships.authoring.document.SnbtText.parse(
                                            entry.get("nbt").getAsString());
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
                    throw new IllegalArgumentException(
                            "Invalid crew equipment SNBT: " + error.getMessage(), error);
                }
                if (!empty && (item.getString("id").isBlank() || item.getInt("count") < 1))
                    throw new IllegalArgumentException(
                            "Equipment SNBT needs registered item id and positive count; use empty:true for an empty outcome");
                entries.add(new Entry(weight, item));
            }
            if (entries.isEmpty())
                throw new IllegalArgumentException(
                        "Crew equipment slot needs at least one weighted choice");
            slots.put(slot.getKey(), new Pool(List.copyOf(entries), totalWeight));
        }
        return new CrewEquipment(slots);
    }

    /** Native item/component validation happens before any entity factory or published output. */
    public void validate(HolderLookup.Provider registries) {
        for (var entries : slots.values())
            for (var entry : entries.entries())
                if (!entry.item().isEmpty()) {
                    var stack =
                            com.eynny.npships.authoring.document.ItemSettings.parse(
                                    entry.item(), registries);
                    if (stack.isEmpty())
                        throw new IllegalArgumentException(
                                "Use empty:true for empty crew equipment");
                }
    }

    CompoundTag apply(CompoundTag state, RandomSource random, CrewResourceBudget resources) {
        // Fixed native slot order makes JSON member order immaterial. Old data draws no extra RNG.
        if (slots.isEmpty()) return null;
        var selectedSlots = new CompoundTag();
        for (var slot : SLOTS) {
            var choices = slots.get(slot);
            if (choices == null) continue;
            resources.charge(CrewResourceBudget.Family.EQUIPMENT_DRAW);
            int roll = random.nextInt(choices.totalWeight());
            Entry selected = choices.entries().getFirst();
            for (var entry : choices.entries()) {
                roll -= entry.weight();
                if (roll < 0) {
                    selected = entry;
                    break;
                }
            }
            boolean hand = slot.endsWith("hand");
            String key = hand ? "HandItems" : "ArmorItems";
            int at = hand ? (slot.equals("mainhand") ? 0 : 1) : SLOTS.indexOf(slot) - 2;
            ListTag items = state.getList(key, Tag.TAG_COMPOUND).copy();
            while (items.size() < (hand ? 2 : 4)) items.add(new CompoundTag());
            items.set(at, selected.item().copy());
            state.put(key, items);
            selectedSlots.put(slot, selected.item().copy());
        }
        return selectedSlots;
    }
}
