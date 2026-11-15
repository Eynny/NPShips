package com.eynny.npships.authoring;

import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** The existing rule grammar, yielding between rules, pool entries and equipment entries. */
final class CrewRuleRead implements AutoCloseable {
    private final LongConsumer reserve;
    private JsonArray source;
    private Set<String> names = new HashSet<>();
    private List<StructureCrew.Rule> rules = new ArrayList<>();
    private List<StructureCrew.Choice> choices;
    private JsonObject rule;
    private JsonArray pool;
    private CrewEquipment.Read equipmentRead;
    private CrewEquipment selectedEquipment;
    private CompoundTag choiceState;
    private String id, name, placement, type;
    private BlockPos origin, size;
    private int index, poolIndex, count, minCount, weight, phase;
    private boolean ranged, preventDespawn, closed, failed;
    private long progress;

    CrewRuleRead(JsonObject config, LongConsumer reserve) {
        source =
                config == null || !config.has("crew")
                        ? new JsonArray()
                        : config.getAsJsonArray("crew");
        this.reserve = Objects.requireNonNull(reserve);
    }

    boolean step(BooleanSupplier allowed) {
        requireOpen();
        try {
            while (phase != 4) {
                if (equipmentRead != null) {
                    long before = equipmentRead.progress();
                    boolean done = equipmentRead.step(allowed);
                    progress += equipmentRead.progress() - before;
                    if (!done) return false;
                    selectedEquipment = equipmentRead.result();
                    equipmentRead.close();
                    equipmentRead = null;
                    phase = 3;
                } else {
                    if (!allowed.getAsBoolean()) return false;
                    one();
                    progress++;
                }
            }
            return true;
        } catch (RuntimeException | Error error) {
            failed = true;
            clear();
            throw error;
        }
    }

    private void one() {
        if (phase == 0) {
            if (index == source.size()) {
                phase = 4;
                return;
            }
            rule = source.get(index).getAsJsonObject();
            id = rule.get("id").getAsString();
            if (id.isBlank() || id.length() > 80 || !names.add(id))
                throw new IllegalArgumentException(
                        "Crew rule needs a unique name (1..80 characters).");
            int fallback = StructureCrew.integer(rule, "count", 1, 0, Integer.MAX_VALUE);
            minCount = StructureCrew.integer(rule, "min_count", 0, 0, Integer.MAX_VALUE);
            count = StructureCrew.integer(rule, "max_count", fallback, 0, Integer.MAX_VALUE);
            if (minCount > count)
                throw new IllegalArgumentException("min_count must not exceed max_count");
            name = rule.has("name") ? rule.get("name").getAsString() : "";
            if (name.length() > 256) throw new IllegalArgumentException("Mob name is too long");
            origin = StructureCrew.vector(rule, "origin", 0);
            size = StructureCrew.vector(rule, "size", 1);
            placement = rule.has("placement") ? rule.get("placement").getAsString() : "ground";
            if (!Set.of("ground", "air", "water").contains(placement))
                throw new IllegalArgumentException("Crew placement: ground / air / water.");
            if (rule.has("prevent_despawn")
                    && (!rule.get("prevent_despawn").isJsonPrimitive()
                            || !rule.getAsJsonPrimitive("prevent_despawn").isBoolean()))
                throw new IllegalArgumentException("prevent_despawn must be true or false");
            ranged = rule.has("min_count") || rule.has("max_count");
            preventDespawn =
                    !rule.has("prevent_despawn") || rule.get("prevent_despawn").getAsBoolean();
            reserve.accept(24L * Long.BYTES);
            choices = new ArrayList<>();
            pool = rule.getAsJsonArray("pool");
            poolIndex = 0;
            phase = 1;
        } else if (phase == 1) {
            if (poolIndex == pool.size()) {
                phase = 2;
                return;
            }
            var choice = pool.get(poolIndex).getAsJsonObject();
            type = choice.get("type").getAsString();
            ResourceLocation.parse(type.startsWith("#") ? type.substring(1) : type);
            weight = StructureCrew.integer(choice, "weight", 1, 1, 1_000_000);
            choiceState = StructureCrew.readState(choice);
            equipmentRead = CrewEquipment.beginRead(choice);
        } else if (phase == 2) {
            if (choices.isEmpty() || choices.size() > 64)
                throw new IllegalArgumentException("Crew pool needs 1..64 types/tags.");
            var state = StructureCrew.readState(rule);
            rules.add(
                    new StructureCrew.Rule(
                            id,
                            origin,
                            size,
                            count,
                            minCount,
                            ranged,
                            name,
                            placement,
                            Collections.unmodifiableList(choices),
                            state,
                            preventDespawn));
            choices = null;
            rule = null;
            pool = null;
            index++;
            phase = 0;
        } else {
            reserve.accept(8L * Long.BYTES);
            choices.add(new StructureCrew.Choice(type, weight, choiceState, selectedEquipment));
            choiceState = null;
            selectedEquipment = null;
            type = null;
            poolIndex++;
            phase = 1;
        }
    }

    List<StructureCrew.Rule> result() {
        requireOpen();
        if (phase != 4) throw new IllegalStateException("Crew rule read unfinished");
        return Collections.unmodifiableList(rules);
    }

    long progress() {
        return progress;
    }

    private void requireOpen() {
        if (closed || failed) throw new IllegalStateException("Crew rule read closed or failed");
    }

    private void clear() {
        if (equipmentRead != null) equipmentRead.close();
        equipmentRead = null;
        source = null;
        names = null;
        rule = null;
        pool = null;
        choices = null;
        rules = null;
        choiceState = null;
        selectedEquipment = null;
    }

    @Override
    public void close() {
        closed = true;
        clear();
    }
}
