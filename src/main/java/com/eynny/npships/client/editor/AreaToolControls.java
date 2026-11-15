package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.FittedPlaneLayers;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.FittedPlane;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.client.editor.widget.EditorButton;
import com.eynny.npships.client.editor.widget.EditorIconButton;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;

import java.util.*;

/** Tool-specific controls; document commits and selection stay with the editor session. */
final class AreaToolControls {
    private final EditorToolsPanel panel;

    AreaToolControls(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void areaTools() {
        if (!panel.inspectedArea.isEmpty()) {
            var layer = panel.layers.find(panel.inspectedArea);
            if (layer == null) return;
            var summary =
                    new MultiLineTextWidget(
                                    net.minecraft.network.chat.Component.translatable(
                                            "npships.tools.area_inspection_summary",
                                            layer.name(),
                                            panel.xyz(layer.region().origin()),
                                            panel.xyz(layer.region().size())),
                                    panel.mc.font)
                            .setMaxWidth(panel.contentWidth());
            summary.setPosition(panel.x, panel.cursor);
            panel.widgets.add(summary);
            panel.wrappedText.put(summary, EditorLayout.CONTENT_GUTTER);
            panel.cursor += summary.getHeight() + 4;
            panel.wrappedNote("area_inspection_hint");
            panel.button("npships.tools.area_activate_edit", () -> panel.chooseArea(layer.id()))
                    .setTooltip(Tooltip.create(panel.tr("area_activate_edit_hint")));
            return;
        }
        if (panel.massRegionEditing
                || panel.shapeJobs.busy() && panel.layers.find(panel.activeArea) == null) return;
        panel.sections.begin("creation", "area_creation_section", true);
        panel.sections.workflow(
                () -> panel.host.creating() || panel.fittedPlane != null,
                () ->
                        panel.tr(
                                panel.fittedPlane != null
                                        ? "fitted_plane_pick"
                                        : "area_two_corners"));
        panel.areaControls(
                new String[] {"area_custom", "fitted_plane"},
                new String[] {"area", "fitted_plane_short"},
                new Runnable[] {
                    () -> {
                        if (!panel.flushEdits()) return;
                        panel.clearSelection();
                        panel.host.clearRegion();
                        panel.fittedPlane = null;
                        panel.fittedPlaneMenu = false;
                        panel.host.setCreateMode();
                        panel.build();
                    },
                    () -> {
                        panel.fittedPlane = null;
                        panel.fittedPlaneMenu = !panel.fittedPlaneMenu;
                        panel.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {
                    () -> !panel.fittedPlaneMenu && panel.fittedPlane == null,
                    () -> panel.fittedPlaneMenu || panel.fittedPlane != null
                });
        if (panel.fittedPlaneMenu || panel.fittedPlane != null) {
            var orientations = FittedPlane.Orientation.values();
            panel.areaIcons(
                    Arrays.stream(orientations)
                            .map(
                                    orientation ->
                                            "fitted_plane_"
                                                    + orientation.name().toLowerCase(Locale.ROOT))
                            .toArray(String[]::new),
                    Arrays.stream(orientations)
                            .map(
                                    orientation ->
                                            (Runnable) () -> panel.beginFittedPlane(orientation))
                            .toArray(Runnable[]::new),
                    new java.util.function.BooleanSupplier[orientations.length]);
        }
        for (var layer : panel.layers.layers()) {
            panel.buttonAt(
                            FittedPlaneLayers.plane(layer)
                                    ? panel.tr("fitted_plane").copy().append(": " + layer.name())
                                    : panel.tr("area_custom_label")
                                            .copy()
                                            .append(": " + layer.name()),
                            panel.x,
                            panel.cursor,
                            panel.w,
                            () -> panel.chooseArea(layer.id()))
                    .selected(() -> panel.activeArea.equals(layer.id()))
                    .setTooltip(Tooltip.create(panel.tr("area_activate_edit_hint")));
            panel.cursor += 24;
        }
        if (panel.fittedPlaneMenu || panel.fittedPlane != null) {
            panel.sections.end();
            return;
        }
        if (panel.host.firstCorner() == null || panel.host.selectingRegion()) {
            var help =
                    new MultiLineTextWidget(panel.tr("area_two_corners"), panel.mc.font)
                            .setMaxWidth(panel.contentWidth());
            help.setPosition(panel.x, panel.cursor);
            panel.widgets.add(help);
            panel.wrappedText.put(help, EditorLayout.CONTENT_GUTTER);
            panel.cursor += help.getHeight() + 8;
            panel.sections.end();
            return;
        }
        panel.sections.end();
        panel.ensureArea();
        var selectedArea = panel.layers.get(panel.activeArea);
        if (FittedPlaneLayers.plane(selectedArea)) {
            editingStart(selectedArea);
            completion();
            panel.sections.end();
            panel.sections.begin("plane", "area_plane_section", true);
            panel.sections.workflow(() -> panel.shapeJobs.busy(), () -> panel.tr("shape_busy"));
            planeTools();
            panel.sections.end();
            panel.sections.group("brush", "area_brush_section", true, this::brushTools);
            deformationTools();
            return;
        }
        if (panel.layers.find(panel.activeArea) != null) {
            panel.sections.begin("membership", "area_membership_section", true);
            panel.sections.workflow(
                    () -> panel.membershipMode != 0,
                    () ->
                            panel.tr(
                                            panel.membershipMode > 0
                                                    ? "area_member_add"
                                                    : "area_member_remove")
                                    .copy()
                                    .append("\n")
                                    .append(panel.tr("area_member_hint")));
            panel.areaControls(
                    new String[] {"area_member_add", "area_member_remove"},
                    new String[] {"area_member_add_short", "area_member_remove_short"},
                    new Runnable[] {
                        () -> {
                            panel.membershipMode = panel.membershipMode == 1 ? 0 : 1;
                            panel.host.setSelectMode();
                            panel.build();
                        },
                        () -> {
                            panel.membershipMode = panel.membershipMode == -1 ? 0 : -1;
                            panel.host.setSelectMode();
                            panel.build();
                        }
                    },
                    new java.util.function.BooleanSupplier[] {
                        () -> panel.membershipMode == 1, () -> panel.membershipMode == -1
                    });
            for (var widget : panel.flows.getLast().cells())
                ((EditorIconButton) widget).hint(panel.tr("area_member_hint"));
            panel.sections.end();
        }
        int[] shapeMenuY = {panel.cursor};
        String[] keys = {
            "area_blocks",
            "area_entities",
            "area_zones",
            "area_turn_left",
            "area_turn_right",
            "area_move",
            "area_delete",
            "area_shape",
            "area_generate",
            "area_hollow"
        };
        Runnable[] actions = {
            () -> {
                panel.areaBlocks = !panel.areaBlocks;
                panel.updateShape();
                panel.build();
            },
            () -> {
                panel.areaEntities = !panel.areaEntities;
                panel.updateShape();
                panel.build();
            },
            () -> {
                panel.areaZones = !panel.areaZones;
                panel.updateShape();
                panel.build();
            },
            () -> panel.turnArea(false),
            () -> panel.turnArea(true),
            () -> panel.pasteArea(true),
            panel::deleteArea,
            () -> {
                var entries =
                        new java.util.ArrayList<
                                com.eynny.npships.client.editor.widget.EditorPopupMenu.Entry>();
                for (var value : SchematicRegions.Shape.values()) {
                    if (value == SchematicRegions.Shape.CYLINDER_X
                            || value == SchematicRegions.Shape.CYLINDER_Z
                            || value == SchematicRegions.Shape.CYLINDER_TILTED
                            || value == SchematicRegions.Shape.TETRAHEDRON_SIDE
                            || value == SchematicRegions.Shape.HEMISPHERE_DOWN) continue;
                    entries.add(
                            new com.eynny.npships.client.editor.widget.EditorPopupMenu.Entry(
                                    panel.tr(
                                            "shape_"
                                                    + value.name()
                                                            .toLowerCase(java.util.Locale.ROOT)),
                                    () -> panel.areaShape == value,
                                    () ->
                                            panel.run(
                                                    () -> {
                                                        panel.rememberArea();
                                                        panel.areaShape = value;
                                                        panel.areaGenerated = true;
                                                        panel.areaTurns = 0;
                                                        panel.deformation =
                                                                ShapeDeformation.IDENTITY;
                                                        panel.updateShape();
                                                        panel.build();
                                                    })));
                }
                panel.host.toolMenu(panel.x, shapeMenuY[0] - panel.scroll, entries);
            },
            () -> {
                panel.rememberArea();
                panel.areaGenerated = !panel.areaGenerated;
                panel.updateShape();
                panel.build();
            },
            () -> {
                panel.rememberArea();
                panel.areaHollow = !panel.areaHollow;
                panel.updateShape();
                panel.build();
            }
        };
        panel.sections.begin("contents", "area_contents_section", true);
        int firstInclusion = panel.widgets.size();
        panel.areaIcons(
                new String[] {keys[0], keys[1], keys[2], "area_sticky", "area_transparent"},
                new Runnable[] {
                    actions[0],
                    actions[1],
                    actions[2],
                    () -> {
                        panel.areaSticky = !panel.areaSticky;
                        panel.updateShape();
                        panel.build();
                    },
                    () -> {
                        panel.areaTransparent = !panel.areaTransparent;
                        var l = panel.layers.get(panel.activeArea);
                        panel.layerCommit(
                                panel.layers.replace(
                                        l.id(),
                                        l.region(),
                                        l.content(),
                                        l.mask(),
                                        panel.areaOptions()));
                        panel.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {
                    () -> panel.areaBlocks,
                    () -> panel.areaEntities,
                    () -> panel.areaZones,
                    () -> panel.areaSticky,
                    () -> panel.areaTransparent
                });
        for (int i = 0; i < 3; i++) {
            var button = (EditorIconButton) panel.widgets.get(firstInclusion + i);
            button.hint(panel.tr(keys[i] + "_hint"));
            if (i == 2) button.active = !panel.host.isPart();
        }
        panel.sections.end();
        editingStart(selectedArea);
        completion();
        panel.sections.end();
        panel.sections.begin("material", "area_material_section", true);
        panel.sections.workflow(() -> panel.shapeJobs.busy(), () -> panel.tr("shape_busy"));
        shapeMenuY[0] = panel.cursor;
        panel.areaIcons(
                new String[] {
                    "area_shape",
                    "area_generate",
                    "area_hollow",
                    "area_random",
                    "area_random_enabled"
                },
                new Runnable[] {
                    actions[7],
                    actions[8],
                    actions[9],
                    () -> {
                        panel.randomPanelOpen = !panel.randomPanelOpen;
                        panel.build();
                    },
                    () -> {
                        panel.randomBlocks = !panel.randomBlocks;
                        panel.updateShape();
                        panel.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {
                    () -> panel.shapeListOpen,
                    () -> panel.areaGenerated,
                    () -> panel.areaHollow,
                    () -> panel.randomPanelOpen,
                    () -> panel.randomBlocks
                });
        panel.randomBlockTools();
        brushTools();
        panel.sections.end();
        deformationTools();
    }

    private void editingStart(BlockAreaLayers.Layer selectedArea) {
        panel.sections.begin("editing", "area_editing_section", true);
        panel.sections.workflow(
                () ->
                        panel.mergeChoosing
                                || panel.movingArea && !panel.deforming
                                || panel.shapeJobs.busy(),
                () ->
                        panel.tr(
                                panel.shapeJobs.busy()
                                        ? "shape_busy"
                                        : panel.mergeChoosing
                                                ? "area_subtract_hint"
                                                : "area_move_drag"));
        String[] areaName = {selectedArea.name()};
        panel.field("area_name", areaName[0], v -> areaName[0] = v);
        panel.defer(
                () -> {
                    if (!areaName[0].equals(selectedArea.name()))
                        panel.layerCommit(panel.layers.rename(selectedArea.id(), areaName[0]));
                });
    }

    private void completion() {
        panel.areaControls(
                new String[] {
                    "area_subtract", "symmetric_resize", "area_move", "area_apply", "area_delete"
                },
                new String[] {
                    "area_subtract_short",
                    null,
                    panel.movingArea ? "area_mode_move_short" : "area_mode_resize_short",
                    "area_apply_short",
                    "area_clear_contents_short"
                },
                new Runnable[] {
                    () -> {
                        if (panel.mergeChoosing) {
                            panel.mergeChoosing = false;
                            return;
                        }
                        panel.host.setSelectMode();
                        panel.mergeChoosing = true;
                        panel.mergeSubtract = true;
                        panel.host.status(panel.tr("area_subtract_hint").getString());
                    },
                    () -> {
                        panel.host.symmetricResize = !panel.host.symmetricResize;
                        panel.build();
                    },
                    () -> panel.pasteArea(true),
                    panel::applyArea,
                    panel::deleteArea
                },
                new java.util.function.BooleanSupplier[] {
                    () -> panel.mergeChoosing && panel.mergeSubtract,
                    () -> panel.host.symmetricResize,
                    () -> panel.movingArea,
                    null,
                    null
                });
    }

    private void planeTools() {
        panel.areaControls(
                new String[] {"fitted_plane_lock", "area_transparent"},
                new String[] {
                    FittedPlaneLayers.locked(panel.layers.find(panel.activeArea))
                            ? "fitted_plane_unlock_short"
                            : "fitted_plane_lock_short",
                    null
                },
                new Runnable[] {
                    panel::togglePlaneLock,
                    () -> {
                        panel.areaTransparent = !panel.areaTransparent;
                        var l = panel.layers.get(panel.activeArea);
                        panel.layerCommit(
                                panel.layers.replace(
                                        l.id(),
                                        l.region(),
                                        l.content(),
                                        l.mask(),
                                        panel.areaOptions()));
                        panel.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {
                    () -> FittedPlaneLayers.locked(panel.layers.find(panel.activeArea)),
                    () -> panel.areaTransparent
                });
    }

    private void deformationTools() {
        panel.sections.begin("transform", "area_transform_section", false);
        panel.sections.workflow(
                () -> panel.deforming || !panel.deformation.identity(),
                () -> panel.tr(panel.shapeJobs.busy() ? "shape_busy" : transformMode() + "_hint"));
        panel.sections.workflowLabel(() -> panel.tr(transformMode()));
        panel.areaIcons(
                new String[] {"area_turn_left", "area_turn_right", "deform_shape"},
                new Runnable[] {
                    () -> panel.turnArea(false),
                    () -> panel.turnArea(true),
                    () -> {
                        panel.deforming = !panel.deforming;
                        panel.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {null, null, () -> panel.deforming});
        if (panel.deforming) {
            panel.cursor += 4;
            var safety =
                    EditorButton.flat(
                                    panel.tr(
                                            panel.allowConfiguredLoss
                                                    ? "deformation_safety_off"
                                                    : "deformation_safety_on"),
                                    pressed -> {
                                        if (panel.shapeJobs.busy()) return;
                                        panel.allowConfiguredLoss = !panel.allowConfiguredLoss;
                                        pressed.setMessage(
                                                panel.tr(
                                                        panel.allowConfiguredLoss
                                                                ? "deformation_safety_off"
                                                                : "deformation_safety_on"));
                                    })
                            .bounds(panel.x, panel.cursor, panel.contentWidth(), 20)
                            .build();
            safety.selected(() -> !panel.allowConfiguredLoss);
            safety.tooltip(
                    () ->
                            panel.tr(
                                    panel.allowConfiguredLoss
                                            ? "deformation_safety_off_hint"
                                            : "deformation_safety_on_hint"));
            safety.active = !panel.shapeJobs.busy();
            panel.widgets.add(safety);
            panel.cursor += EditorLayout.ROW_PITCH;
            boolean livePlane =
                    FittedPlaneLayers.plane(panel.layers.find(panel.activeArea))
                            && !FittedPlaneLayers.locked(panel.layers.find(panel.activeArea));
            panel.areaIcons(
                    panel.areaGizmo.tool == 0
                            ? new String[] {"continuous_surface", "rotation_snap"}
                            : new String[] {"continuous_surface"},
                    panel.areaGizmo.tool == 0
                            ? new Runnable[] {
                                panel::toggleContinuousSurface,
                                () -> panel.areaGizmo.snapRotation = !panel.areaGizmo.snapRotation
                            }
                            : new Runnable[] {panel::toggleContinuousSurface},
                    panel.areaGizmo.tool == 0
                            ? new java.util.function.BooleanSupplier[] {
                                () -> livePlane || panel.continuousSurface,
                                () -> panel.areaGizmo.snapRotation
                            }
                            : new java.util.function.BooleanSupplier[] {
                                () -> livePlane || panel.continuousSurface
                            });
            var continuous = (EditorIconButton) panel.flows.getLast().cells().getFirst();
            continuous.active = !livePlane;
            continuous.hint(
                    panel.tr(livePlane ? "continuous_plane_hint" : "continuous_surface_hint"));
            new DirectDeformationControls(panel).build();
        }
        panel.sections.end();
    }

    private String transformMode() {
        if (panel.areaGizmo.tool == 0) return "direct_rotation";
        var mode = panel.deformation.direct().mode();
        if (mode == DirectDeformation.Mode.NONE) mode = panel.areaGizmo.directTool.mode();
        if (mode == DirectDeformation.Mode.NONE) return "direct_rotation";
        return "direct_" + mode.name().toLowerCase(Locale.ROOT);
    }

    private void brushTools() {
        panel.field(
                "state",
                panel.state,
                v -> {
                    panel.state = v;
                    panel.blockBrushData = null;
                    panel.blockBrushPatches = java.util.List.of();
                });
        panel.registry(false);
        panel.quickRows(false);
    }

    void randomBlockTools() {
        if (!panel.randomPanelOpen) return;
        String[] original = {Double.toString(panel.originalBlockWeight)};
        panel.field(
                "area_original_weight",
                original[0],
                v -> {
                    original[0] = v;
                    panel.settingsDirty = true;
                });
        panel.defer(
                () -> {
                    double value = Double.parseDouble(original[0]);
                    if (!Double.isFinite(value) || value < 0)
                        throw new IllegalArgumentException(
                                panel.tr("area_weight_error").getString());
                    if (value != panel.originalBlockWeight) {
                        panel.originalBlockWeight = value;
                        panel.updateShape();
                    }
                });
        for (int i = 0; i < panel.randomStates.size(); i++) {
            int index = i;
            String[] values = {
                panel.randomStates.get(i), Double.toString(panel.randomWeights.get(i))
            };
            var blockState =
                    panel.field(
                            "state",
                            values[0],
                            v -> {
                                values[0] = v;
                                panel.settingsDirty = true;
                            });
            panel.field("mob_weight", values[1], v -> values[1] = v);
            panel.defer(
                    () -> {
                        double weight = Double.parseDouble(values[1]);
                        if (!Double.isFinite(weight) || weight <= 0)
                            throw invalid(
                                    "block_weight", "Positive block weight required", values[1]);
                        panel.validateField(blockState, () -> panel.parse(values[0]));
                        if (!values[0].equals(panel.randomStates.get(index))
                                || weight != panel.randomWeights.get(index)) {
                            panel.randomStates.set(index, values[0]);
                            panel.randomWeights.set(index, weight);
                            panel.updateShape();
                        }
                    });
            panel.action(
                    "remove",
                    () -> {
                        panel.randomStates.remove(index);
                        panel.randomWeights.remove(index);
                        panel.updateShape();
                        panel.build();
                    });
        }
        panel.areaControls(
                new String[] {"area_random_add", "area_reroll"},
                new String[] {"area_random_add_short", "area_reroll_short"},
                new Runnable[] {
                    () -> {
                        panel.parse(panel.state);
                        panel.randomStates.add(panel.state);
                        panel.randomWeights.add(1d);
                        panel.updateShape();
                        panel.build();
                    },
                    () -> {
                        panel.randomSeed++;
                        panel.updateShape();
                    }
                },
                new java.util.function.BooleanSupplier[] {null, null});
    }
}
