package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class PatchToolControls {
    private final EditorToolsPanel panel;

    PatchToolControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    private void note(String key) {
        panel.wrappedNote(key);
    }

    void patchTools() {
        panel.patchEditors.refresh();
        if (panel.linkRole != null) {
            panel.linkTargets =
                    panel.patchLinks.targets().stream()
                            .filter(panel::compatibleLink)
                            .sorted(
                                    Comparator.<BlockPos>comparingInt(BlockPos::getY)
                                            .thenComparingInt(BlockPos::getX)
                                            .thenComparingInt(BlockPos::getZ))
                            .toList();
            panel.sections.begin(
                    panel.patchLinks.section(),
                    panel.tr("choose_device").getString()
                            + ": "
                            + panel.tr("link_" + panel.linkRole).getString(),
                    true);
            panel.action(
                    "cancel",
                    () -> {
                        panel.linkRole = null;
                        panel.patchLinks.close();
                        panel.build();
                    });
            panel.sections.operationAction(panel.widgets.getLast());
            panel.patchLinks.choices(panel.linkTargets);
            panel.sections.end();
            return;
        }
        var patches = ExternalPatches.authoringEntries(panel.cached);
        // Saved Lua/links remain editable without their mod. No native provider is executed here.
        String unavailable =
                new com.eynny.npships.authoring.patches.PatchAvailability().unavailable(patches);
        if (!unavailable.isEmpty()) {
            note("patch_data_only");
        }
        var incomingLinks = new HashMap<BlockPos, List<JsonObject>>();
        for (var patch : patches)
            for (var target : PatchDevices.targets(patch))
                incomingLinks.computeIfAbsent(target, p -> new ArrayList<>()).add(patch);
        if (panel.pos() == null) {
            panel.sections.begin("devices", "section_devices", true);
            panel.cachePatchHighlights();
            for (var p : panel.patchHighlights.keySet()) {
                String id = PatchDevices.block(panel.cached, p);
                var found =
                        patches.stream()
                                .filter(
                                        e ->
                                                SchematicPatches.position(e.getAsJsonArray("pos"))
                                                        .equals(p))
                                .findFirst();
                String state =
                        panel.tr(!incomingLinks.containsKey(p) ? "device_missing" : "device_ready")
                                .getString();
                if (found.isPresent()) {
                    try {
                        PatchDevices.validateLinks(found.get(), panel.cached);
                        for (var code :
                                com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch
                                        .all()) code.inspect(found.get());
                        state = panel.tr("device_ready").getString();
                    } catch (Exception error) {
                        state = panel.tr("device_broken").getString();
                    }
                }
                panel.button(
                        id + " " + p.toShortString() + " ? " + state,
                        () -> {
                            panel.host.select(p);
                            panel.scroll = 0;
                            panel.build();
                        });
            }
            panel.sections.end();
            return;
        }
        for (var incoming : incomingLinks.getOrDefault(panel.pos(), List.of())) {
            var source = SchematicPatches.position(incoming.getAsJsonArray("pos"));
            panel.button(
                    panel.tr("linked_from").getString() + " " + source.toShortString(),
                    () -> {
                        panel.host.select(source);
                        panel.build();
                    });
            panel.patchLinks.incoming(incoming);
        }
        String id = PatchDevices.block(panel.cached, panel.pos());
        var found =
                patches.stream()
                        .filter(e -> !ExternalPatches.external(e))
                        .filter(
                                e ->
                                        SchematicPatches.position(e.getAsJsonArray("pos"))
                                                        .equals(panel.pos())
                                                || panel.patchEditors.selects(e, panel.pos())
                                                || com
                                                        .eynny
                                                        .npships
                                                        .authoring
                                                        .patches
                                                        .PatchSourceOperationsDispatch
                                                        .all()
                                                        .stream()
                                                        .anyMatch(
                                                                code ->
                                                                        code.selects(
                                                                                panel.cached,
                                                                                e,
                                                                                panel.pos())))
                        .sorted(
                                Comparator.comparingInt(
                                        e ->
                                                com
                                                        .eynny
                                                        .npships
                                                        .authoring
                                                        .patches
                                                        .PatchSourceOperationsDispatch
                                                        .all()
                                                        .stream()
                                                        .mapToInt(code -> code.selectionRank(e))
                                                        .min()
                                                        .orElse(1)))
                        .findFirst();
        if (panel.externalPatchControls.pending()) {
            panel.externalPatchControls.render(null);
            return;
        }
        for (var external : patches)
            if (ExternalPatches.external(external)
                    && SchematicPatches.position(external.getAsJsonArray("pos"))
                            .equals(panel.pos())) panel.externalPatchControls.render(external);
        int beforeExternal = panel.widgets.size();
        panel.externalPatchControls.render(null);
        if (panel.patchRegionTool.render()) return;
        if (found.isEmpty()
                && panel.widgets.size() == beforeExternal
                && PatchDevices.kind(panel.cached, panel.pos()).isEmpty()
                && !panel.patchRegionTool.supported())
            panel.wrappedNote("patch_external_no_controls");
        PatchInspectorContributions.header(panel, found.orElse(null));
        if (found.isEmpty() && !PatchDevices.kind(panel.cached, panel.pos()).isEmpty()) {
            panel.action(
                    "new_patch",
                    () -> panel.putPatch(PatchDevices.create(panel.doc(), panel.pos())));
            if (panel.patchEditors.blocksCreation(panel.pos()))
                panel.widgets.getLast().active = false;
        }
        if (found.isPresent()) {
            panel.action(
                    "delete_patch",
                    () -> {
                        var next = panel.doc();
                        var owner =
                                SchematicPatches.position(
                                        panel.currentPatch(found.get()).getAsJsonArray("pos"));
                        var currentPatches = ExternalPatches.authoringEntries(panel.doc());
                        for (var p : currentPatches) {
                            var position = SchematicPatches.position(p.getAsJsonArray("pos"));
                            if (position.equals(owner) || panel.patchEditors.selects(p, owner))
                                next = PatchDevices.remove(next, position);
                        }
                        for (var external : currentPatches)
                            if (ExternalPatches.external(external))
                                next = ExternalPatches.putAuthoring(next, external);
                        panel.commit(next);
                    });
            var patch = found.get();
            String kind = patch.get("kind").getAsString();
            try {
                PatchDevices.validateLinks(patch, panel.cached);
                for (var code :
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                    code.inspect(patch);
            } catch (Exception error) {
                panel.button(error.getMessage(), () -> {});
            }
            PatchInspectorContributions.fields(panel, patch);
            panel.sections.begin("expert", "patch_json", false);
            {
                String initialJson = new GsonBuilder().setPrettyPrinting().create().toJson(patch);
                String[] json = {initialJson};
                var jsonField = panel.multiline(json[0], v -> json[0] = v);
                panel.defer(
                        jsonField,
                        () -> {
                            if (!json[0].equals(initialJson))
                                panel.putPatch(JsonStructure.parse(json[0]).getAsJsonObject());
                        });
            }
            panel.sections.end();
        }
        panel.blockData();
    }
}
