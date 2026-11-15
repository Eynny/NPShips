package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.DirectDeformation.Mode;
import com.eynny.npships.client.editor.widget.EditorIconButton;

import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** The selected operation owns its controls; rotations never share the scene with warp sliders. */
final class DirectDeformationControls {
    private final EditorToolsPanel p;

    DirectDeformationControls(EditorToolsPanel panel) {
        p = panel;
    }

    void build() {
        if (p.areaGizmo.tool == 1 && current().mode() == Mode.NONE) {
            var tool = p.areaGizmo.directTool;
            p.deformation =
                    p.deformation.direct(tool.amount(DirectDeformation.neutralAmount(tool.mode())));
        }
        var modes = Mode.values();
        String[] keys = new String[modes.length];
        Runnable[] actions = new Runnable[modes.length];
        java.util.function.BooleanSupplier[] states =
                new java.util.function.BooleanSupplier[modes.length];
        keys[0] = "direct_rotation";
        actions[0] =
                () -> {
                    if (p.areaGizmo.tool == 0) return;
                    p.applyDeformationThen(
                            () -> {
                                p.areaGizmo.clear();
                                p.areaGizmo.tool = 0;
                                p.build();
                            });
                };
        states[0] = () -> p.areaGizmo.tool == 0;
        for (int i = 1; i < modes.length; i++) {
            var mode = modes[i];
            keys[i] = key(mode);
            actions[i] = () -> select(mode);
            states[i] = () -> p.areaGizmo.tool == 1 && current().mode() == mode;
        }
        p.areaIcons(keys, actions, states);
        help((p.areaGizmo.tool == 0 ? "direct_rotation" : key(current().mode())) + "_hint");
        // Scale edits the content through bounds; axis and completion controls below are other
        // modes.
        if (p.areaGizmo.tool == 1 && current().mode() == Mode.SCALE) {
            p.areaControls(
                    new String[] {"direct_apply_values"},
                    new String[] {"direct_apply_values_short"},
                    new Runnable[] {() -> p.applyDeformationThen(p::build)},
                    new java.util.function.BooleanSupplier[] {null});
            p.sections.completionRow(p.flows.getLast(), () -> p.deforming);
            return;
        }
        if (p.areaGizmo.tool == 1) {
            var d = current();
            if (d.mode() != Mode.BULGE) {
                axisButton("direct_axis", d.axis(), true);
                p.areaIcons(
                        new String[] {"direct_fixed_end"},
                        new Runnable[] {
                            () -> {
                                change(current().axes(-current().axis(), current().normal()));
                                p.build();
                            }
                        },
                        new java.util.function.BooleanSupplier[] {() -> current().axis() < 0});
            }
            axisButton(
                    d.mode() == Mode.TAPER ? "direct_width_axis" : "direct_normal",
                    d.normal(),
                    false);
            if (d.mode() == Mode.TAPER) {
                p.areaIcons(
                        new String[] {"direct_both"},
                        new Runnable[] {
                            () -> {
                                var live = current();
                                change(
                                        new DirectDeformation(
                                                live.mode(),
                                                live.axis(),
                                                live.normal(),
                                                live.amount(),
                                                !live.both(),
                                                live.center(),
                                                live.radius(),
                                                live.softness()));
                                p.build();
                            }
                        },
                        new java.util.function.BooleanSupplier[] {() -> current().both()});
            }
            if (d.mode() == Mode.BULGE)
                p.areaControls(
                        new String[] {"direct_pick_center", "direct_center_reset"},
                        new String[] {"direct_pick_center_short", "direct_center_reset_short"},
                        new Runnable[] {
                            () -> {
                                p.areaGizmo.pickingCenter = !p.areaGizmo.pickingCenter;
                                p.host.status(p.tr("direct_pick_center_hint").getString());
                            },
                            () -> {
                                change(current().center(Vec3.ZERO));
                                p.build();
                            }
                        },
                        new java.util.function.BooleanSupplier[] {
                            () -> p.areaGizmo.pickingCenter, null
                        });
        }
        p.areaControls(
                new String[] {"direct_apply_values", "direct_reverse", "direct_reset"},
                new String[] {
                    "direct_apply_values_short", "direct_reverse_short", "direct_reset_short"
                },
                new Runnable[] {
                    () -> p.applyDeformationThen(p::build),
                    p::reverseDeformation,
                    () -> {
                        if (p.areaGizmo.tool == 0) {
                            p.rememberArea();
                            var values = p.deformation.parameters();
                            java.util.Arrays.fill(values, 0, 3, 0);
                            p.deformation = p.deformation.parameters(values);
                            p.updateShape();
                        } else
                            change(
                                    current()
                                            .amount(
                                                    DirectDeformation.neutralAmount(
                                                            current().mode())));
                        p.build();
                    }
                },
                new java.util.function.BooleanSupplier[] {null, null, null});
        p.sections.completionRow(p.flows.getLast(), () -> p.deforming);
    }

    private static String key(Mode mode) {
        return "direct_" + mode.name().toLowerCase(Locale.ROOT);
    }

    private void help(String key) {
        var text =
                new MultiLineTextWidget(p.tr(key), p.mc.font)
                        .setMaxWidth(Math.max(1, p.contentWidth()));
        text.setPosition(p.x, p.cursor);
        p.widgets.add(text);
        p.wrappedText.put(text, EditorLayout.CONTENT_GUTTER);
        p.cursor += text.getHeight() + 6;
    }

    private void select(Mode mode) {
        if (p.areaGizmo.tool == 1 && current().mode() == mode) return;
        p.applyDeformationThen(() -> activate(mode));
    }

    private void activate(Mode mode) {
        p.areaGizmo.clear();
        p.areaGizmo.tool = 1;
        if (mode == Mode.SCALE) p.movingArea = false;
        if (current().mode() != mode) {
            var s = p.rotationBasisSize();
            int n = s.getY() <= s.getX() && s.getY() <= s.getZ() ? 2 : s.getX() <= s.getZ() ? 1 : 3;
            int a = n == 1 ? 3 : 1;
            if (DirectDeformation.length(s, a) < DirectDeformation.length(s, 6 - a - n))
                a = 6 - a - n;
            if (mode == Mode.TAPER) n = 6 - a - n;
            change(
                    new DirectDeformation(
                            mode,
                            a,
                            n,
                            DirectDeformation.neutralAmount(mode),
                            mode == Mode.SCALE,
                            Vec3.ZERO,
                            Math.max(.5, Math.max(s.getX(), Math.max(s.getY(), s.getZ())) * .35),
                            2));
        }
        p.build();
    }

    private void axisButton(String label, int selected, boolean longitudinal) {
        p.widgets.add(new StringWidget(p.x, p.cursor, p.w, 12, p.tr(label), p.mc.font).alignLeft());
        p.cursor += 14;
        String[] keys = new String[3];
        Runnable[] actions = new Runnable[3];
        java.util.function.BooleanSupplier[] states = new java.util.function.BooleanSupplier[3];
        for (int i = 1; i <= 3; i++) {
            int axis = i;
            keys[i - 1] = "direct_axis_" + "xyz".charAt(i - 1);
            actions[i - 1] =
                    () -> {
                        var d = current();
                        int a = d.axis(), n = d.normal();
                        if (longitudinal) {
                            if (Math.abs(n) == axis) n = Math.abs(a);
                            a = axis * Integer.signum(a);
                        } else {
                            if (Math.abs(a) == axis) a = Math.abs(n);
                            n = axis;
                        }
                        change(d.axes(a, n));
                        p.build();
                    };
            states[i - 1] = () -> Math.abs(selected) == axis;
        }
        int first = p.widgets.size();
        p.areaIcons(keys, actions, states);
        for (int i = 0; i < 3; i++)
            ((EditorIconButton) p.widgets.get(first + i))
                    .hint(p.tr(label).copy().append(": " + "XYZ".charAt(i)));
    }

    private DirectDeformation current() {
        return p.deformation.direct();
    }

    private void change(DirectDeformation next) {
        next.validate(p.rotationBasisSize());
        p.rememberArea();
        p.deformation = p.deformation.direct(next);
        p.areaGizmo.directTool = next;
        p.areaSticky = false;
        p.updateShape();
    }
}
