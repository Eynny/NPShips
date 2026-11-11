package com.eynny.npships.client.editor;

import com.eynny.npships.api.client.patches.PatchPresentation;
import com.eynny.npships.api.client.patches.PatchPresentations;
import com.eynny.npships.api.patches.PatchTarget;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.PayloadEvolution;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.eynny.npships.client.editor.widget.EditorEditBox;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-panel detached draft. Reflow and focus never commit. No document is passed to providers. */
final class ExternalPatchControls {
    private final EditorToolsPanel panel;
    private SchematicDocument source;
    private BlockPos position;
    private int entityIndex = -1;
    private JsonObject entry;
    private boolean upgraded;
    private final List<PatchPresentation.Field> fields = new ArrayList<>();
    private final List<DraftBox> inputs = new ArrayList<>();

    ExternalPatchControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void clear() {
        source = null;
        position = null;
        entityIndex = -1;
        entry = null;
        upgraded = false;
        fields.clear();
        inputs.clear();
    }

    private boolean current() {
        return source == panel.doc()
                && (entityIndex >= 0
                        ? panel.entityPatchTarget() && panel.entityIndex == entityIndex
                        : Objects.equals(position, panel.pos())
                                && panel.tool == EditorToolsPanel.Tool.PATCHES);
    }

    boolean pending() {
        if (source != null && !current()) {
            clear();
            panel.host.status(panel.tr("patch_external_stale").getString());
        }
        return source != null;
    }

    private boolean entities() {
        return panel.entityPatchTarget();
    }

    private PatchTarget target(SchematicDocument doc, JsonObject entry) {
        if (!entities()) return ExternalPatches.authoringTarget(doc, entry);
        String unavailable = EntityPatches.unavailable(entry);
        if (!unavailable.isEmpty()) throw new IllegalArgumentException(unavailable);
        return EntityPatches.target(doc, panel.entityIndex);
    }

    private SchematicDocument put(SchematicDocument doc, JsonObject entry) {
        return entities()
                ? EntityPatches.put(doc, panel.entityIndex, entry)
                : ExternalPatches.putAuthoring(doc, entry);
    }

    void renderEntity() {
        var token =
                EntityIdentity.read(
                        panel.doc()
                                .entitiesCopy()
                                .getCompound(panel.entityIndex)
                                .getCompound("nbt"));
        var saved =
                EntityPatches.entries(panel.doc()).stream()
                        .filter(e -> EntityPatches.token(e).equals(token))
                        .toList();
        if (pending()) {
            form();
            return;
        }
        panel.sections.begin("entity.patch", "section_entity_patch", true);
        int before = panel.widgets.size();
        for (var patch : saved) render(patch);
        render(null);
        if (panel.widgets.size() == before) panel.wrappedNote("patch_external_no_controls");
        panel.sections.end();
    }

    boolean render(JsonObject saved) {
        if (pending()) {
            form();
            return true;
        }
        var document = panel.doc();
        int selectedEntity = entities() ? panel.entityIndex : -1;
        if (saved != null) {
            var id = ResourceLocation.parse(saved.get("kind").getAsString());
            var presentation = PatchPresentations.registered().get(id);
            var type = PatchTypes.type(id);
            boolean historical =
                    type != null && saved.get("payload_version").getAsInt() != type.version();
            if (historical
                    && PayloadEvolution.readableTarget(id, saved.get("payload_version").getAsInt()))
                panel.action(
                        "patch_external_upgrade",
                        () -> open(document, saved, presentation, false, selectedEntity, true));
            if (presentation == null) panel.wrappedNote("patch_external_data_only");
            if (presentation != null && !historical) {
                try {
                    var target = target(panel.doc(), saved);
                    if (presentation.supports().test(target))
                        panel.button(
                                panel.tr("patch_external_edit").getString()
                                        + ": "
                                        + presentation.title().getString(),
                                () -> open(document, saved, presentation, false, selectedEntity));
                } catch (RuntimeException | LinkageError error) {
                    unavailable(error);
                }
            }
            if (saved.getAsJsonObject("payload").toString().length() <= 32767)
                panel.action(
                        "patch_external_json",
                        () -> open(document, saved, null, false, selectedEntity));
            else panel.wrappedNote("patch_external_large");
            panel.action(
                    "delete_patch",
                    () -> {
                        if (document != panel.doc()
                                || (selectedEntity >= 0
                                        ? !panel.entityPatchTarget()
                                                || panel.entityIndex != selectedEntity
                                        : panel.tool != EditorToolsPanel.Tool.PATCHES
                                                || !Objects.equals(
                                                        panel.pos(),
                                                        SchematicPatches.position(
                                                                saved.getAsJsonArray("pos")))))
                            throw new IllegalStateException(
                                    panel.tr("patch_external_stale").getString());
                        panel.commit(
                                selectedEntity >= 0
                                        ? removeEntity(document, selectedEntity, saved)
                                        : ExternalPatches.removeAuthoring(document, saved));
                    });
            return true;
        }
        var presentations = PatchPresentations.registered();
        if (presentations.isEmpty()) return false;
        var savedEntries =
                selectedEntity >= 0
                        ? EntityPatches.entries(document)
                        : ExternalPatches.authoringEntries(document);
        var selectedToken =
                selectedEntity >= 0
                        ? EntityIdentity.read(
                                document.entitiesCopy()
                                        .getCompound(selectedEntity)
                                        .getCompound("nbt"))
                        : null;
        // This inspector rebuild shares one detached snapshot across provider availability checks.
        for (var registration : presentations.entrySet()) {
            var metadata = PatchTypes.registration(registration.getKey());
            boolean exists =
                    savedEntries.stream()
                            .anyMatch(
                                    e ->
                                            e.get("kind")
                                                            .getAsString()
                                                            .equals(
                                                                    registration
                                                                            .getKey()
                                                                            .toString())
                                                    && (selectedEntity >= 0
                                                            ? EntityPatches.token(e)
                                                                    .equals(selectedToken)
                                                            : SchematicPatches.position(
                                                                            e.getAsJsonArray("pos"))
                                                                    .equals(panel.pos())));
            if (exists
                    && metadata.multiplicity()
                            == com.eynny.npships.api.patches.PatchRegistration.Multiplicity
                                    .SINGLE_PER_TARGET) continue;
            // A native target mismatch is an ordinary unavailable action; no presentation callback
            // is called when common dependency/native target validation fails.
            JsonObject candidate;
            try {
                candidate =
                        entities()
                                ? EntityPatches.authoringEntry(
                                        panel.doc(), panel.entityIndex, registration.getKey())
                                : ExternalPatches.authoringEntry(
                                        panel.doc(), panel.pos(), registration.getKey());
            } catch (IllegalArgumentException error) {
                continue;
            }
            try {
                var presentation = registration.getValue();
                if (presentation.supports().test(target(panel.doc(), candidate)))
                    panel.button(
                            panel.tr("patch_external_create").getString()
                                    + ": "
                                    + presentation.title().getString(),
                            () -> open(document, candidate, presentation, true, selectedEntity));
            } catch (RuntimeException | LinkageError error) {
                unavailable(error);
            }
        }
        return false;
    }

    private SchematicDocument removeEntity(SchematicDocument doc, int index, JsonObject selected) {
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        var entries = new ArrayList<>(EntityPatches.entries(doc));
        int schema = EntityPatches.schema(doc);
        var identity = PatchInstances.identity(selected, true, schema);
        if (!entries.removeIf(e -> PatchInstances.identity(e, true, schema).equals(identity)))
            throw new IllegalArgumentException("Patch selection is stale: " + identity);
        EntityPatches.set(config, entries);
        var next = doc.withConfig(config);
        EntityPatches.validate(next, false);
        return next;
    }

    private void unavailable(Throwable error) {
        var button = panel.button(panel.tr("patch_external_unavailable").getString(), () -> {});
        button.active = false;
        String detail = EditorErrorText.message(error);
        button.setTooltip(
                Tooltip.create(
                        Component.literal(detail.substring(0, Math.min(768, detail.length())))));
        // No per-frame callbacks/logging: this runs only when the existing inspector builds.
    }

    private void open(
            SchematicDocument document,
            JsonObject original,
            PatchPresentation presentation,
            boolean create,
            int selectedEntity) {
        open(document, original, presentation, create, selectedEntity, false);
    }

    private void open(
            SchematicDocument document,
            JsonObject original,
            PatchPresentation presentation,
            boolean create,
            int selectedEntity,
            boolean upgrade) {
        var selected = panel.pos();
        if (document != panel.doc()
                || (selectedEntity >= 0
                        ? !panel.entityPatchTarget() || panel.entityIndex != selectedEntity
                        : panel.tool != EditorToolsPanel.Tool.PATCHES
                                || !Objects.equals(
                                        selected,
                                        SchematicPatches.position(
                                                original.getAsJsonArray("pos"))))) {
            panel.host.error(panel.tr("patch_external_stale").getString());
            return;
        }
        var copy = original.deepCopy();
        if (create) copy = PatchInstances.newInstance(copy);
        List<PatchPresentation.Field> descriptors;
        try {
            // Missing native dependencies must refuse before invoking provider conversion code.
            var upgradeTarget = upgrade ? target(document, copy) : null;
            if (upgrade) copy = ExternalPatches.upgradePayload(copy, selectedEntity >= 0);
            if (upgrade && presentation == null) {
                // An explicit version upgrade is still useful without a client form. Apply owns
                // the accepted edit; merely opening or cancelling this private draft changes none.
                descriptors = List.of();
            } else if (presentation == null) {
                descriptors =
                        List.of(
                                new PatchPresentation.Field(
                                        panel.tr("patch_external_payload"),
                                        panel.tr("patch_external_json_hint"),
                                        copy.getAsJsonObject("payload").toString(),
                                        32767,
                                        (payload, text) -> {
                                            var parsed =
                                                    JsonStructure.parse(text).getAsJsonObject();
                                            payload.entrySet().clear();
                                            parsed.entrySet()
                                                    .forEach(
                                                            e ->
                                                                    payload.add(
                                                                            e.getKey(),
                                                                            e.getValue()
                                                                                    .deepCopy()));
                                        }));
            } else {
                var target = upgradeTarget == null ? target(document, copy) : upgradeTarget;
                if (!presentation.supports().test(target))
                    throw new IllegalArgumentException("Unsupported target");
                if (create) {
                    copy.add(
                            "payload",
                            JsonStructure.copy(
                                    Objects.requireNonNull(
                                            presentation.createPayload().apply(target))));
                    // A create action may only start with a common-valid default instance.
                    if (!entities()) ExternalPatches.putAuthoring(document, copy);
                    else
                        PatchTypes.type(ResourceLocation.parse(copy.get("kind").getAsString()))
                                .validator()
                                .validate(target, copy.getAsJsonObject("payload").deepCopy());
                }
                descriptors =
                        List.copyOf(
                                presentation
                                        .fields()
                                        .apply(target, copy.getAsJsonObject("payload").deepCopy()));
            }
            if (descriptors.size() > PatchPresentation.MAX_FIELDS)
                throw new IllegalArgumentException("Patch presentation exceeds 32 fields");
        } catch (RuntimeException | LinkageError error) {
            panel.host.error(
                    panel.tr("patch_external_unavailable").getString()
                            + ": "
                            + EditorErrorText.message(error));
            return;
        }
        clear();
        source = document;
        position = selected == null ? null : selected.immutable();
        entityIndex = selectedEntity;
        entry = copy;
        upgraded = upgrade;
        fields.addAll(descriptors);
        for (var field : fields) inputs.add(new DraftBox(field));
        panel.build();
    }

    private void form() {
        panel.sections.begin("external.draft", "patch_external_draft", true);
        panel.wrappedNote("patch_external_draft_hint");
        if (upgraded) panel.wrappedNote("patch_external_upgrade_hint");
        for (int i = 0; i < fields.size(); i++) {
            var box = inputs.get(i);
            var label = fields.get(i).label();
            int height = Math.max(12, panel.mc.font.wordWrapHeight(label, panel.contentWidth()));
            panel.widgets.add(
                    new MultiLineTextWidget(panel.x, panel.cursor, label, panel.mc.font)
                            .setMaxWidth(panel.contentWidth()));
            panel.cursor += height + 2;
            box.setPosition(panel.x, panel.cursor);
            box.setWidth(panel.contentWidth());
            panel.widgets.add(box);
            panel.cursor += 24;
        }
        var draft = entry;
        panel.action(
                "patch_external_apply",
                () -> {
                    if (entry == draft) apply();
                });
        panel.action(
                "cancel",
                () -> {
                    if (entry == draft) {
                        clear();
                        panel.build();
                    }
                });
        panel.sections.end();
    }

    private void apply() {
        if (!pending())
            throw new IllegalStateException(panel.tr("patch_external_stale").getString());
        var next = entry.deepCopy();
        var payload = next.getAsJsonObject("payload");
        try {
            for (int i = 0; i < fields.size(); i++)
                fields.get(i).write().accept(payload, inputs.get(i).getValue());
            var replacement = put(source, next);
            clear();
            panel.commit(replacement);
            panel.build();
        } catch (RuntimeException | LinkageError error) {
            panel.host.error(
                    panel.tr("patch_external_invalid").getString()
                            + ": "
                            + EditorErrorText.message(error));
        }
    }

    /** The native field's shared local history is independent of accepted document history. */
    private final class DraftBox extends EditorEditBox {
        DraftBox(PatchPresentation.Field field) {
            super(panel.mc.font, 0, 0, panel.contentWidth(), 20, field.label());
            setMaxLength(field.maxLength());
            setValue(field.initial());
            setTooltip(Tooltip.create(field.hint()));
        }
    }
}
