package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.document.FittedPlaneLayers;
import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicEdits;
import com.eynny.npships.authoring.document.SchematicGlue;
import com.eynny.npships.authoring.document.SchematicReframe;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.screen.BlockEditorScreen;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class EditorGestures {
    private final EditorToolsPanel panel;

    EditorGestures(EditorToolsPanel panel) {
        this.panel = panel;
    }

    boolean beginDrag(double x, double y, Matrix4f inverse, float depth) {
        if (panel.tool == EditorToolsPanel.Tool.AREA && panel.membershipMode != 0) return false;
        panel.areaGizmo.reachable = panel.host::interactionAllowed;
        if (panel.tool == EditorToolsPanel.Tool.AREA && panel.host.firstCorner() != null)
            panel.areaGizmo.modelSize = panel.rotationBasisSize();
        if (panel.tool == EditorToolsPanel.Tool.AREA
                && panel.deforming
                && panel.areaGizmo.pickingCenter
                && panel.host.firstCorner() != null
                && inverse != null) {
            var ray = panel.ray(x, y, inverse, depth);
            var changed =
                    panel.areaGizmo.pickCenter(
                            ray[0], ray[1], panel.gizmoRegion(), panel.deformation);
            if (changed != null) {
                panel.rememberArea();
                panel.deformation = changed;
                panel.areaGizmo.pickingCenter = false;
                panel.updateShape();
                panel.rebuild = true;
            }
            return true;
        }
        panel.dragCorner = -1;
        panel.gizmoGesture = false;
        AABB seedHandle = null;
        if (panel.deforming
                && panel.areaGizmo.tool == 1
                && panel.deformation.direct().mode() == DirectDeformation.Mode.SCALE)
            panel.movingArea = false;
        if (panel.tool == EditorToolsPanel.Tool.AREA
                && !panel.massRegionEditing
                && panel.host.specialBounds() == null
                && inverse != null) {
            var layer = panel.layers.find(panel.activeArea);
            if (FittedPlaneLayers.plane(layer)) {
                var seed = FittedPlaneLayers.seed(layer);
                var handle = new AABB(seed).deflate(.15);
                var seedRay = panel.ray(x, y, inverse, depth);
                if (panel.host.visible(seed)
                        && panel.host.interactionAllowed(handle.getCenter())
                        && handle.clip(seedRay[0], seedRay[1]).isPresent()) {
                    seedHandle = handle;
                    panel.movingArea = true;
                }
            }
        }
        panel.boundaryDrag = panel.tool == EditorToolsPanel.Tool.AREA && !panel.movingArea;
        if (panel.tool == EditorToolsPanel.Tool.AREA
                && seedHandle == null
                && !panel.massRegionEditing
                && panel.host.firstCorner() != null
                && panel.host.specialBounds() == null
                && panel.areaGizmo.begin(
                        x, y, inverse, panel.gizmoRegion(), panel.deformation, panel.deforming)) {
            panel.boundaryDrag = false;
            panel.ensureArea();
            panel.rememberArea();
            panel.gizmoGesture = true;
            panel.dragOriginal = panel.dragBase = panel.doc();
            panel.dragChanged = !panel.areaGizmo.dragging();
            panel.host.status(panel.areaGizmo.message());
            return true;
        }
        var b = panel.selectedBounds();
        if (b == null || inverse == null) return false;
        var ray = panel.ray(x, y, inverse, depth);
        var hitBounds = seedHandle != null ? seedHandle : b;
        var hit =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                        hitBounds, ray[0], ray[1]);
        if (hit.isEmpty()) return false;
        double best = Double.MAX_VALUE;
        panel.dragFace = net.minecraft.core.Direction.UP;
        for (var face : net.minecraft.core.Direction.values()) {
            int axis = face.getAxis().ordinal();
            double value = hit.get().get(face.getAxis());
            double edge =
                    face.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE
                            ? hitBounds.max(face.getAxis())
                            : hitBounds.min(face.getAxis());
            if (Math.abs(value - edge) < best) {
                best = Math.abs(value - edge);
                panel.dragFace = face;
            }
        }
        panel.rememberArea();
        panel.dragOriginal = panel.dragBase = panel.doc();
        panel.dragBlock = panel.pos();
        panel.dragEntity = panel.entityIndex;
        panel.dragBounds = b;
        panel.proposedBounds = b;
        panel.dragX = x;
        panel.dragY = y;
        panel.dragStep = 0;
        panel.dragChanged = false;
        panel.dragOccupied = false;
        panel.lastFreeDrag = panel.doc();
        panel.lastFreeBlock = panel.pos();
        return true;
    }

    boolean dragging() {
        return panel.dragBase != null;
    }

    boolean dragChanged() {
        return panel.dragChanged;
    }

    void cancelDrag() {
        panel.deferredShape = false;
        panel.boundaryDrag = false;
        panel.areaGizmo.end();
        panel.gizmoGesture = false;
        panel.dragBase = null;
        panel.dragOriginal = null;
        panel.host.previewBounds(null);
    }

    boolean drag(double x, double y, Matrix4f camera) {
        if (panel.dragBase == null) return false;
        if (panel.gizmoGesture) {
            var next = panel.areaGizmo.drag(x, y);
            if (next != null) {
                panel.deformation = next;
                if (panel.areaGizmo.tool == 1) panel.areaSticky = false;
                panel.updateShape();
                panel.dragChanged = true;
            }
            panel.host.status(panel.areaGizmo.message());
            return true;
        }
        var vector =
                new Vector3f(
                        panel.dragFace.getStepX(),
                        panel.dragFace.getStepY(),
                        panel.dragFace.getStepZ());
        vector =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                        camera, panel.dragBounds.getCenter().toVector3f(), vector);
        double length = vector.x * vector.x + vector.y * vector.y;
        if (length < .01) return true;
        int step =
                (int)
                        Math.round(
                                ((x - panel.dragX) * vector.x + (y - panel.dragY) * vector.y)
                                        / length);
        if (step == panel.dragStep) return true;
        panel.dragStep = step;
        var offset =
                new BlockPos(
                        panel.dragFace.getStepX() * step,
                        panel.dragFace.getStepY() * step,
                        panel.dragFace.getStepZ() * step);
        if (panel.regionTool() || panel.host.specialBounds() != null) {
            if (panel.host.importing() && panel.host.translatingImport()) {
                panel.proposedBounds = panel.dragBounds.move(offset);
                panel.host.previewBounds(panel.proposedBounds);
                panel.dragChanged = true;
                return true;
            }
            double[] lo = {panel.dragBounds.minX, panel.dragBounds.minY, panel.dragBounds.minZ},
                    hi = {panel.dragBounds.maxX, panel.dragBounds.maxY, panel.dragBounds.maxZ};
            int axis = panel.dragFace.getAxis().ordinal();
            double amount = offset.get(panel.dragFace.getAxis());
            if (panel.host.specialBounds() == null
                    && panel.tool == EditorToolsPanel.Tool.AREA
                    && !panel.massRegionEditing
                    && panel.movingArea) {
                lo[axis] += amount;
                hi[axis] += amount;
            } else if (panel.dragFace.getAxisDirection()
                    == net.minecraft.core.Direction.AxisDirection.POSITIVE) hi[axis] += amount;
            else lo[axis] += amount;
            if (panel.host.symmetricResize
                    && !(panel.tool == EditorToolsPanel.Tool.AREA && panel.movingArea)) {
                if (panel.dragFace.getAxisDirection()
                        == net.minecraft.core.Direction.AxisDirection.POSITIVE) lo[axis] -= amount;
                else hi[axis] -= amount;
                double center = (lo[axis] + hi[axis]) * .5;
                double extent = Math.max(.5, (hi[axis] - lo[axis]) * .5);
                if (panel.host.specialBounds() == null)
                    extent =
                            Math.min(
                                    extent,
                                    Math.min(
                                            center,
                                            panel.doc().size().get(panel.dragFace.getAxis())
                                                    - center));
                lo[axis] = center - extent;
                hi[axis] = center + extent;
            }
            // Clamp the live face instead of rejecting overshoot until the cursor returns.
            if (panel.host.specialBounds() == null) {
                double limit = panel.doc().size().get(panel.dragFace.getAxis());
                if (lo[axis] <= 0 || hi[axis] >= limit) panel.host.flashBounds();
                if (panel.tool == EditorToolsPanel.Tool.AREA
                        && panel.movingArea
                        && !panel.massRegionEditing) {
                    double span = hi[axis] - lo[axis];
                    lo[axis] = Math.max(0, Math.min(limit - span, lo[axis]));
                    hi[axis] = lo[axis] + span;
                } else if (panel.dragFace.getAxisDirection()
                        == net.minecraft.core.Direction.AxisDirection.POSITIVE)
                    hi[axis] = Math.max(lo[axis] + 1, Math.min(limit, hi[axis]));
                else lo[axis] = Math.max(0, Math.min(hi[axis] - 1, lo[axis]));
            }
            if (hi[axis] - lo[axis] < 1) return true;
            var candidate = new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
            if (panel.host.specialBounds() == null && !panel.host.checkBounds(candidate))
                return true;
            panel.proposedBounds = candidate;
            panel.host.previewBounds(candidate);
            panel.dragChanged = true;
            if (panel.host.specialBounds() == null
                    && panel.tool == EditorToolsPanel.Tool.AREA
                    && !panel.massRegionEditing) {
                panel.areaOrigin =
                        panel.xyz(
                                BlockPos.containing(
                                        candidate.minX, candidate.minY, candidate.minZ));
                panel.areaSize =
                        panel.xyz(
                                BlockPos.containing(
                                        candidate.getXsize(),
                                        candidate.getYsize(),
                                        candidate.getZsize()));
                panel.host.selectRegion(
                        BlockEditorScreen.position(panel.areaOrigin),
                        BlockEditorScreen.position(panel.areaSize));
                panel.run(() -> panel.updateShape());
                panel.dragChanged = true;
            }
            return true;
        }
        panel.run(
                () -> {
                    SchematicDocument next;
                    if (panel.tool == EditorToolsPanel.Tool.ASSEMBLY) {
                        var marker =
                                com.eynny.npships.authoring.AssemblyPoints.read(
                                                panel.dragBase.configCopy())
                                        .stream()
                                        .filter(p -> p.id().equals(panel.assemblyPoints.selected))
                                        .findFirst()
                                        .orElseThrow();
                        var target = marker.position().offset(offset);
                        if (!panel.host.checkBounds(new AABB(target))) return;
                        next =
                                com.eynny.npships.authoring.AssemblyPoints.put(
                                        panel.dragBase, marker.id(), target);
                        panel.host.previewDraft(next);
                    } else if (panel.blockSelectionTool()) {
                        var to = panel.dragBlock.offset(offset);
                        if (!panel.host.checkBounds(new AABB(to))) return;
                        var base = panel.dragBase;
                        var i = base.blocks().get(to);
                        boolean occupied =
                                i != null
                                        && !base.paletteCopy()
                                                .getCompound(i)
                                                .getString("Name")
                                                .equals("minecraft:air")
                                        && !to.equals(panel.dragBlock);
                        panel.dragOccupied = occupied;
                        if (occupied)
                            base =
                                    SchematicBlocks.put(
                                            base, to, to, Blocks.AIR.defaultBlockState(), null);
                        var state =
                                NbtUtils.readBlockState(
                                        panel.mc.level.holderLookup(Registries.BLOCK),
                                        panel.dragBase
                                                .paletteCopy()
                                                .getCompound(
                                                        panel.dragBase
                                                                .blocks()
                                                                .get(panel.dragBlock)));
                        next = SchematicBlocks.put(base, panel.dragBlock, to, state, null);
                        panel.host.previewDraft(next);
                        panel.host.select(to);
                        if (!occupied) {
                            panel.lastFreeDrag = next;
                            panel.lastFreeBlock = to;
                        }
                    } else {
                        var e = panel.dragBase.entitiesCopy().getCompound(panel.dragEntity);
                        var p = e.getList("pos", Tag.TAG_DOUBLE);
                        var target =
                                new Vec3(
                                        p.getDouble(0) + offset.getX(),
                                        p.getDouble(1) + offset.getY(),
                                        p.getDouble(2) + offset.getZ());
                        if (!panel.host.checkBounds(new AABB(BlockPos.containing(target)))) return;
                        next =
                                SchematicEdits.entity(
                                        panel.dragBase,
                                        panel.dragEntity,
                                        target,
                                        e.getCompound("nbt"));
                        panel.host.previewDraft(next);
                        panel.entityIndex = panel.dragEntity;
                    }
                    panel.dragChanged = true;
                });
        return true;
    }

    void endDrag() {
        if (panel.dragBase == null) return;
        var before = panel.dragOriginal;
        if (panel.gizmoGesture) {
            panel.gizmoGesture = false;
            panel.areaGizmo.end();
            panel.dragBase = null;
            panel.dragOriginal = null;
            if (panel.deferredShape) panel.updateShape();
            if (panel.dragChanged && !panel.shapeJobs.busy()) {
                panel.host.finishDraftGesture(before);
                panel.shapeCommitBase = null;
                panel.rememberArea();
            }
            panel.rebuild = true;
            return;
        }
        if (panel.blockSelectionTool() && panel.dragOccupied && !Screen.hasShiftDown()) {
            panel.host.previewDraft(panel.lastFreeDrag);
            panel.host.select(panel.lastFreeBlock);
            panel.blocked = panel.lastFreeBlock;
            panel.blockedUntil = System.currentTimeMillis() + 5000;
        }
        panel.dragBase = null;
        panel.dragOriginal = null;
        if (panel.regionTool() || panel.host.specialBounds() != null) {
            var b = panel.proposedBounds;
            panel.host.previewBounds(null);
            if (b.equals(panel.dragBounds)) {
                panel.deferredShape = false;
                panel.boundaryDrag = false;
                return;
            }
            var origin = BlockPos.containing(b.minX, b.minY, b.minZ);
            var size = BlockPos.containing(b.getXsize(), b.getYsize(), b.getZsize());
            panel.run(
                    () -> {
                        if (panel.host.specialBounds() != null) {
                            panel.host.finishBounds(origin, size);
                            return;
                        }
                        if (panel.tool == EditorToolsPanel.Tool.AREA || panel.massRegionEditing) {
                            panel.areaOrigin = panel.xyz(origin);
                            panel.areaSize = panel.xyz(size);
                            panel.host.selectRegion(origin, size);
                            if (panel.massRegionEditing) panel.calculateRegionMass();
                            else {
                                if (panel.deferredShape) panel.updateShape();
                                if (panel.dragChanged && !panel.shapeJobs.busy()) {
                                    panel.applyingShape = true;
                                    try {
                                        panel.host.finishDraftGesture(before);
                                        panel.shapeCommitBase = null;
                                        panel.rememberArea();
                                    } finally {
                                        panel.applyingShape = false;
                                    }
                                } else if (!panel.dragChanged) panel.updateShape();
                            }
                            panel.build();
                        } else if (panel.tool == EditorToolsPanel.Tool.SEGMENTS)
                            panel.host.proposeResize(
                                    SchematicReframe.segment(
                                            panel.doc(), panel.segment, origin, size));
                        else if (panel.tool == EditorToolsPanel.Tool.GLUE) {
                            int index = panel.entityIndex;
                            String id =
                                    panel.doc()
                                            .entitiesCopy()
                                            .getCompound(index)
                                            .getCompound("nbt")
                                            .getString("id");
                            panel.commit(SchematicGlue.put(panel.doc(), index, id, b));
                            panel.entityIndex = index;
                        } else {
                            var c = panel.doc().configCopy();
                            var z = panel.zoneObject(c);
                            z.add("origin", SchematicPatches.vector(origin));
                            z.add("size", SchematicPatches.vector(size));
                            StructureCrew.validateBounds(panel.doc(), c);
                            panel.commit(panel.doc().withConfig(c));
                        }
                    });
        } else if (panel.dragChanged) {
            int index = panel.entityIndex;
            panel.host.finishDraftGesture(before);
            panel.entityIndex = index;
        }
    }

    void rotateSelected(int direction) {
        if (direction == 0 || panel.regionTool() || panel.host.specialBounds() != null) return;
        panel.run(
                () -> {
                    if (panel.blockSelectionTool() && panel.pos() != null) {
                        if (panel.dragging() && panel.dragOccupied) return;
                        var state = panel.selectedBlockState();
                        state =
                                com.eynny.npships.authoring.document.SchematicRotations.cycle(
                                        state, direction, panel.mc.level);
                        var next =
                                SchematicBlocks.put(
                                        panel.doc(), panel.pos(), panel.pos(), state, null);
                        if (panel.dragging()) {
                            panel.host.previewDraft(next);
                            panel.dragChanged = true;
                            panel.dragBase = next;
                            panel.dragBlock = panel.pos();
                            panel.lastFreeDrag = next;
                            panel.lastFreeBlock = panel.pos();
                            panel.dragStep = 0;
                            panel.dragX = panel.host.mouseX();
                            panel.dragY = panel.host.mouseY();
                        } else panel.commit(next);
                    } else if (panel.entityTool() && panel.entityIndex >= 0) {
                        int index = panel.entityIndex;
                        var e = panel.doc().entitiesCopy().getCompound(index);
                        var p = e.getList("pos", Tag.TAG_DOUBLE);
                        var data = e.getCompound("nbt");
                        data =
                                com.eynny.npships.authoring.document.EntitySettings.turn(
                                        data, panel.mc.level, direction);
                        var next =
                                SchematicEdits.entity(
                                        panel.doc(),
                                        index,
                                        new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2)),
                                        data);
                        if (panel.dragging()) {
                            panel.host.previewDraft(next);
                            panel.dragChanged = true;
                            panel.dragBase = next;
                            panel.dragStep = 0;
                            panel.dragX = panel.host.mouseX();
                            panel.dragY = panel.host.mouseY();
                        } else panel.commit(next);
                        panel.entityIndex = index;
                    }
                });
    }

    boolean pickEntity(double x, double y, Matrix4f inverse, float depth) {
        if (!panel.entityTool() || panel.entityPlacement() || inverse == null) return false;
        var hits = panel.entityHits(x, y, inverse, depth);
        int chosen =
                hits.isEmpty() ? -1 : hits.get((hits.indexOf(panel.entityIndex) + 1) % hits.size());
        panel.host.clearRegion();
        panel.host.select(null);
        panel.entityIndex = hits.size() == 1 && panel.entityIndex == chosen ? -1 : chosen;
        if (chosen >= 0 && panel.mode().equals("delete")) {
            int index = chosen;
            panel.run(
                    () -> panel.commit(SchematicEdits.entity(panel.doc(), index, Vec3.ZERO, null)));
            panel.entityIndex = -1;
        }
        panel.scroll = 0;
        panel.build();
        return true;
    }

    boolean pickRegion(double x, double y, Matrix4f inverse, float depth) {
        boolean allZones = panel.host.authoringSelectionReady();
        if (panel.massRegionEditing && !allZones && !panel.createRegion()) return true;
        if (inverse == null
                || (!allZones
                        && (!panel.regionTool()
                                || panel.createRegion()
                                || panel.tool == EditorToolsPanel.Tool.GLUE))) return false;
        record Target(EditorToolsPanel.Tool tool, String id, double distance) {}
        var ray = panel.ray(x, y, inverse, depth);
        var hits = new ArrayList<Target>();
        if (panel.tool == EditorToolsPanel.Tool.AREA || allZones)
            for (var area : panel.layers.layers()) {
                var hit =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                                area.region().bounds(), ray[0], ray[1]);
                if (hit.isPresent())
                    hits.add(
                            new Target(
                                    EditorToolsPanel.Tool.AREA,
                                    area.id(),
                                    hit.get().distanceToSqr(ray[0])));
            }
        if (panel.tool == EditorToolsPanel.Tool.SEGMENTS || allZones)
            for (var s : panel.segments) {
                var hit =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                                panel.bounds(s.origin(), s.size()), ray[0], ray[1]);
                if (hit.isPresent())
                    hits.add(
                            new Target(
                                    EditorToolsPanel.Tool.SEGMENTS,
                                    s.id(),
                                    hit.get().distanceToSqr(ray[0])));
            }
        if (panel.tool == EditorToolsPanel.Tool.ZONES || allZones)
            for (var z : panel.zones) {
                var hit =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                                panel.bounds(z.origin(), z.size()), ray[0], ray[1]);
                if (hit.isPresent())
                    hits.add(
                            new Target(
                                    EditorToolsPanel.Tool.ZONES,
                                    z.id(),
                                    hit.get().distanceToSqr(ray[0])));
            }
        if (allZones)
            for (int i = 0; i < panel.entityPreview.entities.size(); i++) {
                var e = panel.entityPreview.entities.get(i);
                if (!panel.glue(e) || !panel.host.visible(BlockPos.containing(e.position())))
                    continue;
                var hit =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                                e.getBoundingBox(), ray[0], ray[1]);
                if (hit.isPresent())
                    hits.add(
                            new Target(
                                    EditorToolsPanel.Tool.GLUE,
                                    "" + panel.entityPreview.entityIndices.get(i),
                                    hit.get().distanceToSqr(ray[0])));
            }
        hits.sort(Comparator.comparingDouble(Target::distance).thenComparing(Target::id));
        String selected =
                panel.tool == EditorToolsPanel.Tool.AREA
                        ? panel.activeArea
                        : panel.tool == EditorToolsPanel.Tool.SEGMENTS
                                ? panel.segment
                                : panel.tool == EditorToolsPanel.Tool.GLUE
                                        ? "" + panel.entityIndex
                                        : panel.zone;
        int index = -1;
        for (int i = 0; i < hits.size(); i++)
            if (hits.get(i).tool() == panel.tool && hits.get(i).id().equals(selected)) index = i;
        if (panel.mergeChoosing) {
            var other =
                    hits.stream()
                            .filter(
                                    hit ->
                                            hit.tool() == EditorToolsPanel.Tool.AREA
                                                    && !hit.id().equals(panel.activeArea))
                            .findFirst();
            if (other.isPresent()) panel.chooseArea(other.get().id());
            else if (hits.stream().noneMatch(hit -> hit.tool() == EditorToolsPanel.Tool.AREA))
                panel.subtractFromBase();
            return true;
        }
        if (hits.isEmpty()) {
            if (panel.tool == EditorToolsPanel.Tool.AREA && !panel.host.deleting()) {
                panel.clearSelection();
                panel.host.clearRegion();
            }
            return true;
        } else {
            var target = hits.get((index + 1) % hits.size());
            boolean deselect = hits.size() == 1 && index == 0 && !allZones;
            panel.massRegionEditing = false;
            panel.tool = target.tool();
            panel.segment = "";
            panel.zone = "";
            panel.entityIndex = -1;
            if (panel.tool == EditorToolsPanel.Tool.AREA) {
                boolean deleting = panel.host.deleting() && !allZones;
                if (deleting) {
                    // Selecting promotes/rebuilds live surfaces. Delete the hit layer directly,
                    // without queuing a competing rebuild or changing the user's delete mode.
                    panel.activeArea = target.id();
                    panel.removeArea();
                } else panel.chooseArea(target.id());
                return true;
            }
            if (panel.tool == EditorToolsPanel.Tool.GLUE) {
                panel.entityIndex = Integer.parseInt(target.id());
            } else if (panel.tool == EditorToolsPanel.Tool.SEGMENTS) {
                panel.segment = deselect ? "" : target.id();
                if (panel.host.deleting() && !allZones) {
                    panel.commit(SchematicReframe.deleteSegment(panel.doc(), target.id()));
                    panel.segment = "";
                }
            } else {
                panel.zone = deselect ? "" : target.id();
                if (panel.host.deleting() && !allZones) {
                    panel.zone = target.id();
                    panel.deleteZone();
                }
            }
        }
        panel.host.select(null);
        panel.host.clearRegion();
        panel.scroll = 0;
        panel.build();
        return true;
    }

    List<Integer> entityHits(double x, double y, Matrix4f inverse, float depth) {
        if (inverse == null) return List.of();
        var ray = panel.ray(x, y, inverse, depth);
        var hits = new ArrayList<Integer>();
        var distances = new HashMap<Integer, Double>();
        for (int i = 0; i < panel.entityPreview.entities.size(); i++) {
            var e = panel.entityPreview.entities.get(i);
            if (panel.glue(e) != (panel.tool == EditorToolsPanel.Tool.GLUE)
                    || panel.entityPreview.failedEntities.contains(e)
                    || !panel.host.visible(BlockPos.containing(e.position()))) continue;
            var hit =
                    com.eynny.npships.authoring.geometry.SchematicViewProjection.hit(
                            e.getBoundingBox().inflate(.05), ray[0], ray[1]);
            if (hit.isPresent()) {
                int index = panel.entityPreview.entityIndices.get(i);
                hits.add(index);
                distances.put(index, hit.get().distanceToSqr(ray[0]));
            }
        }
        hits.sort(Comparator.<Integer>comparingDouble(distances::get).thenComparingInt(i -> i));
        return hits;
    }

    int entityAt(double x, double y, Matrix4f inverse, float depth) {
        var hits = panel.entityHits(x, y, inverse, depth);
        return hits.isEmpty() ? -1 : hits.getFirst();
    }
}
