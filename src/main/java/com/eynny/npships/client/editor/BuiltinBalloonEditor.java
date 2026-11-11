package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.concurrent.LatestTask;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Native detached gas/group calculations owned by the registered lifting-gas editor session. */
final class BuiltinBalloonEditor implements PatchEditorParticipant {
    private final EditorToolsPanel panel;
    private long generation;

    BuiltinBalloonEditor(EditorToolsPanel panel) {
        this.panel = panel;
    }

    private final Map<BlockPos, JsonObject> inheritedBalloonPatches = new HashMap<>();
    private SchematicDocument balloonGroupSource;
    private Map<BlockPos, BlockPos> balloonGroups = Map.of();
    final LatestTask<Map<BlockPos, BlockPos>> balloonGroupJobs =
            new LatestTask<>(net.minecraft.Util.backgroundExecutor());

    void ensureBalloonGroups() {
        if (balloonGroupSource == panel.doc()) return;
        var changed = panel.doc().changedBlocks(balloonGroupSource, 256);
        boolean patchOnly = changed != null && balloonGroupSource != null;
        if (patchOnly) {
            var palette = panel.doc().paletteCopy();
            var previous = balloonGroupSource.paletteCopy();
            for (var p : changed) {
                var now = panel.doc().blocks().get(p);
                var before = balloonGroupSource.blocks().get(p);
                if (now == null
                        || before == null
                        || !palette.getCompound(now).equals(previous.getCompound(before))
                        || !panel.doc()
                                .blockEntityData(p)
                                .equals(balloonGroupSource.blockEntityData(p))) {
                    patchOnly = false;
                    break;
                }
            }
        }
        if (patchOnly) {
            balloonGroupSource = panel.doc();
            return;
        }
        for (var patch : SchematicPatches.read(panel.doc().configCopy()))
            if (patch.get("kind").getAsString().equals("balloon")) {
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                var group = balloonGroups.get(owner);
                if (group != null)
                    balloonGroups.forEach(
                            (p, g) -> {
                                if (g.equals(group)
                                        && com.eynny.npships.integration.aeronautics
                                                .AeronauticsPatch.burner(panel.doc().blockName(p)))
                                    inheritedBalloonPatches.put(p, patch.deepCopy());
                            });
            }
        balloonGroupSource = panel.doc();
        balloonGroups = Map.of();
        generation++;
        balloonGroupJobs.discard();
        var source = panel.doc();
        var palette = source.paletteCopy();
        boolean[] providers = new boolean[palette.size()];
        for (int i = 0; i < providers.length; i++)
            providers[i] =
                    com.eynny.npships.integration.aeronautics.AeronauticsPatch.burner(
                            palette.getCompound(i).getString("Name"));
        var sources =
                source.blocks().entrySet().stream()
                        .filter(e -> providers[e.getValue()])
                        .map(Map.Entry::getKey)
                        .toList();
        if (sources.isEmpty()) return;
        var view = new SchematicPreviewLevel(source, panel.mc.level);
        var casts = new HashMap<BlockPos, BlockPos>();
        for (var p : sources) {
            try {
                casts.put(
                        p,
                        com.eynny.npships.integration.aeronautics.BalloonCapacity.cast(
                                source, view, p));
            } catch (RuntimeException error) {
                casts.put(p, null);
                panel.host.status(EditorErrorText.message(error));
            }
        }
        if (!casts.isEmpty())
            balloonGroupJobs.submit(
                    () ->
                            com.eynny.npships.integration.aeronautics.BalloonCapacity.groups(
                                    source, view, casts));
    }

    BlockPos balloonGroup(BlockPos p) {
        return balloonGroups.getOrDefault(p, p);
    }

    boolean sameBalloon(BlockPos a, BlockPos b) {
        return balloonGroups.containsKey(a) && balloonGroup(a).equals(balloonGroup(b));
    }

    SchematicDocument capacitySource;
    BlockPos capacityOwner;
    final LatestTask<Integer> capacityJobs =
            new LatestTask<>(net.minecraft.Util.backgroundExecutor());
    Integer capacityValue;
    boolean capacityFailed;

    @Override
    public void refresh() {
        ensureBalloonGroups();
    }

    @Override
    public void poll() {
        // Invalidate superseded geometry before a completed worker may publish its result.
        refresh();
        if (capacitySource != panel.doc() || !Objects.equals(capacityOwner, panel.pos())) {
            capacityJobs.discard();
            capacityValue = null;
            capacityFailed = false;
        }
        balloonGroupJobs.poll(
                value -> {
                    balloonGroups = value;
                    generation++;
                    var next = panel.doc();
                    var configured = new HashSet<BlockPos>();
                    var accepted = new HashSet<JsonObject>();
                    for (var patch : SchematicPatches.read(next.configCopy()))
                        if (patch.get("kind").getAsString().equals("balloon")) {
                            configured.add(
                                    balloonGroup(
                                            SchematicPatches.position(
                                                    patch.getAsJsonArray("pos"))));
                            accepted.add(patch);
                        }
                    for (var entry : inheritedBalloonPatches.entrySet()) {
                        var group = value.get(entry.getKey());
                        // Deletion or a newer payload edit revokes the captured inheritance.
                        if (!accepted.contains(entry.getValue())
                                || group == null
                                || !configured.add(group)) continue;
                        var patch = PatchDevices.copy(next, entry.getValue(), entry.getKey());
                        patch.addProperty("block", next.blockName(entry.getKey()));
                        next = PatchDevices.put(next, patch);
                    }
                    inheritedBalloonPatches.clear();
                    if (next != panel.doc()) panel.commit(next);
                    panel.cachePatchHighlights();
                    panel.rebuild = true;
                },
                error -> {
                    panel.host.status(EditorErrorText.message(error));
                    panel.rebuild = true;
                });
        capacityJobs.poll(
                value -> {
                    capacityValue = value;
                    capacityFailed = false;
                },
                error -> {
                    capacityValue = null;
                    capacityFailed = true;
                });
    }

    @Override
    public long revision() {
        return generation;
    }

    @Override
    public BlockPos representative(BlockPos target) {
        return balloonGroup(target);
    }

    @Override
    public boolean selects(JsonObject record, BlockPos selection) {
        return record.get("kind").getAsString().equals("balloon")
                && sameBalloon(SchematicPatches.position(record.getAsJsonArray("pos")), selection);
    }

    @Override
    public boolean blocksCreation(BlockPos target) {
        return com.eynny.npships.integration.aeronautics.AeronauticsPatch.burner(
                        panel.doc().blockName(target))
                && balloonGroupJobs.busy();
    }

    @Override
    public void highlights(Map<BlockPos, Boolean> result) {
        var marked = new HashSet<BlockPos>();
        for (var record : SchematicPatches.read(panel.doc().configCopy()))
            if (record.get("kind").getAsString().equals("balloon"))
                marked.add(balloonGroup(SchematicPatches.position(record.getAsJsonArray("pos"))));
        balloonGroups.forEach(
                (position, group) -> {
                    if (marked.contains(group)) result.put(position, true);
                });
    }

    @Override
    public SchematicDocument prepareEdit(SchematicDocument source, JsonObject record) {
        if (!record.get("kind").getAsString().equals("balloon")) return source;
        var owner = SchematicPatches.position(record.getAsJsonArray("pos"));
        var result = source;
        for (var other : SchematicPatches.read(source.configCopy())) {
            var position = SchematicPatches.position(other.getAsJsonArray("pos"));
            if (other.get("kind").getAsString().equals("balloon")
                    && !position.equals(owner)
                    && sameBalloon(owner, position)) {
                result =
                        com.eynny.npships.authoring.patches.ExternalPatches.removeAuthoring(
                                result, other);
                var block = result.blockEntryCopy(position);
                block.putBoolean(SchematicPatches.UNPATCHED, true);
                result =
                        result.withBlocks(
                                Map.of(position, block), result.paletteCopy(), result.configCopy());
            }
        }
        return result;
    }

    @Override
    public void label() {
        if (panel.pos() != null
                && com.eynny.npships.integration.aeronautics.AeronauticsPatch.burner(
                        panel.doc().blockName(panel.pos()))) capacityLabel();
    }

    void capacityLabel() {
        if (capacitySource != panel.doc() || !Objects.equals(capacityOwner, panel.pos())) {
            capacitySource = panel.doc();
            capacityOwner = panel.pos();
            var source = panel.doc();
            var view = new SchematicPreviewLevel(source, panel.mc.level);
            var cast =
                    com.eynny.npships.integration.aeronautics.BalloonCapacity.cast(
                            source, view, panel.pos());
            capacityValue = null;
            capacityFailed = false;
            capacityJobs.submit(
                    () ->
                            com.eynny.npships.integration.aeronautics.BalloonCapacity.calculate(
                                    source, view, cast));
        }
        var label =
                new StringWidget(
                        panel.x,
                        panel.cursor,
                        panel.contentWidth(),
                        26,
                        Component.empty(),
                        panel.mc.font) {
                    @Override
                    public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
                        Component text;
                        try {
                            text =
                                    capacityValue != null
                                            ? Component.translatable(
                                                    "npships.tools.balloon_capacity", capacityValue)
                                            : panel.tr(
                                                    capacityFailed
                                                            ? "balloon_capacity_unknown"
                                                            : "balloon_capacity_busy");
                        } catch (Exception e) {
                            text = panel.tr("balloon_capacity_unknown");
                        }
                        g.drawWordWrap(panel.mc.font, text, getX(), getY(), getWidth(), 0x9fded0);
                    }
                };
        panel.widgets.add(label);
        panel.cursor += 30;
    }

    @Override
    public void close() {
        balloonGroupJobs.discard();
        capacityJobs.discard();
        inheritedBalloonPatches.clear();
        balloonGroupSource = null;
        capacitySource = null;
        balloonGroups = Map.of();
    }
}
