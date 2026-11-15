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

    public static Read beginRead(JsonObject choice) {
        return new Read(choice);
    }

    public static CrewEquipment read(JsonObject choice) {
        var read = beginRead(choice);
        while (!read.step()) {}
        return read.result();
    }

    /**
     * Parses one weighted outcome per step; each pool becomes immutable without another full copy.
     */
    public static final class Read implements AutoCloseable {
        private final java.util.Iterator<Map.Entry<String, com.google.gson.JsonElement>> source;
        private final Map<String, Pool> result = new LinkedHashMap<>();
        private com.google.gson.JsonArray values;
        private ArrayList<Entry> entries;
        private String slot;
        private int index, totalWeight;
        private CrewEquipment completed;
        private boolean closed;
        private long progress;

        private Read(JsonObject choice) {
            source =
                    choice.has("equipment")
                            ? choice.getAsJsonObject("equipment").entrySet().iterator()
                            : java.util.Collections.emptyIterator();
        }

        public boolean step(java.util.function.BooleanSupplier canContinue) {
            while (completed == null && canContinue.getAsBoolean()) if (step()) return true;
            return completed != null;
        }

        public long progress() {
            return progress;
        }

        public void close() {
            closed = true;
            values = null;
            entries = null;
        }

        public boolean step() {
            if (closed) throw new IllegalStateException("Crew equipment reader is closed");
            if (completed != null) return true;
            progress++;
            if (values == null) {
                if (!source.hasNext()) {
                    completed = result.isEmpty() ? EMPTY : new CrewEquipment(result);
                    return true;
                }
                var next = source.next();
                slot = next.getKey();
                if (!SLOTS.contains(slot))
                    throw new IllegalArgumentException("Unsupported crew equipment slot: " + slot);
                values = next.getValue().getAsJsonArray();
                entries = new ArrayList<>();
                index = 0;
                totalWeight = 0;
                return false;
            }
            if (index == values.size()) {
                if (entries.isEmpty())
                    throw new IllegalArgumentException(
                            "Crew equipment slot needs at least one weighted choice");
                result.put(
                        slot,
                        new Pool(java.util.Collections.unmodifiableList(entries), totalWeight));
                values = null;
                entries = null;
                return false;
            }
            var entry = values.get(index++).getAsJsonObject();
            int weight =
                    entry.has("weight")
                            ? com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                    entry.get("weight"), "weight")
                            : 1;
            if (weight < 1)
                throw new IllegalArgumentException("Equipment weight must be a positive integer");
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
            return false;
        }

        public CrewEquipment result() {
            if (completed == null)
                throw new IllegalStateException("Crew equipment read is incomplete");
            return completed;
        }
    }

    /** Native item/component validation happens before any entity factory or published output. */
    public Validation beginValidation(HolderLookup.Provider registries) {
        return new Validation(registries);
    }

    public void validate(HolderLookup.Provider registries) {
        var validation = beginValidation(registries);
        while (!validation.step()) {}
    }

    public final class Validation implements AutoCloseable {
        private final HolderLookup.Provider registries;
        private final java.util.Iterator<Pool> pools = slots.values().iterator();
        private java.util.Iterator<Entry> entries = java.util.Collections.emptyIterator();
        private boolean done, closed;
        private long progress;

        private Validation(HolderLookup.Provider registries) {
            this.registries = registries;
        }

        public boolean step(java.util.function.BooleanSupplier canContinue) {
            while (!done && canContinue.getAsBoolean()) if (step()) return true;
            return done;
        }

        public long progress() {
            return progress;
        }

        public void close() {
            closed = true;
        }

        public boolean step() {
            if (closed) throw new IllegalStateException("Crew equipment validation is closed");
            if (done) return true;
            progress++;
            if (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.item().isEmpty()) {
                    var stack =
                            com.eynny.npships.authoring.document.ItemSettings.parse(
                                    entry.item(), registries);
                    if (stack.isEmpty())
                        throw new IllegalArgumentException(
                                "Use empty:true for empty crew equipment");
                }
                return false;
            }
            if (!pools.hasNext()) {
                done = true;
                return true;
            }
            entries = pools.next().entries().iterator();
            return false;
        }
    }

    CompoundTag apply(CompoundTag state, RandomSource random, CrewResourceBudget resources) {
        try (var application = beginApply(state, random, resources, ignored -> {})) {
            while (!application.step(() -> true)) {}
            return application.result();
        }
    }

    Application beginApply(
            CompoundTag state,
            RandomSource random,
            CrewResourceBudget resources,
            java.util.function.LongConsumer reserve) {
        return new Application(state, random, resources, reserve);
    }

    final class Application implements AutoCloseable {
        private final CompoundTag state, selectedSlots = new CompoundTag();
        private final RandomSource random;
        private final CrewResourceBudget resources;
        private final java.util.function.LongConsumer reserve;
        private int slotIndex, entryIndex, roll, phase, at;
        private String slot, key;
        private Pool choices;
        private Entry selected;
        private ListTag items;
        private com.eynny.npships.resources.NbtWorkCursor copy;
        private boolean done, closed;
        private long progress;

        long progress() {
            return progress;
        }

        private Application(
                CompoundTag state,
                RandomSource random,
                CrewResourceBudget resources,
                java.util.function.LongConsumer reserve) {
            this.state = state;
            this.random = random;
            this.resources = resources;
            this.reserve = reserve;
        }

        boolean step(java.util.function.BooleanSupplier canContinue) {
            if (closed) throw new IllegalStateException("Crew equipment application is closed");
            while (!done) {
                if (copy != null) {
                    long before = copy.progress();
                    boolean complete = copy.step(canContinue);
                    progress += copy.progress() - before;
                    if (!complete) return false;
                    var value = copy.copyResult();
                    copy.close();
                    copy = null;
                    if (phase == 2) {
                        items = (ListTag) value;
                        while (items.size() < (slot.endsWith("hand") ? 2 : 4))
                            items.add(new CompoundTag());
                        copy =
                                com.eynny.npships.resources.NbtWorkCursor.copy(
                                        selected.item(), reserve);
                        phase = 3;
                    } else if (phase == 3) {
                        items.set(at, value);
                        state.put(key, items);
                        copy =
                                com.eynny.npships.resources.NbtWorkCursor.copy(
                                        selected.item(), reserve);
                        phase = 4;
                    } else if (phase == 4) {
                        selectedSlots.put(slot, value);
                        phase = 0;
                    } else throw new IllegalStateException("Unknown equipment copy continuation");
                    continue;
                }
                if (!canContinue.getAsBoolean()) return false;
                progress++;
                switch (phase) {
                    case 0 -> {
                        if (slotIndex == SLOTS.size()) {
                            done = true;
                            return true;
                        }
                        slot = SLOTS.get(slotIndex++);
                        choices = slots.get(slot);
                        if (choices == null) continue;
                        resources.charge(CrewResourceBudget.Family.EQUIPMENT_DRAW);
                        roll = random.nextInt(choices.totalWeight());
                        entryIndex = 0;
                        phase = 1;
                    }
                    case 1 -> {
                        selected = choices.entries().get(entryIndex++);
                        roll -= selected.weight();
                        if (roll < 0) {
                            boolean hand = slot.endsWith("hand");
                            key = hand ? "HandItems" : "ArmorItems";
                            at = hand ? (slot.equals("mainhand") ? 0 : 1) : SLOTS.indexOf(slot) - 2;
                            copy =
                                    com.eynny.npships.resources.NbtWorkCursor.copy(
                                            state.getList(key, Tag.TAG_COMPOUND), reserve);
                            phase = 2;
                        }
                    }
                    default ->
                            throw new IllegalStateException("Unknown equipment application phase");
                }
            }
            return true;
        }

        CompoundTag result() {
            if (closed || !done)
                throw new IllegalStateException("Crew equipment application is incomplete");
            return slots.isEmpty() ? null : selectedSlots;
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (copy != null) copy.close();
        }
    }
}
