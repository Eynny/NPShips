package com.eynny.npships.client.editor;

import com.eynny.npships.authoring.document.SchematicRegions.Region;
import com.eynny.npships.authoring.geometry.DirectDeformation;
import com.eynny.npships.authoring.geometry.ShapeDeformation;
import com.eynny.npships.client.editor.render.SceneOutlines;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.*;

/** Rotation and deformation handles in the selected local frame. */
final class AreaGizmo {
    private static final int[] COLORS = {0xff7474, 0x80e69a, 0x81b5ff};
    private static final Vec3[] AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private static final int RING_STEPS = 64;

    private enum Kind {
        ROTATION,
        DIRECT
    }

    private record Handle(
            Kind kind, int index, Vec3 origin, Vec3 direction, Vec3 tangent, double radius) {}

    private record Geometry(
            List<Vec3> corners, List<Handle> handles, Matrix4f camera, Matrix4f inverse) {}

    net.minecraft.core.BlockPos modelSize = new net.minecraft.core.BlockPos(1, 1, 1);
    net.minecraft.core.BlockPos displaySize;
    boolean rigidFrame;
    Vec3 modelCenter;
    private Handle active;
    private ShapeDeformation base;
    private ShapeDeformation lastValue;
    private Region region;
    private Matrix4f camera;
    private double startX, startY, lastX, lastY, lastAngle, angle;
    private double screenDx, screenDy;
    private boolean planarRotation;
    boolean snapRotation;
    DirectDeformation directTool = DirectDeformation.NONE;
    String areaId = "";
    boolean pickingCenter;
    int tool; // 0 rotation, 1 direct deformation
    private String message = "";

    boolean dragging() {
        return active != null;
    }

    String message() {
        return message;
    }

    void clear() {
        pickingCenter = false;
        end();
        message = "";
    }

    void end() {
        active = null;
        base = lastValue = null;
        region = null;
        camera = null;
    }

    void render(
            GuiGraphics g,
            PoseStack pose,
            Region region,
            ShapeDeformation shape,
            boolean transformMode) {
        Geometry geometry =
                geometry(region, shape, SceneOutlines.screenMatrix(pose), transformMode);
        if (transformMode && tool == 1 && shape.direct().mode() == DirectDeformation.Mode.SCALE) {
            SceneOutlines.box(g, pose, region.bounds(), 0xffbf60, false);
            var box = region.bounds();
            for (int i = 0; i < 8; i++) {
                var corner =
                        new Vec3(
                                (i & 1) == 0 ? box.minX : box.maxX,
                                (i & 2) == 0 ? box.minY : box.maxY,
                                (i & 4) == 0 ? box.minZ : box.maxZ);
                dot(g, pose, geometry.inverse, corner, 0xffbf60, 4);
            }
            return;
        }
        if (transformMode && tool == 1) renderDirectGuide(g, pose, region, shape);
        for (Handle handle : geometry.handles) {
            int color =
                    handle.kind == Kind.DIRECT
                            ? (handle.index == 0
                                    ? 0xffbf60
                                    : handle.index == 4
                                            ? 0xf0a0dd
                                            : handle.index == 3 ? 0xbda0ff : 0x80e6db)
                            : COLORS[handle.index];
            if (active != null && active.kind == handle.kind && active.index == handle.index)
                color = 0xffe77b;
            switch (handle.kind) {
                case DIRECT -> {
                    Vec3 position = directPosition(handle, shape.direct());
                    if (handle.index == 3) {
                        Vec3 previous =
                                handle.origin.add(handle.direction.scale(shape.direct().radius()));
                        for (int i = 1; i <= RING_STEPS; i++) {
                            double a = i * Math.PI * 2 / RING_STEPS;
                            Vec3 next =
                                    handle.origin
                                            .add(
                                                    handle.direction.scale(
                                                            Math.cos(a) * shape.direct().radius()))
                                            .add(
                                                    handle.tangent.scale(
                                                            Math.sin(a) * shape.direct().radius()));
                            SceneOutlines.link(g, pose, previous, next, color);
                            previous = next;
                        }
                    } else
                        arrow(
                                g,
                                pose,
                                geometry,
                                handle.origin,
                                handle.origin.add(handle.direction.scale(handle.radius)),
                                color);
                    SceneOutlines.link(g, pose, handle.origin, position, color);
                    dot(g, pose, geometry.inverse, position, color, 4);
                    dot(g, pose, geometry.inverse, handle.origin, 0xffff40, 2);
                }
                case ROTATION -> {
                    for (int step = 0; step < 24; step++) {
                        var outer = ringPoint(handle, step * Math.PI / 12);
                        var inner =
                                handle.origin.add(
                                        outer.subtract(handle.origin)
                                                .scale(step % 6 == 0 ? .88 : .95));
                        SceneOutlines.link(g, pose, inner, outer, color);
                    }
                    double current = Math.toRadians(shape.parameters()[handle.index]);
                    dot(g, pose, geometry.inverse, ringPoint(handle, current), color, 4);
                    if (active != null
                            && active.kind == Kind.ROTATION
                            && active.index == handle.index) {
                        var start =
                                ringPoint(handle, Math.toRadians(base.parameters()[handle.index]));
                        dot(g, pose, geometry.inverse, start, 0xffffff, 3);
                        SceneOutlines.link(g, pose, handle.origin, start, 0xffffff);
                        SceneOutlines.link(
                                g, pose, handle.origin, ringPoint(handle, current), color);
                    }
                    Vec3 previous = ringPoint(handle, 0);
                    for (int i = 1; i <= RING_STEPS; i++) {
                        Vec3 next = ringPoint(handle, i * Math.PI * 2 / RING_STEPS);
                        SceneOutlines.link(g, pose, previous, next, color);
                        previous = next;
                    }
                }
            }
        }
    }

    void renderAngles(GuiGraphics g, ShapeDeformation shape, int x, int y, int right) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var values = shape.parameters();
        for (int axis = 0; axis < 3; axis++) {
            String label =
                    String.format(Locale.ROOT, "%s: %.1f\u00b0", "XYZ".charAt(axis), values[axis]);
            if (active != null && active.kind == Kind.ROTATION && active.index == axis)
                label +=
                        String.format(
                                Locale.ROOT,
                                "  (%+.1f\u00b0)",
                                values[axis] - base.parameters()[axis]);
            label = font.plainSubstrByWidth(label, Math.max(1, right - x - 12));
            g.fill(x - 3, y - 2, x + font.width(label) + 3, y + font.lineHeight + 2, 0xc0202020);
            g.drawString(font, label, x, y, COLORS[axis]);
            y += font.lineHeight + 6;
        }
    }

    /** Starts a drag only when a visible handle is hit. */
    boolean begin(
            double x,
            double y,
            Matrix4f inverseCamera,
            Region region,
            ShapeDeformation shape,
            boolean transformMode) {
        if (inverseCamera == null) return false;
        Geometry geometry =
                geometry(region, shape, new Matrix4f(inverseCamera).invert(), transformMode);
        Handle best = null;
        double distance = 36;
        for (Handle handle : geometry.handles) {
            double candidate = hit(handle, geometry.camera, shape, x, y);
            if (candidate < distance) {
                distance = candidate;
                best = handle;
            }
        }
        if (best == null) return false;
        active = best;
        this.base = lastValue = shape;
        this.region = region;
        camera = geometry.camera;
        startX = lastX = x;
        startY = lastY = y;
        angle = 0;
        Vector3f axis =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                        camera, active.origin.toVector3f(), active.direction.toVector3f());
        screenDx = axis.x;
        screenDy = axis.y;
        if (active.kind == Kind.ROTATION) {
            Vector3f
                    u =
                            com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                                    camera,
                                    active.origin.toVector3f(),
                                    active.direction.toVector3f()),
                    v =
                            com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                                    camera,
                                    active.origin.toVector3f(),
                                    active.tangent.toVector3f());
            double determinant = u.x * v.y - u.y * v.x;
            planarRotation =
                    Math.abs(determinant)
                            > .025
                                    * Math.sqrt(
                                            (square(u.x) + square(u.y))
                                                    * (square(v.x) + square(v.y)));
            lastAngle = rotationAngle(x, y);
            if (!planarRotation) {
                // At an edge-on angle the ring collapses to a line: use its visible tangent.
                double hitAngle = closestRingAngle(active, camera, x, y);
                Vec3 tangent =
                        active.direction
                                .scale(-Math.sin(hitAngle))
                                .add(active.tangent.scale(Math.cos(hitAngle)));
                Vector3f projected =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                                camera, active.origin.toVector3f(), tangent.toVector3f());
                screenDx = projected.x * active.radius;
                screenDy = projected.y * active.radius;
                if (screenDx * screenDx + screenDy * screenDy < 4) {
                    screenDx = 0;
                    screenDy = -48;
                }
            }
            message =
                    Component.translatable(
                                    "npships.area_gizmo.rotate",
                                    "XYZ".substring(active.index, active.index + 1))
                            .getString();
        } else
            message =
                    Component.translatable("npships.tools.direct_drag_" + active.index).getString();
        return true;
    }

    /** Returns null when no sample changed, the axis is end-on, or the cage would fold. */
    ShapeDeformation drag(double x, double y) {
        if (active == null || base == null || x == lastX && y == lastY) return null;
        ShapeDeformation changed;
        try {
            switch (active.kind) {
                case ROTATION -> {
                    if (planarRotation) {
                        double now = rotationAngle(x, y);
                        double step = now - lastAngle;
                        step -= Math.PI * 2 * Math.floor((step + Math.PI) / (Math.PI * 2));
                        angle += step;
                        lastAngle = now;
                    } else
                        angle +=
                                ((x - lastX) * screenDx + (y - lastY) * screenDy)
                                        / Math.max(
                                                .0001, screenDx * screenDx + screenDy * screenDy);
                    double[] parameters = base.parameters();
                    double degrees = parameters[active.index] + Math.toDegrees(angle);
                    parameters[active.index] =
                            !snapRotation
                                            && !net.minecraft.client.gui.screens.Screen
                                                    .hasControlDown()
                                    ? Math.round(degrees / 90d) * 90d
                                    : degrees;
                    changed = base.parameters(parameters);
                }
                case DIRECT -> {
                    double norm = screenDx * screenDx + screenDy * screenDy;
                    if (norm < .0001) return null;
                    double amount = ((x - startX) * screenDx + (y - startY) * screenDy) / norm;
                    if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) amount *= .1;
                    var d = base.direct();
                    if (active.index == 0) {
                        double value = d.amount() + amount * directUnits(d, active.radius);
                        d = d.amount(value);
                    } else if (active.index == 3) d = d.radius(Math.max(.5, d.radius() + amount));
                    else if (active.index == 4)
                        d =
                                new DirectDeformation(
                                        d.mode(),
                                        d.axis(),
                                        d.normal(),
                                        d.amount(),
                                        d.both(),
                                        d.center(),
                                        d.radius(),
                                        Math.clamp(
                                                d.softness() + amount * 3 / active.radius, 1, 4));
                    else {
                        int a =
                                active.index == 1
                                        ? Math.abs(d.axis())
                                        : 6 - Math.abs(d.axis()) - Math.abs(d.normal());
                        var c = d.center();
                        double delta = amount * 2 / DirectDeformation.length(modelSize, a);
                        d =
                                d.center(
                                        new Vec3(
                                                Math.clamp(c.x + (a == 1 ? delta : 0), -1, 1),
                                                Math.clamp(c.y + (a == 2 ? delta : 0), -1, 1),
                                                Math.clamp(c.z + (a == 3 ? delta : 0), -1, 1)));
                    }
                    d.validate(modelSize);
                    changed = base.direct(d);
                    message =
                            Component.translatable("npships.tools.direct_drag_" + active.index)
                                            .getString()
                                    + "  "
                                    + String.format(
                                            java.util.Locale.ROOT,
                                            "%.2f",
                                            active.index == 4
                                                    ? d.softness()
                                                    : active.index == 3 ? d.radius() : d.amount());
                }

                default -> throw new IllegalStateException();
            }
            lastX = x;
            lastY = y;
            if (same(changed, lastValue)) return null;
            lastValue = changed;
            return changed;
        } catch (IllegalArgumentException e) {
            message = Component.translatable("npships.area_gizmo.fold_limit").getString();
            lastX = x;
            lastY = y;
            return null;
        }
    }

    private static boolean same(ShapeDeformation a, ShapeDeformation b) {
        if (!a.direct().equals(b.direct())) return false;
        if (a.unrestricted() != b.unrestricted() || !Arrays.equals(a.parameters(), b.parameters()))
            return false;
        for (int i = 0; i < 8; i++) if (!a.corner(i).equals(b.corner(i))) return false;
        return true;
    }

    private Geometry geometry(
            Region region, ShapeDeformation shape, Matrix4f camera, boolean transformMode) {
        Matrix4f inverse = new Matrix4f(camera).invert();
        List<Vec3> points = new ArrayList<>();
        List<Handle> handles = new ArrayList<>();
        // World-space size is stable at any camera distance or orientation.
        // Live fitted sections can shrink while their transform frame must keep its pivot.
        var visibleSize = displaySize == null ? region.size() : displaySize;
        if (transformMode && tool == 1 && shape.direct().mode() == DirectDeformation.Mode.SCALE)
            return new Geometry(points, handles, camera, inverse);
        if (transformMode) {
            Vec3 center =
                    rigidFrame && modelCenter != null ? modelCenter : region.bounds().getCenter();
            double radius =
                    Math.max(
                            1.25,
                            Math.sqrt(
                                            (double) visibleSize.getX() * visibleSize.getX()
                                                    + (double) visibleSize.getY()
                                                            * visibleSize.getY()
                                                    + (double) visibleSize.getZ()
                                                            * visibleSize.getZ())
                                    * .55);
            double[] p = shape.parameters();
            if (tool == 0)
                for (int axis = 0; axis < 3; axis++) {
                    Matrix3d basis = new Matrix3d();
                    if (axis >= 1) basis.rotateX(Math.toRadians(p[0]));
                    if (axis >= 2) basis.rotateY(Math.toRadians(p[1]));
                    Vec3 u = transform(basis, AXES[(axis + 1) % 3]),
                            v = transform(basis, AXES[(axis + 2) % 3]);
                    handles.add(new Handle(Kind.ROTATION, axis, center, u, v, radius));
                }
            double span = Math.max(1.2, radius * .45);
            var orientation =
                    new Matrix3d()
                            .rotationXYZ(
                                    Math.toRadians(p[0]),
                                    Math.toRadians(p[1]),
                                    Math.toRadians(p[2]));
            if (tool == 1) {
                var d = shape.direct();
                Vec3 n = transform(orientation, DirectDeformation.direction(d.normal()));
                Vec3 a = transform(orientation, DirectDeformation.direction(d.axis()));
                Vec3 origin = center;
                if (d.mode() == DirectDeformation.Mode.BULGE) {
                    var c = d.center();
                    origin =
                            center.add(
                                    transform(
                                            orientation,
                                            new Vec3(
                                                    c.x * modelSize.getX() / 2d,
                                                    c.y * modelSize.getY() / 2d,
                                                    c.z * modelSize.getZ() / 2d)));
                    int u = Math.abs(d.axis()), v = 6 - u - Math.abs(d.normal());
                    Vec3 du = transform(orientation, DirectDeformation.direction(u));
                    Vec3 dv = transform(orientation, DirectDeformation.direction(v));
                    handles.add(new Handle(Kind.DIRECT, 1, origin, du, Vec3.ZERO, span * .55));
                    handles.add(new Handle(Kind.DIRECT, 2, origin, dv, Vec3.ZERO, span * .55));
                    handles.add(new Handle(Kind.DIRECT, 3, origin, du, dv, d.radius()));
                    handles.add(new Handle(Kind.DIRECT, 4, origin, dv.scale(-1), Vec3.ZERO, span));
                } else {
                    origin = center.add(a.scale(DirectDeformation.length(modelSize, d.axis()) / 2));
                }
                handles.add(new Handle(Kind.DIRECT, 0, origin, n, Vec3.ZERO, span));
                return new Geometry(List.of(), handles, camera, inverse);
            }
        }
        return new Geometry(points, handles, camera, inverse);
    }

    private void renderDirectGuide(
            GuiGraphics g, PoseStack pose, Region region, ShapeDeformation shape) {
        var d = shape.direct();
        int u = Math.abs(d.axis()) - 1;
        int v =
                d.mode() == DirectDeformation.Mode.TAPER
                        ? Math.abs(d.normal()) - 1
                        : 6 - Math.abs(d.axis()) - Math.abs(d.normal()) - 1;
        double[] size = {modelSize.getX(), modelSize.getY(), modelSize.getZ()};
        Vec3 center = modelCenter == null ? region.bounds().getCenter() : modelCenter;
        for (int family = 0; family < 2; family++)
            for (int line = 0; line <= 8; line++) {
                Vec3 previous = null;
                for (int step = 0; step <= 16; step++) {
                    double[] q = {size[0] / 2, size[1] / 2, size[2] / 2};
                    q[u] = (family == 0 ? line / 8d : step / 16d) * size[u];
                    q[v] = (family == 0 ? step / 16d : line / 8d) * size[v];
                    var point = shape.unscaledPoint(new Vec3(q[0], q[1], q[2]), center, modelSize);
                    if (previous != null) SceneOutlines.link(g, pose, previous, point, 0x679b9d);
                    previous = point;
                }
            }
        if (d.mode() != DirectDeformation.Mode.BULGE) {
            double[] a = {size[0] / 2, size[1] / 2, size[2] / 2};
            a[u] = d.axis() > 0 ? 0 : size[u];
            a[v] = 0;
            var lo = shape.unscaledPoint(new Vec3(a[0], a[1], a[2]), center, modelSize);
            a[v] = size[v];
            var hi = shape.unscaledPoint(new Vec3(a[0], a[1], a[2]), center, modelSize);
            SceneOutlines.link(g, pose, lo, hi, 0xffe77b);
        }
    }

    ShapeDeformation pickCenter(Vec3 start, Vec3 end, Region region, ShapeDeformation shape) {
        var d = shape.direct();
        var angles = shape.parameters();
        var inv =
                new Matrix3d()
                        .rotationXYZ(
                                Math.toRadians(angles[0]),
                                Math.toRadians(angles[1]),
                                Math.toRadians(angles[2]))
                        .transpose();
        Vec3 center = modelCenter == null ? region.bounds().getCenter() : modelCenter;
        var a = transform(inv, start.subtract(center));
        var b = transform(inv, end.subtract(center));
        var n = DirectDeformation.direction(d.normal());
        double denominator = b.subtract(a).dot(n);
        if (Math.abs(denominator) < 1e-8) return null;
        double t = -a.dot(n) / denominator;
        if (t < 0 || t > 1) return null;
        var at = a.lerp(b, t);
        var world = start.lerp(end, t);
        if (!reachable.test(world)) return null;
        double cx = at.x * 2 / modelSize.getX(),
                cy = at.y * 2 / modelSize.getY(),
                cz = at.z * 2 / modelSize.getZ();
        if (Math.max(Math.abs(cx), Math.max(Math.abs(cy), Math.abs(cz))) > 1.00001) return null;
        return shape.direct(
                d.center(
                        new Vec3(
                                Math.clamp(cx, -1, 1),
                                Math.clamp(cy, -1, 1),
                                Math.clamp(cz, -1, 1))));
    }

    private static double directUnits(DirectDeformation d, double span) {
        return d.mode() == DirectDeformation.Mode.BEND
                ? 90 / span
                : (d.mode() == DirectDeformation.Mode.TAPER
                                || d.mode() == DirectDeformation.Mode.SCALE)
                        ? 100 / span
                        : 1;
    }

    private static Vec3 directPosition(Handle h, DirectDeformation d) {
        double distance =
                h.index == 0
                        ? (d.amount() - (DirectDeformation.neutralAmount(d.mode())))
                                / directUnits(d, h.radius)
                        : h.index == 4
                                ? h.radius * (d.softness() - .5) / 3
                                : h.index == 3 ? d.radius() : h.radius;
        return h.origin.add(h.direction.scale(distance));
    }

    private static Vec3 transform(Matrix3d matrix, Vec3 value) {
        Vector3d p = matrix.transform(new Vector3d(value.x, value.y, value.z));
        return new Vec3(p.x, p.y, p.z);
    }

    private static Vec3 screenDelta(Matrix4f inverse, Vector3f anchor, double x, double y) {
        var start = inverse.transformProject(new Vector3f(anchor));
        var end = inverse.transformProject(new Vector3f(anchor).add((float) x, (float) y, 0));
        return new Vec3(end.sub(start));
    }

    private static Vector3f project(Matrix4f camera, Vec3 point) {
        return com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                camera, point.toVector3f());
    }

    java.util.function.Predicate<Vec3> reachable = point -> true;

    private static double square(double v) {
        return v * v;
    }

    private static Vec3 ringPoint(Handle h, double radians) {
        return h.origin
                .add(h.direction.scale(Math.cos(radians) * h.radius))
                .add(h.tangent.scale(Math.sin(radians) * h.radius));
    }

    private static double segment(Vector3f a, Vector3f b, double x, double y) {
        if (!a.isFinite() || !b.isFinite()) return Double.MAX_VALUE;
        double dx = b.x - a.x, dy = b.y - a.y;
        double t =
                Math.max(
                        0,
                        Math.min(
                                1,
                                ((x - a.x) * dx + (y - a.y) * dy)
                                        / Math.max(.000001, dx * dx + dy * dy)));
        return square(x - a.x - t * dx) + square(y - a.y - t * dy);
    }

    private double hit(Handle h, Matrix4f camera, ShapeDeformation shape, double x, double y) {
        if (h.kind == Kind.ROTATION) {
            double distance = Double.MAX_VALUE;
            Vector3f previous = project(camera, ringPoint(h, 0));
            for (int i = 1; i <= RING_STEPS; i++) {
                Vector3f next = project(camera, ringPoint(h, i * Math.PI * 2 / RING_STEPS));
                if (reachable.test(ringPoint(h, i * Math.PI * 2 / RING_STEPS)))
                    distance = Math.min(distance, segment(previous, next, x, y));
                previous = next;
            }
            return distance;
        }
        if (!reachable.test(h.origin)) return Double.MAX_VALUE;
        if (h.kind == Kind.DIRECT) {
            var p = project(camera, directPosition(h, shape.direct()));
            return square(p.x - x) + square(p.y - y);
        }

        Vector3f a = project(camera, h.origin),
                b = project(camera, h.origin.add(h.direction.scale(h.radius)));
        float length = (float) Math.hypot(b.x - a.x, b.y - a.y);
        if (length < 12) return Double.MAX_VALUE;
        a.lerp(b, Math.min(.5f, 9 / length));
        return segment(a, b, x, y);
    }

    private double rotationAngle(double x, double y) {
        Vector3f center = project(camera, active.origin),
                u =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                                camera, active.origin.toVector3f(), active.direction.toVector3f()),
                v =
                        com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                                camera, active.origin.toVector3f(), active.tangent.toVector3f());
        double determinant = u.x * v.y - u.y * v.x;
        if (Math.abs(determinant) < .00000001) return 0;
        double px = x - center.x, py = y - center.y;
        return Math.atan2((u.x * py - u.y * px) / determinant, (px * v.y - py * v.x) / determinant);
    }

    private static double closestRingAngle(Handle handle, Matrix4f camera, double x, double y) {
        double nearest = Double.MAX_VALUE, angle = 0;
        for (int i = 0; i < RING_STEPS; i++) {
            double a = i * Math.PI * 2 / RING_STEPS;
            Vector3f p = project(camera, ringPoint(handle, a));
            double d = square(p.x - x) + square(p.y - y);
            if (d < nearest) {
                nearest = d;
                angle = a;
            }
        }
        return angle;
    }

    private static void dot(
            GuiGraphics g, PoseStack pose, Matrix4f inverse, Vec3 point, int color, int radius) {
        var anchor = project(new Matrix4f(inverse).invert(), point);
        if (!anchor.isFinite()) return;
        for (int y = -radius; y <= radius; y++) {
            double half = y == -radius || y == radius ? radius * .5 : radius;
            SceneOutlines.link(
                    g,
                    pose,
                    point.add(screenDelta(inverse, anchor, -half, y)),
                    point.add(screenDelta(inverse, anchor, half, y)),
                    color);
        }
    }

    private static void arrow(
            GuiGraphics g, PoseStack pose, Geometry geo, Vec3 a, Vec3 b, int color) {
        Vector3f from = project(geo.camera, a), to = project(geo.camera, b);
        if (!from.isFinite() || !to.isFinite()) return;
        double dx = to.x - from.x, dy = to.y - from.y, len = Math.hypot(dx, dy);
        if (len < 3) return;
        SceneOutlines.link(
                g,
                pose,
                a.add(screenDelta(geo.inverse, from, dx / len * 7, dy / len * 7)),
                b,
                color);
        Vec3 back = screenDelta(geo.inverse, to, -dx / len * 8, -dy / len * 8),
                side = screenDelta(geo.inverse, to, -dy / len * 4, dx / len * 4);
        SceneOutlines.link(g, pose, b, b.add(back).add(side), color);
        SceneOutlines.link(g, pose, b, b.add(back).subtract(side), color);
    }
}
