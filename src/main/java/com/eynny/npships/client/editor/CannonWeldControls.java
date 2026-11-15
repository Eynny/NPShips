package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.geometry.SchematicViewProjection;
import com.eynny.npships.client.editor.render.SceneOutlines;
import com.eynny.npships.integration.cannons.CannonWelding;
import com.eynny.npships.integration.cannons.CannonWelds;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Spatial selection only. Accepted edges still cross the native CBC validation boundary. */
final class CannonWeldControls implements PatchSpatialTool {
    private final EditorToolsPanel panel;
    private long generation;
    private SchematicDocument source;
    private BlockPos from;
    private boolean choosing, tooManyPairs, rejectedDrag;
    private AABB extent, dragStart;
    private Direction dragFace;
    private double startX, startY, axisX, axisY;
    private final Map<CannonWelds.Pair, String> candidates = new LinkedHashMap<>();
    private final Set<CannonWelds.Pair> selected = new LinkedHashSet<>();

    CannonWeldControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    public void clear() {
        generation++;
        source = null;
        from = null;
        choosing = false;
        tooManyPairs = rejectedDrag = false;
        extent = dragStart = null;
        dragFace = null;
        candidates.clear();
        selected.clear();
    }

    public net.minecraft.network.chat.Component pendingHint() {
        return panel.tr("cbc_weld_finish");
    }

    public boolean pending() {
        return choosing() && !selected.isEmpty();
    }

    public boolean choosing() {
        return choosing && current();
    }

    public boolean supported() {
        return current();
    }

    public AABB extent() {
        return extent;
    }

    private boolean current() {
        return source != null
                && source == panel.doc()
                && Objects.equals(from, panel.pos())
                && panel.tool == EditorToolsPanel.Tool.PATCHES
                && panel.entityIndex < 0;
    }

    private boolean supports(SchematicDocument doc, BlockPos p) {
        return supports(doc, doc.paletteCopy(), p);
    }

    private boolean supports(SchematicDocument doc, ListTag palette, BlockPos p) {
        var index = doc.blocks().get(p);
        return index != null
                && CannonWelding.supports(
                        NbtUtils.readBlockState(
                                panel.mc.level.holderLookup(Registries.BLOCK),
                                palette.getCompound(index)));
    }

    private boolean prepare() {
        if (current()) return true;
        clear();
        var p = panel.pos();
        if (p == null
                || !panel.doc().blocks().containsKey(p)
                || !CannonWelding.available()
                || !supports(panel.doc(), p)) return false;
        source = panel.doc();
        from = p;
        extent = new AABB(p);
        return true;
    }

    public void cancel() {
        generation++;
        choosing = false;
        dragFace = null;
        selected.clear();
        candidates.clear();
        if (from != null) extent = new AABB(from);
        panel.rebuild = true;
    }

    public void cancelGesture() {
        dragFace = null;
    }

    public boolean dragging() {
        return choosing() && dragFace != null;
    }

    private boolean same(SchematicDocument doc, BlockPos owner, long stamp) {
        return current() && source == doc && Objects.equals(from, owner) && generation == stamp;
    }

    public boolean render() {
        if (!prepare()) return false;
        var doc = source;
        var owner = from;
        long stamp = generation;
        panel.sections.begin("cbc.weld", "cbc_weld_title", true);
        panel.wrappedNote(choosing ? "cbc_weld_region_hint" : "cbc_weld_hint");
        if (!choosing)
            panel.button(
                            "npships.tools.cbc_weld_region",
                            () -> {
                                if (!same(doc, owner, stamp)) return;
                                choosing = true;
                                panel.host.setSelectMode();
                                panel.host.status(panel.tr("cbc_weld_region_hint").getString());
                                panel.build();
                            })
                    .setTooltip(Tooltip.create(panel.tr("cbc_weld_region_help")));
        else {
            if (tooManyPairs) panel.wrappedNote("cbc_weld_region_limit");
            panel.action(
                    "cbc_weld_cancel",
                    () -> {
                        if (!same(doc, owner, stamp)) return;
                        cancel();
                        panel.build();
                    });
            panel.button(
                                    Component.translatable(
                                                    "npships.tools.cbc_weld_commit",
                                                    selected.size())
                                            .getString(),
                                    () -> {
                                        if (same(doc, owner, stamp))
                                            commit(new LinkedHashSet<>(selected));
                                    })
                            .active =
                    !selected.isEmpty();
            for (var entry : candidates.entrySet()) {
                var pair = entry.getKey();
                var failure = entry.getValue();
                var button =
                        panel.button(
                                (!failure.isEmpty()
                                                ? "[!] "
                                                : selected.contains(pair) ? "[x] " : "[ ] ")
                                        + pair.from().toShortString()
                                        + " -> "
                                        + pair.to().toShortString(),
                                () -> {
                                    if (!same(doc, owner, stamp) || !failure.isEmpty()) return;
                                    if (!selected.remove(pair)) selected.add(pair);
                                    panel.build();
                                });
                button.active = failure.isEmpty();
                button.setTooltip(
                        Tooltip.create(
                                failure.isEmpty()
                                        ? Component.translatable(
                                                "npships.tools.cbc_weld_ready",
                                                pair.from().toShortString(),
                                                pair.to().toShortString())
                                        : Component.translatable(
                                                "npships.tools.cbc_weld_refused", failure)));
            }
        }
        for (var link : CannonWelds.links(doc.blockEntityData(owner))) {
            var target = owner.offset(CannonWelds.offset(link));
            panel.button(
                    Component.translatable("npships.tools.cbc_weld_remove", target.toShortString())
                            .getString(),
                    () -> {
                        if (!same(doc, owner, stamp)) return;
                        panel.commit(CannonWelds.remove(doc, owner, target));
                        clear();
                    });
        }
        panel.sections.end();
        return choosing;
    }

    private static Vec3 faceCenter(AABB box, Direction face) {
        var c = box.getCenter();
        double edge =
                face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? box.max(face.getAxis())
                        : box.min(face.getAxis());
        return switch (face.getAxis()) {
            case X -> new Vec3(edge, c.y, c.z);
            case Y -> new Vec3(c.x, edge, c.z);
            case Z -> new Vec3(c.x, c.y, edge);
        };
    }

    public boolean begin(double x, double y, Matrix4f inverse, float depth) {
        if (!choosing() || inverse == null) return false;
        var camera = new Matrix4f(inverse).invert();
        Direction picked = null;
        double best = 64;
        for (var face : Direction.values()) {
            var center = faceCenter(extent, face);
            if (!panel.host.interactionAllowed(center)) continue;
            var p = SchematicViewProjection.projectHandle(camera, center.toVector3f());
            var axis =
                    SchematicViewProjection.direction(
                            camera,
                            center.toVector3f(),
                            Vec3.atLowerCornerOf(face.getNormal()).toVector3f());
            if (!p.isFinite() || !axis.isFinite() || axis.x * axis.x + axis.y * axis.y < .01)
                continue;
            double d = (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y);
            if (d < best) {
                best = d;
                picked = face;
            }
        }
        if (picked == null) {
            var ray = panel.ray(x, y, inverse, depth);
            var hit = SchematicViewProjection.hit(extent, ray[0], ray[1]);
            if (hit.isEmpty() || !panel.host.interactionAllowed(hit.get())) return false;
            double distance = Double.MAX_VALUE;
            for (var face : Direction.values()) {
                double edge =
                        face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                                ? extent.max(face.getAxis())
                                : extent.min(face.getAxis());
                double d = Math.abs(hit.get().get(face.getAxis()) - edge);
                if (d < distance) {
                    distance = d;
                    picked = face;
                }
            }
        }
        if (picked == null) return false;
        var axis =
                SchematicViewProjection.direction(
                        camera,
                        faceCenter(extent, picked).toVector3f(),
                        Vec3.atLowerCornerOf(picked.getNormal()).toVector3f());
        if (!axis.isFinite() || axis.x * axis.x + axis.y * axis.y < .01) return false;
        dragFace = picked;
        dragStart = extent;
        rejectedDrag = false;
        startX = x;
        startY = y;
        axisX = axis.x;
        axisY = axis.y;
        return true;
    }

    public boolean drag(double x, double y) {
        if (!choosing() || dragFace == null) return false;
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            cancelGesture();
            return true;
        }
        double step =
                Math.rint(
                        ((x - startX) * axisX + (y - startY) * axisY)
                                / (axisX * axisX + axisY * axisY));
        var axis = dragFace.getAxis();
        double[] lo = {dragStart.minX, dragStart.minY, dragStart.minZ},
                hi = {dragStart.maxX, dragStart.maxY, dragStart.maxZ};
        int a = axis.ordinal();
        if (dragFace.getAxisDirection() == Direction.AxisDirection.POSITIVE)
            hi[a] =
                    Math.max(
                            from.get(axis) + 1,
                            Math.min(panel.doc().size().get(axis), hi[a] + step));
        else lo[a] = Math.min(from.get(axis), Math.max(0, lo[a] - step));
        var next = new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        if ((hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2]) > 256) {
            rejectedDrag = true;
            panel.host.status(panel.tr("cbc_weld_region_limit").getString());
            return true;
        }
        rejectedDrag = false;
        if (!next.equals(extent)) {
            extent = next;
            selected.clear();
            preflight();
            panel.rebuild = true;
        }
        return true;
    }

    public boolean end() {
        if (dragFace == null) return false;
        boolean changed = current() && !Objects.equals(extent, dragStart);
        dragFace = null;
        if (changed
                && !rejectedDrag
                && !tooManyPairs
                && candidates.size() == 1
                && candidates.values().iterator().next().isEmpty())
            commit(Set.of(candidates.keySet().iterator().next()));
        else if (rejectedDrag || tooManyPairs)
            panel.host.status(panel.tr("cbc_weld_region_limit").getString());
        else if (changed) panel.host.status(panel.tr("cbc_weld_region_review").getString());
        return true;
    }

    private void preflight() {
        generation++;
        candidates.clear();
        tooManyPairs = false;
        // One private snapshot serves this synchronous preflight; native validation stays per pair.
        var palette = source.paletteCopy();
        var proposed = new LinkedHashSet<CannonWelds.Pair>();
        for (var mutable :
                BlockPos.betweenClosed(
                        BlockPos.containing(extent.minX, extent.minY, extent.minZ),
                        BlockPos.containing(extent.maxX - 1, extent.maxY - 1, extent.maxZ - 1))) {
            var p = mutable.immutable();
            if (!supports(source, palette, p)) continue;
            for (var side : Direction.values()) {
                var q = p.relative(side);
                if (extent.contains(Vec3.atCenterOf(q)))
                    proposed.add(
                            p.compareTo(q) < 0
                                    ? new CannonWelds.Pair(p, q)
                                    : new CannonWelds.Pair(q, p));
            }
        }
        if (proposed.size() > 128) {
            tooManyPairs = true;
            panel.host.status(panel.tr("cbc_weld_region_limit").getString());
            return;
        }
        for (var pair : proposed) {
            String failure = "";
            try {
                if (CannonWelds.links(source.blockEntityData(pair.from())).stream()
                        .anyMatch(
                                link ->
                                        pair.from()
                                                .offset(CannonWelds.offset(link))
                                                .equals(pair.to())))
                    failure = panel.tr("cbc_weld_exists").getString();
                else
                    CannonWelding.validatePair(
                            source, palette, pair.from(), pair.to(), panel.mc.level);
            } catch (RuntimeException invalid) {
                failure = EditorErrorText.message(invalid);
            }
            candidates.put(pair, failure);
        }
    }

    private void commit(Set<CannonWelds.Pair> accepted) {
        if (!current() || accepted.isEmpty()) return;
        try {
            var result = CannonWelds.addAll(source, accepted, panel.mc.level);
            var message =
                    Component.translatable("npships.tools.cbc_weld_committed", accepted.size());
            clear();
            panel.commit(result);
            panel.host.status(message.getString());
        } catch (RuntimeException invalid) {
            panel.host.error(
                    Component.translatable(
                                    "npships.tools.cbc_weld_refused",
                                    EditorErrorText.message(invalid))
                            .getString());
        }
    }

    public void renderScene(GuiGraphics g, PoseStack pose) {
        if (!choosing()) return;
        SceneOutlines.box(g, pose, extent, 0xc080ff, false);
        SceneOutlines.box(g, pose, new AABB(from).deflate(.06), 0xffffff, false);
        for (var entry : candidates.entrySet()) {
            var pair = entry.getKey();
            int color =
                    !entry.getValue().isEmpty()
                            ? 0xff9850
                            : selected.contains(pair) ? 0x80dfff : 0x70ffb0;
            SceneOutlines.link(
                    g, pose, Vec3.atCenterOf(pair.from()), Vec3.atCenterOf(pair.to()), color);
        }
        var camera = SceneOutlines.screenMatrix(pose);
        for (var face : Direction.values()) {
            var c = faceCenter(extent, face);
            if (!panel.host.interactionAllowed(c)) continue;
            if (!SchematicViewProjection.projectHandle(camera, c.toVector3f()).isFinite()) continue;
            double density = 0;
            for (var d :
                    new Vector3f[] {
                        new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)
                    }) {
                var axis = SchematicViewProjection.direction(camera, c.toVector3f(), d);
                if (axis.isFinite()) density = Math.max(density, Math.hypot(axis.x, axis.y));
            }
            double half = Math.min(.3, 4 / Math.max(1, density));
            SceneOutlines.box(
                    g,
                    pose,
                    new AABB(c, c).inflate(half),
                    face == dragFace ? 0xffe77b : 0xc080ff,
                    true);
        }
    }
}
