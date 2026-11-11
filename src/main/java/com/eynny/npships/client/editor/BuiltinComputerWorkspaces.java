package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.*;

import java.util.*;

/** Native program/media workflows owned by their registered source provider, not the host panel. */
public final class BuiltinComputerWorkspaces {
    private BuiltinComputerWorkspaces() {}

    static void openComputer(
            EditorToolsPanel panel, JsonObject record, String path, String initial) {
        com.eynny.npships.authoring.patches.PatchCode.authorControl(
                        PatchTextWorkspace.class,
                        ResourceLocation.parse("npships:computercraft_computer"))
                .open(panel, record, null, path, initial);
    }

    static void openMedia(
            EditorToolsPanel panel,
            JsonObject record,
            String container,
            String path,
            String initial) {
        com.eynny.npships.authoring.patches.PatchCode.authorControl(
                        PatchTextWorkspace.class,
                        ResourceLocation.parse("npships:computercraft_media"))
                .open(panel, record, container, path, initial);
    }

    static String decodeText(String base64) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(base64)))
                    .toString()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (Exception e) {
            throw invalid("binary_file", "This file is binary; edit its Base64 in patch JSON.");
        }
    }

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract != PatchTextWorkspace.class
                || !Set.of("computercraft_computer", "computercraft_media").contains(provider))
            return null;
        return contract.cast(
                (PatchTextWorkspace)
                        (panel, record, container, path, initial) -> {
                            var form = new Form(panel);
                            if (provider.equals("computercraft_computer"))
                                form.openCode(record, path, initial);
                            else form.openMediaCode(record, container, path, initial);
                        });
    }

    private static final class Form {
        private final EditorToolsPanel panel;

        Form(EditorToolsPanel panel) {
            this.panel = panel;
        }

        private void codeFileContext(String family) {
            panel.focusWidget(null);
            panel.widgets.clear();
            panel.resetTextWorkspace();
            panel.sections.reset(family);
            panel.rebuild = false;
            panel.searchStart = panel.searchEnd = -1;
            panel.itemCells.clear();
            panel.lootRows.clear();
            panel.fieldSavers.clear();
            panel.pendingSettings = null;
            panel.cursor = panel.layout.inspectorContent().top();
            panel.scroll = 0;
        }

        private void codeFileActions(Runnable deletion, Component cancel, boolean applyHint) {
            panel.button(
                    panel.tr("apply_code").getString(),
                    () -> {
                        panel.pendingSettings = null;
                        panel.build();
                    });
            if (applyHint)
                panel.widgets.getLast().setTooltip(Tooltip.create(panel.tr("apply_code_hint")));
            panel.widgets.add(
                    EditorButton.flat(
                                    panel.tr("delete_file"),
                                    ignored -> {
                                        panel.pendingSettings = null;
                                        panel.settingsDirty = false;
                                        panel.run(deletion);
                                    })
                            .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                            .build());
            panel.cursor += 24;
            panel.widgets.add(
                    EditorButton.flat(
                                    cancel,
                                    ignored -> {
                                        panel.pendingSettings = null;
                                        panel.settingsDirty = false;
                                        panel.build();
                                    })
                            .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                            .build());
            panel.cursor += 24;
        }

        private JsonObject currentMediaPatch(JsonObject expected, String disk) {
            var current = panel.currentPatch(expected);
            if (!current.has("media")
                    || !current.get("media").isJsonObject()
                    || !current.getAsJsonObject("media").has(disk)
                    || !current.getAsJsonObject("media").get(disk).isJsonObject())
                throw invalid(
                        "action_target_changed", panel.tr("action_target_changed").getString());
            return current;
        }

        private void sameFile(JsonObject files, String path, JsonElement expected) {
            if (!Objects.equals(files.get(path), expected))
                throw invalid(
                        "action_target_changed", panel.tr("action_target_changed").getString());
        }

        void openMediaCode(JsonObject patch, String disk, String path, String requestedInitial) {
            var accepted = currentMediaPatch(patch, disk);
            var acceptedFiles = accepted.getAsJsonObject("media").getAsJsonObject(disk);
            var expected = acceptedFiles.has(path) ? acceptedFiles.get(path).deepCopy() : null;
            if (patch.getAsJsonObject("media").getAsJsonObject(disk).has(path) && expected == null)
                throw invalid(
                        "action_target_changed", panel.tr("action_target_changed").getString());
            String initial =
                    expected == null ? requestedInitial : decodeText(expected.getAsString());
            codeFileContext("patches:media.workspace");
            String[] code = {initial}, name = {path};
            if (expected == null) panel.settingsDirty = true;
            codeFileActions(
                    () -> {
                        var next = currentMediaPatch(accepted, disk);
                        var files = next.getAsJsonObject("media").getAsJsonObject(disk);
                        sameFile(files, path, expected);
                        files.remove(path);
                        panel.putPatch(next);
                    },
                    panel.tr("cancel"),
                    false);
            panel.sections.begin("computer.media.code", "section_computer_media", true);
            var nameField =
                    panel.field(
                            "media_file",
                            path,
                            v -> {
                                name[0] = v;
                                panel.settingsDirty = true;
                            });
            var dock = panel.layout.codeDock(panel.cursor, 0, panel.layout.codeDockHeight());
            var editor =
                    new LuaEditBox(panel.mc.font, dock.left(), dock.top(), dock.width(), List.of());
            editor.setValue(initial);
            editor.setValueListener(
                    v -> {
                        panel.host.validation.changed(editor);
                        code[0] = v;
                        panel.settingsDirty = true;
                    });
            panel.host.validation.register(editor, panel.sections.currentId());
            panel.widgets.add(editor);
            editor.setHeight(
                    editor.compactHeight(
                            panel.layout.contentWidth(), panel.layout.codeDockHeight()));
            panel.cursor += editor.getHeight() + 4;
            panel.codeControls(editor);
            panel.pendingSettings =
                    () -> {
                        var next = currentMediaPatch(accepted, disk);
                        var files = next.getAsJsonObject("media").getAsJsonObject(disk);
                        sameFile(files, path, expected);
                        panel.validateField(
                                nameField,
                                () -> {
                                    if (!name[0].equals(path) && files.has(name[0]))
                                        throw invalid(
                                                "file_exists", "File already exists", name[0]);
                                    EditorErrorText.computerPath(
                                            () ->
                                                    com.eynny.npships.integration.computercraft
                                                            .ComputerPatch.relative(name[0]),
                                            name[0]);
                                });
                        boolean unchangedCode = code[0].equals(initial);
                        if (unchangedCode && name[0].equals(path) && files.has(path)) return;
                        String encoded =
                                unchangedCode && files.has(path)
                                        ? files.get(path).getAsString()
                                        : Base64.getEncoder()
                                                .encodeToString(
                                                        code[0].getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                        files.remove(path);
                        files.addProperty(name[0], encoded);
                        panel.validateField(editor, () -> panel.putPatch(next));
                    };
            panel.sections.end();
            panel.contentHeight = panel.cursor - panel.layout.inspectorContent().top();
            panel.captureArrangement();
            panel.layoutOnly(panel.layout);
        }

        private JsonObject currentComputerPatch(JsonObject expected, boolean existed) {
            boolean present =
                    ExternalPatches.authoringEntries(panel.doc()).stream()
                            .anyMatch(
                                    p ->
                                            PatchInstances.key(p, false)
                                                    .equals(PatchInstances.key(expected, false)));
            if (present || existed) return panel.currentPatch(expected);
            var anchor = SchematicPatches.position(expected.getAsJsonArray("pos"));
            if (!anchor.equals(panel.pos())
                    || !panel.doc().blockName(anchor).equals(expected.get("block").getAsString()))
                throw invalid(
                        "action_target_changed", panel.tr("action_target_changed").getString());
            return expected.deepCopy();
        }

        void openCode(JsonObject originalPatch, String path, String initial) {
            boolean existed =
                    ExternalPatches.authoringEntries(panel.doc()).stream()
                            .anyMatch(
                                    p ->
                                            PatchInstances.key(p, false)
                                                    .equals(
                                                            PatchInstances.key(
                                                                    originalPatch, false)));
            var network =
                    new com.eynny.npships.integration.computercraft.ComputerNetwork(panel.doc());
            var connected = network.devices(panel.pos());
            var patch =
                    network.withReferences(
                            existed ? panel.currentPatch(originalPatch) : originalPatch);
            var expected =
                    patch.getAsJsonObject("files").has(path)
                            ? patch.getAsJsonObject("files").get(path).deepCopy()
                            : null;
            String acceptedInitial =
                    expected == null ? initial : decodeText(expected.getAsString());
            codeFileContext("patches:computer.workspace");
            String[] code = {acceptedInitial}, name = {path};
            if (!patch.getAsJsonObject("files").has(path)) panel.settingsDirty = true;
            codeFileActions(
                    () -> {
                        var next = panel.currentPatch(patch);
                        sameFile(next.getAsJsonObject("files"), path, expected);
                        next.getAsJsonObject("files").remove(path);
                        panel.putPatch(next);
                    },
                    Component.translatable("gui.cancel"),
                    true);
            panel.sections.begin("computer.code", "section_computer_files", true);
            var nameField =
                    panel.field(
                            "file",
                            path,
                            v -> {
                                name[0] = v;
                                panel.settingsDirty = true;
                            });
            var snippets = new ArrayList<String>();
            for (var device : connected)
                if (device.type().equals("modem") && !device.route().equals("wired"))
                    snippets.add("rednet.open(\"" + device.route() + "\")");
            network.builtins(panel.pos())
                    .forEach(
                            (side, type) -> {
                                if (type.equals("modem"))
                                    snippets.add("rednet.open(\"" + side + "\")");
                            });
            network.builtins(panel.pos())
                    .forEach((side, type) -> snippets.add("peripheral.wrap(\"" + side + "\")"));
            if (patch.has("references"))
                for (var ref : patch.getAsJsonObject("references").entrySet()) {
                    var target =
                            panel.pos()
                                    .offset(
                                            SchematicPatches.position(
                                                    ref.getValue()
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("offset")));
                    if (network.referenceDevice(panel.pos(), target).isEmpty()) continue;
                    String fn =
                            PatchDevices.kind(
                                                    ref.getValue()
                                                            .getAsJsonObject()
                                                            .get("block")
                                                            .getAsString())
                                            .equals("computer")
                                    ? "npships_id"
                                    : "npships_peripheral";
                    snippets.add(fn + "(\"" + ref.getKey() + "\")");
                }
            var dock = panel.layout.codeDock(panel.cursor, 0, panel.layout.codeDockHeight());
            var editor =
                    new LuaEditBox(panel.mc.font, dock.left(), dock.top(), dock.width(), snippets);
            editor.setValue(acceptedInitial);
            editor.setValueListener(
                    v -> {
                        panel.host.validation.changed(editor);
                        code[0] = v;
                        panel.settingsDirty = true;
                    });

            panel.host.validation.register(editor, panel.sections.currentId());
            panel.widgets.add(editor);
            editor.setHeight(
                    editor.compactHeight(
                            panel.layout.contentWidth(), panel.layout.codeDockHeight()));
            panel.cursor += editor.getHeight() + 4;
            panel.codeControls(editor);
            panel.pendingSettings =
                    () -> {
                        var updated = currentComputerPatch(patch, existed);
                        sameFile(updated.getAsJsonObject("files"), path, expected);
                        panel.validateField(
                                nameField,
                                () -> {
                                    if (!name[0].equals(path)
                                            && updated.getAsJsonObject("files").has(name[0]))
                                        throw invalid(
                                                "file_exists", "File already exists.", name[0]);
                                    // Use the native filename validator, including the reserved
                                    // module
                                    // name.
                                    var filename = new JsonObject();
                                    filename.addProperty(name[0], "");
                                    EditorErrorText.computerPath(
                                            () ->
                                                    com.eynny.npships.integration.computercraft
                                                            .ComputerPatch.validate(filename),
                                            name[0]);
                                });
                        var files = updated.getAsJsonObject("files");
                        boolean unchangedCode = code[0].equals(acceptedInitial);
                        if (unchangedCode && name[0].equals(path) && files.has(path)) return;
                        // Renaming a file does not rewrite the bytes of its normalized text view.
                        String encoded =
                                unchangedCode && files.has(path)
                                        ? files.get(path).getAsString()
                                        : Base64.getEncoder()
                                                .encodeToString(
                                                        code[0].getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                        files.remove(path);
                        files.addProperty(name[0], encoded);
                        panel.validateField(
                                editor,
                                () ->
                                        panel.putPatch(
                                                com.eynny.npships.integration.computercraft
                                                        .ComputerNetwork.usedReferences(
                                                        new com.eynny.npships.integration
                                                                        .computercraft
                                                                        .ComputerNetwork(
                                                                        panel.doc())
                                                                .withReferences(updated))));
                    };
            panel.sections.end();
            panel.cursor += 8;
            panel.sections.begin("computer.references", "code_devices", true);
            for (var builtin : network.builtins(panel.pos()).entrySet()) {
                var button =
                        EditorButton.flat(
                                        Component.literal(
                                                builtin.getValue()
                                                        + " / "
                                                        + panel.tr("cc_route_" + builtin.getKey())
                                                                .getString()),
                                        ignored ->
                                                editor.insert(
                                                        "peripheral.wrap(\""
                                                                + builtin.getKey()
                                                                + "\")"))
                                .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                                .build();
                panel.widgets.add(button);
                panel.cursor += 24;
            }
            if (patch.has("references"))
                for (var ref : patch.getAsJsonObject("references").entrySet()) {
                    var target =
                            panel.pos()
                                    .offset(
                                            SchematicPatches.position(
                                                    ref.getValue()
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("offset")));
                    var device = network.referenceDevice(panel.pos(), target);
                    if (device.isEmpty()) continue;
                    String fn =
                            PatchDevices.kind(
                                                    ref.getValue()
                                                            .getAsJsonObject()
                                                            .get("block")
                                                            .getAsString())
                                            .equals("computer")
                                    ? "npships_id"
                                    : "npships_peripheral";
                    var button =
                            EditorButton.flat(
                                            Component.literal(
                                                    device.get().type()
                                                            + " / "
                                                            + panel.tr(
                                                                            "cc_route_"
                                                                                    + device.get()
                                                                                            .route())
                                                                    .getString()
                                                            + " / "
                                                            + ref.getKey()),
                                            ignored -> {
                                                String value = fn + "(\"" + ref.getKey() + "\")";
                                                editor.insert(
                                                        fn.equals("npships_peripheral")
                                                                ? "peripheral.wrap(" + value + ")"
                                                                : value);
                                                panel.settingsDirty = true;
                                            })
                                    .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                                    .build();
                    panel.widgets.add(button);
                    button.setTooltip(
                            Tooltip.create(
                                    Component.translatable(
                                            "npships.tools.cc_insert_device",
                                            target.toShortString(),
                                            fn + "(\"" + ref.getKey() + "\")")));
                    panel.cursor += 24;
                }
            panel.sections.end();
            panel.contentHeight = panel.cursor - panel.layout.inspectorContent().top();
            panel.captureArrangement();
            panel.layoutOnly(panel.layout);
        }
    }
}
