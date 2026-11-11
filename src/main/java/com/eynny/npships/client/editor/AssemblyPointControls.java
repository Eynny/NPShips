package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.AssemblyPoints;
import com.eynny.npships.client.editor.render.SceneOutlines;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Selection only. Marker data and drag previews use the Screen's existing document/history owner.
 */
final class AssemblyPointControls {
    final EditorToolsPanel panel;
    UUID selected;
    private com.google.gson.JsonObject clipboard;
    private com.eynny.npships.authoring.document.SchematicDocument source;
    private java.util.List<AssemblyPoints.Point> cached = java.util.List.of();
    private java.util.List<AssemblyPoints.Point> empty = java.util.List.of();

    private java.util.List<AssemblyPoints.Point> points() {
        if (panel.host.isPart()) return java.util.List.of();
        var current = panel.doc();
        if (source != current) {
            cached = current.assemblyPoints();
            empty = cached.stream().filter(p -> AssemblyPoints.empty(current, p)).toList();
            source = current;
        }
        return cached;
    }

    AssemblyPointControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    AssemblyPoints.Point point() {
        var current =
                points().stream().filter(p -> p.id().equals(selected)).findFirst().orElse(null);
        if (current == null) selected = null;
        return current;
    }

    AABB bounds() {
        var p = point();
        return p == null ? null : new AABB(p.position());
    }

    void controls() {
        if (panel.host.isPart()) return;
        var selectedPoint = point();
        panel.wrappedNote("assembly_points_hint");
        if (selectedPoint != null && AssemblyPoints.empty(panel.doc(), selectedPoint))
            panel.wrappedNote("assembly_point_empty");
        for (var p : points()) {
            var button =
                    panel.buttonAt(
                            net.minecraft.network.chat.Component.literal(
                                    p.position().toShortString()),
                            panel.x,
                            panel.cursor,
                            panel.w,
                            () -> {
                                selected = p.id();
                                panel.rebuild = true;
                            });
            panel.cursor += EditorLayout.ROW_PITCH;
            button.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            panel.tr(
                                    AssemblyPoints.empty(panel.doc(), p)
                                            ? "assembly_point_empty"
                                            : "assembly_point_select")));
        }
    }

    boolean keyPressed(int key, int modifiers) {
        if (panel.host.isPart()) return false;
        boolean control =
                com.eynny.npships.client.editor.EditorKeyboardInput.control(modifiers)
                        || Screen.hasControlDown();
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
            panel.run(this::remove);
            return true;
        }
        if (!control) return false;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C || key == org.lwjgl.glfw.GLFW.GLFW_KEY_X) {
            var point = point();
            if (point == null) return true;
            clipboard =
                    panel.doc().configCopy().getAsJsonArray(AssemblyPoints.KEY).asList().stream()
                            .map(com.google.gson.JsonElement::getAsJsonObject)
                            .filter(e -> e.get("id").getAsString().equals(point.id().toString()))
                            .findFirst()
                            .orElseThrow()
                            .deepCopy();
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_X) panel.run(this::remove);
            panel.host.clearObjectSelection();
            panel.host.setCreateMode();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_V) {
            if (clipboard != null) {
                panel.host.clearObjectSelection();
                panel.host.setCreateMode();
            }
            return true;
        }
        return false;
    }

    private com.eynny.npships.authoring.document.SchematicDocument create(BlockPos position) {
        return clipboard == null
                ? AssemblyPoints.put(panel.doc(), null, position)
                : AssemblyPoints.copy(panel.doc(), clipboard, position);
    }

    void remove() {
        if (panel.host.isPart()) return;
        if (selected == null) return;
        var next = AssemblyPoints.put(panel.doc(), selected, null);
        selected = null;
        panel.commit(next);
        panel.rebuild = true;
    }

    boolean visible() {
        return !panel.host.isPart()
                && (panel.tool == EditorToolsPanel.Tool.ASSEMBLY || panel.host.allZones());
    }

    AssemblyPoints.Point hit(double x, double y, Matrix4f inverse, float depth) {
        if (inverse == null || !visible()) return null;
        return hit(x, y, inverse, depth, points(), true);
    }

    net.minecraft.network.chat.Component warningAt(
            double x, double y, Matrix4f inverse, float depth) {
        if (panel.host.isPart() || !panel.host.showsWarnings() || inverse == null) return null;
        points();
        return hit(x, y, inverse, depth, empty, false) == null
                ? null
                : panel.tr("assembly_point_empty");
    }

    private AssemblyPoints.Point hit(
            double x,
            double y,
            Matrix4f inverse,
            float depth,
            java.util.List<AssemblyPoints.Point> candidates,
            boolean interaction) {
        var ray = panel.ray(x, y, inverse, depth);
        AssemblyPoints.Point result = null;
        double best = Double.MAX_VALUE;
        for (var p : candidates) {
            if (!panel.host.visible(p.position())) continue;
            if (interaction && !panel.host.interactionAllowed(new AABB(p.position()).getCenter()))
                continue;
            var hit =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                            new AABB(p.position()), ray[0], ray[1]);
            if (hit.isPresent() && hit.get().distanceToSqr(ray[0]) < best) {
                result = p;
                best = hit.get().distanceToSqr(ray[0]);
            }
        }
        return result;
    }

    void click(double x, double y, Matrix4f inverse, float depth) {
        if (panel.host.isPart()) return;
        var p = hit(x, y, inverse, depth);
        if (p != null) {
            UUID next =
                    p.id().equals(selected) && panel.tool == EditorToolsPanel.Tool.ASSEMBLY
                            ? null
                            : p.id();
            if (panel.tool != EditorToolsPanel.Tool.ASSEMBLY)
                panel.change(EditorToolsPanel.Tool.ASSEMBLY);
            selected = next;
            if (panel.host.deleting()) {
                selected = p.id();
                panel.run(this::remove);
            }
            panel.rebuild = true;
        } else if (panel.host.creating()) {
            var at = panel.host.pick(x, y);
            if (at != null && panel.doc().contains(at))
                panel.run(
                        () -> {
                            var next = create(at);
                            selected = AssemblyPoints.read(next.configCopy()).getLast().id();
                            panel.commit(next);
                            panel.rebuild = true;
                        });
        } else {
            selected = null;
            panel.rebuild = true;
        }
    }

    void render(GuiGraphics graphics, PoseStack pose) {
        if (panel.host.isPart()) return;
        var points = points();
        if (visible())
            for (var p : points)
                if (panel.host.visible(p.position()))
                    SceneOutlines.markerBox(
                            graphics,
                            pose,
                            new AABB(p.position()).deflate(.03),
                            p.id().equals(selected) ? 0xff8a50 : 0xff5a24,
                            false);
        if (panel.host.showsWarnings())
            for (var p : empty)
                if (panel.host.visible(p.position()))
                    SceneOutlines.patch(graphics, pose, new AABB(p.position()), 0xff3030);
    }
}
