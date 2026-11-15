package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.EntitySettings;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class EntityToolControls {
    private final EditorToolsPanel panel;

    EntityToolControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void entityTools() {
        panel.placementModes();
        if (panel.entityIndex < 0) {
            panel.sections.begin("brush", "section_entity_brush", true);
            panel.field(
                    "entity_type",
                    panel.entityType,
                    v -> {
                        panel.entityType = v;
                        panel.entityBrush = null;
                        panel.entityBrushPatches = java.util.List.of();
                    });
            panel.registry(true);
            panel.quickRows(true);
            panel.sections.end();
            panel.sections.begin("list", "placed_entities", true);
        }
        if (panel.entityIndex >= 0) {
            final int editingEntity = panel.entityIndex;
            var e = panel.cached.entitiesCopy().getCompound(panel.entityIndex);
            var p = e.getList("pos", Tag.TAG_DOUBLE);
            String[] location = {p.getDouble(0) + " " + p.getDouble(1) + " " + p.getDouble(2)},
                    nbt = {e.getCompound("nbt").toString()};
            var originalData = e.getCompound("nbt");
            panel.sections.begin("position", "position", true);
            var positionField = panel.field("position", location[0], v -> location[0] = v, false);
            String initialPosition = location[0];
            panel.defer(
                    positionField,
                    () -> {
                        if (location[0].equals(initialPosition)) return;
                        var xyz = location[0].trim().split("[ ,;]+");
                        if (xyz.length != 3) throw invalid("position_xyz", "X Y Z", location[0]);
                        panel.commit(
                                SchematicEdits.entity(
                                        panel.doc(),
                                        editingEntity,
                                        new Vec3(
                                                Double.parseDouble(xyz[0]),
                                                Double.parseDouble(xyz[1]),
                                                Double.parseDouble(xyz[2])),
                                        panel.doc()
                                                .entitiesCopy()
                                                .getCompound(editingEntity)
                                                .getCompound("nbt")));
                    });
            panel.sections.end();
            boolean compact = panel.host.hideOverriddenEntityFields();
            var shown = EntitySettings.textView(originalData, panel.mc.level, compact);
            var initial = new net.minecraft.nbt.SnbtPrinterTagVisitor().visit(shown);
            var rawBaseline = new CompoundTag[] {shown};
            panel.sections.begin("data", "section_data", true);
            panel.dataEditor(
                    initial,
                    previous ->
                            EntitySettings.suggestions(
                                    panel.doc()
                                            .entitiesCopy()
                                            .getCompound(editingEntity)
                                            .getCompound("nbt"),
                                    panel.mc.level,
                                    compact,
                                    previous),
                    value -> {},
                    value -> {
                        try {
                            var entry = panel.doc().entitiesCopy().getCompound(editingEntity);
                            var raw = com.eynny.npships.authoring.document.SnbtText.parse(value);
                            var data =
                                    EntitySettings.textEdit(
                                            entry.getCompound("nbt"), rawBaseline[0], raw, compact);
                            if (data.equals(entry.getCompound("nbt"))) {
                                rawBaseline[0] = raw;
                                return;
                            }
                            com.eynny.npships.authoring.document.ItemSettings.validateChanges(
                                    entry.getCompound("nbt"),
                                    data,
                                    com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject
                                            .entity(data.getString("id")),
                                    panel.mc.level.registryAccess());
                            var at = entry.getList("pos", Tag.TAG_DOUBLE);
                            panel.liveCommit(
                                    SchematicEdits.entity(
                                            panel.doc(),
                                            editingEntity,
                                            new Vec3(
                                                    at.getDouble(0),
                                                    at.getDouble(1),
                                                    at.getDouble(2)),
                                            data));
                            rawBaseline[0] = raw;
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                            throw com.eynny.npships.authoring.validation.ValidationProblem.describe(
                                    new IllegalArgumentException(ex.getMessage(), ex),
                                    "snbt_syntax");
                        }
                    });
            panel.sections.end();
            panel.entitySpecializedControls(originalData);
            panel.externalPatchControls.renderEntity();
        }
        var saved = panel.cached.entitiesCopy();
        for (int i = 0; i < saved.size(); i++) {
            int index = i;
            var e = saved.getCompound(i);
            if (panel.entityIndex < 0
                    && panel.isGlueId(e.getCompound("nbt").getString("id"))
                            == (panel.tool == EditorToolsPanel.Tool.GLUE))
                panel.button(
                        e.getCompound("nbt").getString("id")
                                + " "
                                + e.getList("pos", Tag.TAG_DOUBLE),
                        () -> {
                            panel.entityIndex = index;
                            panel.scroll = 0;
                            panel.build();
                        });
        }
        if (panel.entityIndex < 0) panel.sections.end();
    }
}
