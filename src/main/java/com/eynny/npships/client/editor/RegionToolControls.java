package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.authoring.document.SchematicGlue;
import com.eynny.npships.authoring.document.SchematicReframe;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.screen.BlockEditorScreen;
import com.eynny.npships.client.editor.screen.SchematicBrowserScreen;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.eynny.npships.client.editor.widget.EntityTypeBox;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class RegionToolControls {
    private final EditorToolsPanel panel;
    private final CrewEquipmentControls equipment;

    RegionToolControls(EditorToolsPanel panel) {
        this.panel = panel;
        this.equipment = new CrewEquipmentControls(panel);
    }

    void segmentTools() {
        if (panel.segment.isEmpty()) {
            panel.sections.begin("list", "segments", true);
            var create =
                    panel.buttonAt(
                            panel.tr("new_segment"),
                            panel.x,
                            panel.cursor,
                            panel.w,
                            () -> {
                                if (panel.host.firstCorner() == null
                                        || panel.host.lastCorner() == null) {
                                    panel.host.temporaryCreateMode();
                                    panel.build();
                                    return;
                                }
                                var a = panel.host.firstCorner();
                                var b = panel.host.lastCorner();
                                if (a == null || b == null) return;
                                var config = panel.doc().configCopy();
                                var list =
                                        config.has("segments")
                                                ? config.getAsJsonArray("segments")
                                                : new JsonArray();
                                var s = new JsonObject();
                                String id = "segment_" + (list.size() + 1);
                                while (panel.hasSegment(id)) id += "_";
                                s.addProperty("id", id);
                                s.add(
                                        "origin",
                                        SchematicPatches.vector(
                                                new BlockPos(
                                                        Math.min(a.getX(), b.getX()),
                                                        Math.min(a.getY(), b.getY()),
                                                        Math.min(a.getZ(), b.getZ()))));
                                s.add(
                                        "size",
                                        SchematicPatches.vector(
                                                new BlockPos(
                                                        Math.abs(a.getX() - b.getX()) + 1,
                                                        Math.abs(a.getY() - b.getY()) + 1,
                                                        Math.abs(a.getZ() - b.getZ()) + 1)));
                                list.add(s);
                                config.add("segments", list);
                                StructureSegments.validate(
                                        panel.doc().nbtCopy(), StructureSegments.read(config));
                                panel.commit(panel.doc().withConfig(config));
                                panel.segment = id;
                                panel.host.clearRegion();
                            });
            create.active = true;
            create.setTooltip(Tooltip.create(panel.tr("new_segment_hint")));
            panel.cursor += 24;
            for (var s : panel.segments)
                panel.button(
                        (panel.segment.equals(s.id()) ? "> " : "") + s.id(),
                        () -> {
                            panel.host.setSelectMode();
                            panel.segment = s.id();
                            panel.build();
                        });
            panel.sections.end();
        }
        if (panel.segment.isEmpty() || !panel.hasSegment(panel.segment)) return;
        var s =
                panel.segments.stream()
                        .filter(v -> v.id().equals(panel.segment))
                        .findFirst()
                        .orElseThrow();
        String selectedSegment = panel.segment;
        String[] segmentName = {s.id()};
        var segmentField =
                panel.field(
                        "segment_name",
                        segmentName[0],
                        v -> {
                            segmentName[0] = v;
                            panel.settingsDirty = true;
                        });
        // Variant edits still address the old ID, so rename is the last field saver.
        panel.sections.begin("variants", "section_variants", true);
        panel.action(
                "base_variant", () -> panel.addBaseVariant(panel.currentSegment(panel.segment)));
        panel.action(
                "import_variant",
                () ->
                        panel.mc.setScreen(
                                new SchematicBrowserScreen(
                                        panel.host,
                                        (name, source) ->
                                                panel.host.importRegion(
                                                        name,
                                                        source,
                                                        panel.currentSegment(panel.segment).size(),
                                                        origin ->
                                                                panel.importVariant(
                                                                        panel.currentSegment(
                                                                                panel.segment),
                                                                        source,
                                                                        origin,
                                                                        name)))));
        panel.action("all_variants", () -> panel.openGallery(panel.currentSegment(panel.segment)));
        for (var v : s.pool()) {
            panel.button(
                    v.name() + " (" + v.weight() + ")",
                    () -> panel.openVariant(panel.currentSegment(panel.segment), v));
            String[] name = {v.name()}, weight = {"" + v.weight()};
            var nameField = panel.field("variant_name", name[0], value -> name[0] = value);
            var weightField = panel.field("weight", weight[0], value -> weight[0] = value);
            panel.defer(
                    () -> {
                        if (name[0].equals(v.name()) && weight[0].equals("" + v.weight())) {
                            panel.host.validation.result(
                                    nameField, panel.host.validation.generation(nameField), null);
                            return;
                        }
                        var c = panel.doc().configCopy();
                        for (var entry : c.getAsJsonArray("segments")) {
                            var object = entry.getAsJsonObject();
                            if (!object.get("id").getAsString().equals(s.id())) continue;
                            for (var variant : object.getAsJsonArray("pool")) {
                                var item = variant.getAsJsonObject();
                                if (item.get("variant").getAsString().equals(v.id())) {
                                    item.addProperty("name", name[0]);
                                    panel.validateField(
                                            weightField,
                                            () ->
                                                    item.addProperty(
                                                            "weight", Integer.parseInt(weight[0])));
                                }
                            }
                        }
                        try {
                            StructureSegments.validate(
                                    panel.doc().nbtCopy(), StructureSegments.read(c));
                        } catch (RuntimeException failure) {
                            var owner = weightField;
                            var probe = c.deepCopy();
                            for (var entry : probe.getAsJsonArray("segments"))
                                if (entry.getAsJsonObject().get("id").getAsString().equals(s.id()))
                                    for (var item : entry.getAsJsonObject().getAsJsonArray("pool"))
                                        if (item.getAsJsonObject()
                                                .get("variant")
                                                .getAsString()
                                                .equals(v.id()))
                                            item.getAsJsonObject()
                                                    .addProperty("weight", v.weight());
                            try {
                                StructureSegments.validate(
                                        panel.doc().nbtCopy(), StructureSegments.read(probe));
                            } catch (RuntimeException diagnostic) {
                                if (Objects.equals(diagnostic.getMessage(), failure.getMessage()))
                                    owner = nameField;
                            }
                            panel.validateField(
                                    owner,
                                    () -> {
                                        throw failure;
                                    });
                        }
                        panel.commit(panel.doc().withConfig(c));
                        panel.host.validation.result(
                                nameField, panel.host.validation.generation(nameField), null);
                    });
            panel.action(
                    "delete_variant",
                    () ->
                            panel.commit(
                                    SchematicReframe.deleteVariant(
                                            panel.doc(), panel.segment, v.id())));
        }
        panel.sections.end();
        panel.defer(
                segmentField,
                () -> {
                    if (!segmentName[0].equals(selectedSegment)) {
                        var renamed =
                                StructureSegments.rename(
                                        panel.doc(), selectedSegment, segmentName[0]);
                        panel.commit(renamed);
                        panel.segment = segmentName[0];
                    }
                });
        panel.action(
                "delete_segment",
                () -> {
                    panel.commit(SchematicReframe.deleteSegment(panel.doc(), panel.segment));
                    panel.segment = "";
                });
    }

    void glueTools() {
        // Creation/deletion changes indices and bounds before the panel's end-of-build refresh.
        panel.entityPreview.refresh(panel.doc(), panel.mc.level);
        if (panel.entityIndex < 0) {
            boolean list =
                    panel.entityPreview.entityEntries.stream()
                            .anyMatch(
                                    e ->
                                            SchematicGlue.supported(
                                                    ((CompoundTag) e)
                                                            .getCompound("nbt")
                                                            .getString("id")));
            if (list) panel.sections.begin("list", "glue", true);
            var formats = SchematicGlue.formats();
            if (formats.stream().noneMatch(format -> format.type().equals(panel.glueType)))
                panel.glueType =
                        formats.stream().findFirst().map(format -> format.type()).orElse("");
            for (var format : formats) {
                panel.buttonAt(
                                panel.tr(format.label()),
                                panel.x,
                                panel.cursor,
                                panel.w,
                                () -> {
                                    panel.glueType = format.type();
                                    panel.host.temporaryCreateMode();
                                    panel.build();
                                })
                        .selected(() -> panel.glueType.equals(format.type()));
                panel.cursor += 24;
            }
            panel.placementModes();
            for (int i = 0; i < panel.entityPreview.entityEntries.size(); i++) {
                int index = i;
                String id =
                        panel.entityPreview
                                .entityEntries
                                .getCompound(i)
                                .getCompound("nbt")
                                .getString("id");
                if (SchematicGlue.supported(id))
                    panel.button(
                            id + " #" + i,
                            () -> {
                                panel.entityIndex = index;
                                panel.build();
                            });
            }
            if (list) panel.sections.end();
            return;
        }
        var b = panel.selectedBounds();
        if (b == null) return;
        int index = panel.entityIndex;
        String id =
                panel.entityPreview
                        .entityEntries
                        .getCompound(index)
                        .getCompound("nbt")
                        .getString("id");
        panel.action(
                "delete_entity",
                () -> {
                    panel.commit(SchematicEdits.entity(panel.doc(), index, Vec3.ZERO, null));
                    panel.entityIndex = -1;
                });
    }

    void zoneTools() {
        if (panel.zone.isEmpty()) {
            panel.pendingSettings = null;
            panel.sections.begin("list", "zones", true);
            panel.placementModes();
            panel.action(
                    "new_zone",
                    () -> {
                        panel.host.clearRegion();
                        panel.host.setCreateMode();
                    });
            for (var rule : panel.zones)
                panel.button(
                        rule.id(),
                        () -> {
                            panel.host.setSelectMode();
                            panel.zone = rule.id();
                            panel.scroll = 0;
                            panel.build();
                        });
            panel.sections.end();
            return;
        }
        var rule =
                panel.zones.stream()
                        .filter(r -> r.id().equals(panel.zone))
                        .findFirst()
                        .orElse(null);
        if (rule == null) {
            panel.zone = "";
            return;
        }
        String selectedZone = panel.zone;
        var edited = panel.zoneObject(panel.doc().configCopy()).deepCopy();
        String[] origin = {panel.xyz(rule.origin())},
                size = {panel.xyz(rule.size())},
                min = {"" + rule.minCount()},
                max = {"" + rule.count()},
                name = {rule.name()};
        var entries = new ArrayList<JsonObject>();
        for (var entry : edited.getAsJsonArray("pool"))
            entries.add(entry.getAsJsonObject().deepCopy());
        var definitionFields = new LinkedHashMap<String, AbstractWidget>();
        var typeFields = new ArrayList<AbstractWidget>();
        var weightFields = new ArrayList<AbstractWidget>();
        AbstractWidget[] changedField = {null};
        Runnable[] equipmentFlush = {() -> {}};
        Runnable accept =
                () -> {
                    equipmentFlush[0].run();
                    var c = panel.doc().configCopy();
                    var obj = edited.deepCopy();
                    obj.add(
                            "origin",
                            SchematicPatches.vector(BlockEditorScreen.position(origin[0])));
                    obj.add("size", SchematicPatches.vector(BlockEditorScreen.position(size[0])));
                    obj.remove("count");
                    panel.validateField(
                            definitionFields.get("min_count"),
                            () -> obj.addProperty("min_count", Integer.parseInt(min[0])));
                    panel.validateField(
                            definitionFields.get("max_count"),
                            () -> obj.addProperty("max_count", Integer.parseInt(max[0])));
                    obj.addProperty("name", name[0]);
                    var pool = new JsonArray();
                    for (int at = 0; at < entries.size(); at++) {
                        var entry = entries.get(at);
                        var e = entry.deepCopy();
                        panel.validateField(
                                weightFields.get(at),
                                () ->
                                        e.addProperty(
                                                "weight",
                                                e.has("weight")
                                                        ? Integer.parseInt(
                                                                e.get("weight").getAsString())
                                                        : 1));
                        pool.add(e);
                    }
                    obj.add("pool", pool);
                    var list = c.getAsJsonArray("crew");
                    for (int i = 0; i < list.size(); i++)
                        if (list.get(i)
                                .getAsJsonObject()
                                .get("id")
                                .getAsString()
                                .equals(selectedZone)) list.set(i, obj);
                    try {
                        StructureCrew.validateBounds(panel.doc(), c);
                    } catch (RuntimeException failure) {
                        var owner =
                                zoneFailureField(
                                        c,
                                        edited,
                                        rule,
                                        definitionFields,
                                        typeFields,
                                        weightFields,
                                        changedField[0],
                                        failure);
                        panel.validateField(
                                owner,
                                () -> {
                                    throw failure;
                                });
                    }
                    panel.commit(panel.doc().withConfig(c));
                    for (var field : definitionFields.values())
                        panel.host.validation.result(
                                field, panel.host.validation.generation(field), null);
                    for (var field : typeFields)
                        panel.host.validation.result(
                                field, panel.host.validation.generation(field), null);
                    for (var field : weightFields)
                        panel.host.validation.result(
                                field, panel.host.validation.generation(field), null);
                };
        panel.sections.begin("definition", "section_zone_definition", true);
        definitionFields.put(
                "min_count",
                panel.field(
                        "min_count",
                        min[0],
                        v -> {
                            min[0] = v;
                            changedField[0] = definitionFields.get("min_count");
                            panel.settingsDirty = true;
                        }));
        definitionFields.put(
                "max_count",
                panel.field(
                        "max_count",
                        max[0],
                        v -> {
                            max[0] = v;
                            changedField[0] = definitionFields.get("max_count");
                            panel.settingsDirty = true;
                        }));
        definitionFields.put(
                "entity_name",
                panel.field(
                        "entity_name",
                        name[0],
                        v -> {
                            name[0] = v;
                            changedField[0] = definitionFields.get("entity_name");
                            panel.settingsDirty = true;
                        }));
        panel.sections.end();
        panel.sections.begin("placement", "section_placement", true);
        panel.toggle(
                "prevent_despawn",
                rule::preventDespawn,
                () -> {
                    var c = panel.doc().configCopy();
                    var zone = panel.zoneObject(c);
                    boolean before =
                            !zone.has("prevent_despawn")
                                    || zone.get("prevent_despawn").getAsBoolean();
                    zone.addProperty("prevent_despawn", !before);
                    panel.commit(panel.doc().withConfig(c));
                });
        panel.widgets.getLast().setTooltip(Tooltip.create(panel.tr("prevent_despawn_hint")));
        for (String placement : List.of("ground", "air", "water")) {
            var button =
                    panel.buttonAt(
                            panel.tr("placement_" + placement),
                            panel.x,
                            panel.cursor,
                            panel.w,
                            () -> {
                                var c = panel.doc().configCopy();
                                panel.zoneObject(c).addProperty("placement", placement);
                                panel.commit(panel.doc().withConfig(c));
                            });
            panel.cursor += 24;
            button.selected(() -> rule.placement().equals(placement));
            button.setTooltip(Tooltip.create(panel.tr("placement_" + placement + "_hint")));
        }
        panel.sections.end();
        panel.sections.begin("pool", "section_mob_pool", true);
        for (int i = 0; i < entries.size(); i++) {
            int index = i;
            var entry = entries.get(i);
            int typeWidth = panel.contentWidth() - 42 - 20 - 2 * EditorLayout.CONTROL_GAP;
            panel.widgets.add(
                    new StringWidget(
                                    panel.x,
                                    panel.cursor,
                                    typeWidth,
                                    12,
                                    panel.tr("entity_type_tag"),
                                    panel.mc.font)
                            .alignLeft());
            panel.stretch(panel.widgets.getLast());
            panel.widgets.add(
                    new StringWidget(
                                    panel.x + typeWidth + EditorLayout.CONTROL_GAP,
                                    panel.cursor,
                                    42,
                                    12,
                                    panel.tr("mob_weight"),
                                    panel.mc.font)
                            .alignLeft());
            panel.anchorRight(panel.widgets.getLast());
            panel.cursor += 14;
            panel.widgets.add(
                    new EntityTypeBox(
                            panel.x,
                            panel.cursor,
                            typeWidth,
                            entry.get("type").getAsString(),
                            v -> {
                                entry.addProperty("type", v);
                                changedField[0] = typeFields.get(index);
                                panel.host.validation.changed(changedField[0]);
                                panel.settingsDirty = true;
                            }));
            typeFields.add(panel.widgets.getLast());
            panel.host.validation.register(panel.widgets.getLast(), panel.sections.currentId());
            panel.stretch(panel.widgets.getLast());
            var weight =
                    new EditorEditBox(
                            panel.mc.font,
                            panel.x + typeWidth + EditorLayout.CONTROL_GAP,
                            panel.cursor,
                            42,
                            20,
                            panel.tr("mob_weight"));
            weight.setMaxLength(9);
            weight.setValue(entry.has("weight") ? entry.get("weight").getAsString() : "1");
            weight.setResponder(
                    v -> {
                        entry.addProperty("weight", v);
                        changedField[0] = weight;
                        panel.host.validation.changed(weight);
                        panel.settingsDirty = true;
                    });
            weight.setTooltip(Tooltip.create(panel.tr("mob_weight_hint")));
            panel.widgets.add(weight);
            weightFields.add(weight);
            panel.host.validation.register(weight, panel.sections.currentId());
            panel.anchorRight(weight);
            panel.buttonAt(
                    Component.literal("-"),
                    panel.x + panel.contentWidth() - 20,
                    panel.cursor,
                    20,
                    () -> {
                        var c = panel.doc().configCopy();
                        panel.zoneObject(c).getAsJsonArray("pool").remove(index);
                        StructureCrew.read(c);
                        panel.commit(panel.doc().withConfig(c));
                    });
            panel.cursor += 28;
        }
        String[] newType = {""};
        AbstractWidget[] newTypeField = {null};
        panel.widgets.add(
                new StringWidget(
                                panel.x,
                                panel.cursor,
                                panel.contentWidth(),
                                12,
                                panel.tr("new_pool_type"),
                                panel.mc.font)
                        .alignLeft());
        panel.cursor += 14;
        panel.widgets.add(
                new EntityTypeBox(
                        panel.x,
                        panel.cursor,
                        panel.contentWidth(),
                        "",
                        v -> {
                            newType[0] = v;
                            panel.host.validation.changed(newTypeField[0]);
                        }));
        newTypeField[0] = panel.widgets.getLast();
        panel.host.validation.register(newTypeField[0], panel.sections.currentId());
        panel.cursor += 24;
        panel.action(
                "add_pool_type",
                () -> {
                    var c = panel.doc().configCopy();
                    var item = new JsonObject();
                    item.addProperty("type", newType[0].trim());
                    item.addProperty("weight", 1);
                    panel.zoneObject(c).getAsJsonArray("pool").add(item);
                    panel.validateField(newTypeField[0], () -> StructureCrew.read(c));
                    panel.commit(panel.doc().withConfig(c));
                });
        panel.sections.end();
        equipmentFlush[0] = equipment.build(entries);
        // Keep the original single atomic acceptance boundary and callback ordering.
        panel.pendingSettings = accept;
        panel.action("delete_zone", panel::deleteZone);
    }

    private record ZoneField(AbstractWidget widget, String key, int entry) {}

    /** Failed explicit acceptance only: use the native validator to attribute the draft error. */
    private AbstractWidget zoneFailureField(
            JsonObject config,
            JsonObject initial,
            StructureCrew.Rule rule,
            Map<String, AbstractWidget> definition,
            List<AbstractWidget> types,
            List<AbstractWidget> weights,
            AbstractWidget lastChanged,
            RuntimeException failure) {
        var baseline = initial.deepCopy();
        baseline.remove("count");
        baseline.addProperty("min_count", rule.minCount());
        baseline.addProperty("max_count", rule.count());
        baseline.addProperty("name", rule.name());
        for (var item : baseline.getAsJsonArray("pool"))
            if (!item.getAsJsonObject().has("weight"))
                item.getAsJsonObject().addProperty("weight", 1);
        var fields = new ArrayList<ZoneField>();
        definition.forEach(
                (key, widget) ->
                        fields.add(
                                new ZoneField(
                                        widget, key.equals("entity_name") ? "name" : key, -1)));
        for (int i = 0; i < types.size(); i++) {
            fields.add(new ZoneField(types.get(i), "type", i));
            fields.add(new ZoneField(weights.get(i), "weight", i));
        }
        var draft = panel.zoneObject(config);
        for (var field : fields) {
            var from =
                    field.entry() < 0
                            ? draft
                            : draft.getAsJsonArray("pool").get(field.entry()).getAsJsonObject();
            var original =
                    field.entry() < 0
                            ? baseline
                            : baseline.getAsJsonArray("pool").get(field.entry()).getAsJsonObject();
            if (Objects.equals(from.get(field.key()), original.get(field.key()))) continue;
            var isolated = baseline.deepCopy();
            var target =
                    field.entry() < 0
                            ? isolated
                            : isolated.getAsJsonArray("pool").get(field.entry()).getAsJsonObject();
            target.add(field.key(), from.get(field.key()).deepCopy());
            var probe = config.deepCopy();
            var list = probe.getAsJsonArray("crew");
            for (int i = 0; i < list.size(); i++)
                if (list.get(i).getAsJsonObject().get("id").getAsString().equals(rule.id()))
                    list.set(i, isolated);
            try {
                StructureCrew.validateBounds(panel.doc(), probe);
            } catch (RuntimeException diagnostic) {
                if (Objects.equals(diagnostic.getMessage(), failure.getMessage()))
                    return field.widget();
            }
        }
        // Cross-field constraints can need multiple changes; retain their last edited field.
        return lastChanged;
    }
}
