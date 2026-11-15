package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.CrewEquipment;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.client.editor.widget.EditorPopupMenu;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Session selectors only; equipment drafts join the zone's existing atomic acceptance. */
final class CrewEquipmentControls {
    private final EditorToolsPanel panel;
    private String zone = "", slot = CrewEquipment.SLOTS.getFirst();
    private int type, outcome;
    private boolean advanced;

    CrewEquipmentControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    Runnable build(List<JsonObject> pool) {
        if (!zone.equals(panel.zone)) {
            zone = panel.zone;
            type = outcome = 0;
        }
        type = Math.max(0, Math.min(type, pool.size() - 1));
        panel.sections.begin("equipment", "crew_equipment", false);
        if (pool.isEmpty()) {
            panel.sections.end();
            return () -> {};
        }
        var entry = pool.get(type);
        var types = new ArrayList<EditorPopupMenu.Entry>();
        for (int i = 0; i < pool.size(); i++) {
            int at = i;
            types.add(
                    new EditorPopupMenu.Entry(
                            Component.literal(
                                    (i + 1) + ": " + pool.get(i).get("type").getAsString()),
                            () -> type == at,
                            () -> {
                                type = at;
                                outcome = 0;
                                panel.build();
                            }));
        }
        menu(Component.literal((type + 1) + ": " + entry.get("type").getAsString()), types);
        var slots = new ArrayList<EditorPopupMenu.Entry>();
        for (String target : CrewEquipment.SLOTS)
            slots.add(
                    new EditorPopupMenu.Entry(
                            Component.translatable("npships.crew.slot." + target),
                            () -> slot.equals(target),
                            () -> {
                                slot = target;
                                outcome = 0;
                                panel.build();
                            }));
        menu(Component.translatable("npships.crew.slot." + slot), slots);
        var equipment =
                entry.has("equipment") ? entry.getAsJsonObject("equipment") : new JsonObject();
        var choices = equipment.has(slot) ? equipment.getAsJsonArray(slot) : new JsonArray();
        outcome = Math.max(0, Math.min(outcome, choices.size() - 1));
        button(Component.translatable("npships.crew.equipment_add"), () -> add(false));
        button(Component.translatable("npships.crew.equipment_empty"), () -> add(true));
        Runnable flush = () -> {};
        if (choices.isEmpty()) {
            panel.widgets.add(
                    new net.minecraft.client.gui.components.StringWidget(
                                    panel.x,
                                    panel.cursor,
                                    panel.contentWidth(),
                                    12,
                                    Component.translatable("npships.crew.equipment_inherit"),
                                    panel.mc.font)
                            .alignLeft());
            panel.cursor += 16;
        } else {
            var alternatives = new ArrayList<EditorPopupMenu.Entry>();
            for (int i = 0; i < choices.size(); i++) {
                int at = i;
                alternatives.add(
                        new EditorPopupMenu.Entry(
                                Component.literal((i + 1) + " / " + choices.size()),
                                () -> outcome == at,
                                () -> {
                                    outcome = at;
                                    panel.build();
                                }));
            }
            menu(Component.literal((outcome + 1) + " / " + choices.size()), alternatives);
            var original = choices.get(outcome).getAsJsonObject();
            boolean empty = original.has("empty") && original.get("empty").getAsBoolean();
            String[] weight = {original.has("weight") ? original.get("weight").getAsString() : "1"};
            var weightField = panel.field("mob_weight", weight[0], v -> weight[0] = v);
            String[] nbt = {original.has("nbt") ? original.get("nbt").getAsString() : "{}"};
            String[] item = {""}, count = {"1"};
            try {
                var tag = com.eynny.npships.authoring.document.SnbtText.parse(nbt[0]);
                item[0] = tag.getString("id");
                count[0] = "" + tag.getInt("count");
            } catch (Exception malformed) {
                advanced = true;
            }
            AbstractWidget[] input = {weightField};
            if (!empty) {
                button(
                        advanced
                                ? Component.translatable("npships.authoring.simple")
                                : Component.literal("SNBT"),
                        () -> {
                            advanced = !advanced;
                            panel.build();
                        });
                if (advanced) {
                    panel.dataEditor(
                            nbt[0],
                            previous -> {
                                net.minecraft.nbt.CompoundTag source;
                                try {
                                    source =
                                            com.eynny.npships.authoring.document.SnbtText.parse(
                                                    nbt[0]);
                                } catch (
                                        com.mojang.brigadier.exceptions.CommandSyntaxException
                                                incomplete) {
                                    source = new net.minecraft.nbt.CompoundTag();
                                }
                                return com.eynny.npships.authoring.document.ItemSettings
                                        .suggestions(
                                                source, panel.mc.level.registryAccess(), previous);
                            },
                            v -> nbt[0] = v,
                            v -> {
                                com.eynny.npships.authoring.document.ItemSettings.parse(
                                        v, panel.mc.level.registryAccess());
                            });
                    input[0] = panel.codeEditor;
                } else {
                    input[0] =
                            panel.field(
                                    "item",
                                    item[0],
                                    v -> {
                                        item[0] = v;
                                        panel.settingsDirty = true;
                                    });
                    panel.field(
                            "count",
                            count[0],
                            v -> {
                                count[0] = v;
                                panel.settingsDirty = true;
                            });
                }
            }
            int selected = outcome;
            boolean raw = advanced;
            flush =
                    () ->
                            panel.validateField(
                                    input[0],
                                    () -> {
                                        var updated = original.deepCopy();
                                        updated.addProperty("weight", Integer.parseInt(weight[0]));
                                        if (!empty) {
                                            try {
                                                var tag =
                                                        com.eynny.npships.authoring.document
                                                                .SnbtText.parse(nbt[0]);
                                                if (!raw) {
                                                    var id = ResourceLocation.parse(item[0].trim());
                                                    if (!BuiltInRegistries.ITEM.containsKey(id))
                                                        throw new IllegalArgumentException(
                                                                "Unknown equipment item: " + id);
                                                    tag.putString("id", id.toString());
                                                    tag.putInt("count", Integer.parseInt(count[0]));
                                                }
                                                if (tag.isEmpty()) {
                                                    updated.remove("nbt");
                                                    updated.addProperty("empty", true);
                                                } else {
                                                    updated.remove("empty");
                                                    updated.addProperty("nbt", tag.toString());
                                                }
                                            } catch (
                                                    com.mojang.brigadier.exceptions
                                                                    .CommandSyntaxException
                                                            bad) {
                                                throw new IllegalArgumentException(
                                                        bad.getMessage(), bad);
                                            }
                                        }
                                        var proposed = entry.deepCopy();
                                        var replacement = equipment.deepCopy();
                                        replacement.getAsJsonArray(slot).set(selected, updated);
                                        proposed.add("equipment", replacement);
                                        CrewEquipment.read(proposed)
                                                .validate(panel.mc.level.registryAccess());
                                        entry.add("equipment", replacement);
                                    });
            button(
                    Component.translatable("npships.authoring.delete"),
                    () -> mutate(list -> list.remove(outcome)));
        }
        button(Component.translatable("npships.crew.equipment_apply"), panel::build);
        panel.sections.end();
        return flush;
    }

    private void button(Component label, Runnable action) {
        panel.buttonAt(label, panel.x, panel.cursor, panel.contentWidth(), action);
        panel.cursor += EditorLayout.ROW_PITCH;
    }

    private void menu(Component title, List<EditorPopupMenu.Entry> entries) {
        int y = panel.cursor;
        panel.buttonAt(
                title,
                panel.x,
                y,
                panel.contentWidth(),
                () -> panel.host.toolMenu(panel.x, y, panel.contentWidth(), entries));
        panel.cursor += EditorLayout.ROW_PITCH;
    }

    private void add(boolean empty) {
        mutate(
                list -> {
                    var choice = new JsonObject();
                    choice.addProperty("weight", 1);
                    if (empty) choice.addProperty("empty", true);
                    else choice.addProperty("nbt", "{id:\"minecraft:stone\",count:1}");
                    list.add(choice);
                    outcome = list.size() - 1;
                });
    }

    private void mutate(Consumer<JsonArray> action) {
        // Button routing already flushed the previous draft. Reacquire accepted data, never an old
        // closure.
        var base = panel.doc();
        var config = base.configCopy();
        var entry = panel.zoneObject(config).getAsJsonArray("pool").get(type).getAsJsonObject();
        var equipment =
                entry.has("equipment") ? entry.getAsJsonObject("equipment") : new JsonObject();
        var list = equipment.has(slot) ? equipment.getAsJsonArray(slot) : new JsonArray();
        action.accept(list);
        if (list.isEmpty()) equipment.remove(slot);
        else equipment.add(slot, list);
        if (equipment.entrySet().isEmpty()) entry.remove("equipment");
        else entry.add("equipment", equipment);
        CrewEquipment.read(entry).validate(panel.mc.level.registryAccess());
        StructureCrew.validateBounds(base, config);
        panel.commit(base.withConfig(config));
        panel.build();
    }
}
