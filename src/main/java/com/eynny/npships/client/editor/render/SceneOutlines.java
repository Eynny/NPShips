package com.eynny.npships.client.editor.render;

import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.AABB;

import org.joml.Vector3f;

/** Screen-width ribbons: independent of camera scale, face culling and world depth. */
public final class SceneOutlines extends RenderType {
    // Scoped to one preview render; reset by the screen's finally block.
    private static org.joml.Matrix4f lens = new org.joml.Matrix4f();
    private static org.joml.Matrix4f inverseLens = new org.joml.Matrix4f();
    private static float nearZ;
    private static float clipDepth;

    public static void projection(org.joml.Matrix4f value, float depth) {
        clipDepth = depth;
        lens = new org.joml.Matrix4f(value);
        inverseLens = new org.joml.Matrix4f(value).invert();
        nearZ = inverseLens.transformProject(new Vector3f(0, 0, depth)).z;
    }

    public static org.joml.Matrix4f screenMatrix(PoseStack pose) {
        return new org.joml.Matrix4f(lens).mul(pose.last().pose());
    }

    public static void massCross(
            GuiGraphics g, PoseStack pose, net.minecraft.world.phys.Vec3 center) {
        massCross(g, pose, center, 0xffd040);
    }

    public static void massCross(
            GuiGraphics g, PoseStack pose, net.minecraft.world.phys.Vec3 center, int color) {
        // Fixed local axes, not a screen-facing reticle or rays to the schematic edges.
        var p =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                        screenMatrix(pose), center.toVector3f());
        if (!p.isFinite()) return;
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1}) {
                var direction =
                        com.eynny.npships.authoring.geometry.MassMarker.axis(
                                        pose.last().pose(), axis)
                                .mul(sign);
                // Project after 3D normalization; the end-on axis naturally disappears.
                ribbon(
                        g,
                        new Vector3f(p).fma(.35f, direction),
                        new Vector3f(p).add(direction),
                        color,
                        1,
                        1,
                        1);
            }
        ribbon(g, new Vector3f(p).add(-1, 0, 0), new Vector3f(p).add(1, 0, 0), 0xffffff, 2, 1, 1);
    }

    public static void massPlanes(
            GuiGraphics g,
            PoseStack pose,
            net.minecraft.world.phys.Vec3 center,
            net.minecraft.core.BlockPos size,
            boolean[] enabled) {
        massPlanes(g, pose, center, size, enabled, 1);
    }

    public static void massPlanes(
            GuiGraphics g,
            PoseStack pose,
            net.minecraft.world.phys.Vec3 center,
            net.minecraft.core.BlockPos size,
            boolean[] enabled,
            float opacity) {
        massPlanes(g, pose, center, size, enabled, opacity, 0xffd040);
    }

    public static void massPlanes(
            GuiGraphics g,
            PoseStack pose,
            net.minecraft.world.phys.Vec3 center,
            net.minecraft.core.BlockPos size,
            boolean[] enabled,
            float opacity,
            int color) {
        double[] c = {center.x, center.y, center.z};
        int[] n = {size.getX(), size.getY(), size.getZ()};
        for (int normal = 0; normal < 3; normal++)
            if (enabled[normal])
                for (int along = 0; along < 3; along++)
                    if (along != normal) {
                        int across = 3 - normal - along;
                        int step = Math.max(1, n[across] / 128);
                        for (int i = 0; i <= n[across]; i += step) {
                            double[] a = c.clone(), b = c.clone();
                            a[across] = b[across] = i;
                            a[along] = 0;
                            b[along] = n[along];
                            var start = new Vector3f((float) a[0], (float) a[1], (float) a[2]);
                            var end = new Vector3f((float) b[0], (float) b[1], (float) b[2]);
                            if (!com.eynny.npships.authoring.geometry.SchematicViewProjection
                                            .projectSegment(
                                                    screenMatrix(pose), clipDepth, start, end)
                                    || !clip(start, end, g.guiWidth(), g.guiHeight())) continue;
                            int pieces =
                                    Math.max(1, Math.min(2048, (int) (start.distance(end) / 5)));
                            for (int j = 0; j < pieces; j++)
                                ribbon(
                                        g,
                                        new Vector3f(start).lerp(end, (float) j / pieces),
                                        new Vector3f(start).lerp(end, (j + .5f) / pieces),
                                        color,
                                        .7f,
                                        .55f * opacity,
                                        .55f * opacity);
                        }
                    }
    }

    public static void front(GuiGraphics g, PoseStack pose, net.minecraft.core.BlockPos size) {
        double x = size.getX() / 2.0, y = size.getY() / 2.0;
        var tail = new net.minecraft.world.phys.Vec3(x, y, 0);
        double length = Math.max(2, Math.max(size.getX(), size.getZ()) * .25);
        var tip = tail.add(0, 0, -length);
        link(g, pose, tail, tip, 0x60eaff);
        link(g, pose, tip, tip.add(-length * .3, 0, length * .4), 0x60eaff);
        link(g, pose, tip, tip.add(length * .3, 0, length * .4), 0x60eaff);
    }

    private SceneOutlines() {
        super(
                "npships_outline",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                () -> {},
                () -> {});
    }

    private static final RenderType TYPE =
            create(
                    "npships_outline",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(
                                    new DepthTestStateShard(
                                            "npships_overlay", org.lwjgl.opengl.GL11.GL_ALWAYS) {
                                        @Override
                                        public void setupRenderState() {
                                            com.mojang.blaze3d.systems.RenderSystem
                                                    .disableDepthTest();
                                        }

                                        @Override
                                        public void clearRenderState() {
                                            com.mojang.blaze3d.systems.RenderSystem
                                                    .enableDepthTest();
                                        }
                                    })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));
    private static final RenderType GRID =
            create(
                    "npships_grid",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));
    private static final RenderType HIDDEN =
            create(
                    "npships_hidden_zone",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(
                                    new DepthTestStateShard(
                                            "greater", org.lwjgl.opengl.GL11.GL_GREATER))
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));

    public record Zone(AABB bounds, int color, boolean thick) {}

    public static void zones(GuiGraphics g, PoseStack pose, java.util.List<Zone> zones) {
        for (var zone : zones)
            edges(
                    g,
                    pose,
                    zone.bounds(),
                    zone.color(),
                    false,
                    zone.thick() ? 2f : 1f,
                    zone.thick() ? 1 : .65f,
                    GRID);
        for (var zone : zones)
            edges(
                    g,
                    pose,
                    zone.bounds(),
                    zone.color(),
                    true,
                    zone.thick() ? 2f : 1f,
                    zone.thick() ? 1 : .65f,
                    HIDDEN);
    }

    public static void zone(GuiGraphics g, PoseStack pose, AABB bounds, int color, boolean thick) {
        float width = thick ? 2f : 1f;
        edges(g, pose, bounds, color, false, width, 1, GRID);
        edges(g, pose, bounds, color, true, width, 1, HIDDEN);
    }

    public static void deletion(GuiGraphics g, PoseStack pose, AABB bounds, int color) {
        edges(g, pose, bounds, color, true, 1.75f);
    }

    /** Inset face squares identify configured devices without imitating selection edges. */
    public static void patch(GuiGraphics g, PoseStack pose, AABB bounds, int color) {
        patch(g, pose, bounds, color, .2f);
    }

    public static void patch(GuiGraphics g, PoseStack pose, AABB bounds, int color, float inset) {
        for (int axis = 0; axis < 3; axis++)
            for (int side = 0; side < 2; side++) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                float[] lo = {(float) bounds.minX, (float) bounds.minY, (float) bounds.minZ};
                float[] hi = {(float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ};
                Vector3f[] points = new Vector3f[4];
                for (int i = 0; i < 4; i++) {
                    var p = new Vector3f();
                    p.setComponent(axis, side == 0 ? lo[axis] - .003f : hi[axis] + .003f);
                    p.setComponent(
                            u, lo[u] + (hi[u] - lo[u]) * (i == 1 || i == 2 ? 1 - inset : inset));
                    p.setComponent(v, lo[v] + (hi[v] - lo[v]) * (i >= 2 ? 1 - inset : inset));
                    points[i] = p;
                }
                for (int i = 0; i < 4; i++) {
                    var start = new Vector3f(points[i]);
                    var end = new Vector3f(points[(i + 1) % 4]);
                    if (!com.eynny.npships.authoring.geometry.SchematicViewProjection
                                    .projectSegment(screenMatrix(pose), clipDepth, start, end)
                            || !clip(start, end, g.guiWidth(), g.guiHeight())) continue;
                    ribbon(g, start, end, color, 1, 1, 1, GRID);
                    int steps =
                            Math.max(1, Math.min(512, (int) Math.ceil(start.distance(end) / 5)));
                    for (int dash = 0; dash < steps; dash++)
                        ribbon(
                                g,
                                new Vector3f(start).lerp(end, (float) dash / steps),
                                new Vector3f(start).lerp(end, (dash + .5f) / steps),
                                color,
                                1,
                                1,
                                1,
                                HIDDEN);
                }
            }
    }

    private static AABB gridBounds;
    private static int gridSpacing;
    private static double gridFade = -1;
    private static java.util.List<com.eynny.npships.authoring.geometry.SchematicGrid.Line>
            gridLines = java.util.List.of();

    public static void box(GuiGraphics g, PoseStack pose, AABB b, int color, boolean thick) {
        zone(g, pose, b.inflate(.002), color, thick);
    }

    private static final RenderType MARKER =
            create(
                    "npships_assembly_marker",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(
                                    new ShaderStateShard(GameRenderer::getPositionTexColorShader))
                            .setTextureState(
                                    new TextureStateShard(
                                            net.minecraft.resources.ResourceLocation
                                                    .fromNamespaceAndPath(
                                                            "npships",
                                                            "textures/gui/editor/assembly.png"),
                                            false,
                                            false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(CULL)
                            .createCompositeState(false));

    /**
     * Outward faces share the toolbar icon and ignore preview depth, like their outline. Culling
     * still hides inward faces through the image's clear pixels.
     */
    public static void markerBox(
            GuiGraphics g, PoseStack pose, AABB bounds, int color, boolean thick) {
        box(g, pose, bounds, color, thick);
        var out = g.bufferSource().getBuffer(MARKER);
        var center = bounds.getCenter().toVector3f();
        var half =
                new Vector3f(
                                (float) bounds.getXsize(),
                                (float) bounds.getYsize(),
                                (float) bounds.getZsize())
                        .mul(.5f);
        for (var face : net.minecraft.core.Direction.values()) {
            var normal = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
            var up =
                    face.getAxis() == net.minecraft.core.Direction.Axis.Y
                            ? new Vector3f(0, 0, face == net.minecraft.core.Direction.UP ? -1 : 1)
                            : new Vector3f(0, 1, 0);
            var right = new Vector3f(up).cross(normal);
            for (int i = 0; i < 4; i++) {
                boolean highU = i == 1 || i == 2, highV = i >= 2;
                var point =
                        new Vector3f(normal)
                                .fma(highU ? 1 : -1, right)
                                .fma(highV ? 1 : -1, up)
                                .mul(half)
                                .add(center);
                out.addVertex(pose.last().pose(), point.x, point.y, point.z)
                        .setUv(highU ? 1 : 0, highV ? 0 : 1)
                        .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255);
            }
        }
    }

    public static void dashed(GuiGraphics g, PoseStack pose, AABB b, int color) {
        edges(g, pose, b, color, true, .75f);
    }

    public static void dashed(GuiGraphics g, PoseStack pose, AABB b, int color, float alpha) {
        edges(g, pose, b, color, true, .75f, alpha);
    }

    private static void edges(
            GuiGraphics g, PoseStack pose, AABB b, int color, boolean dashed, float width) {
        edges(g, pose, b, color, dashed, width, 1);
    }

    private static void edges(
            GuiGraphics g,
            PoseStack pose,
            AABB b,
            int color,
            boolean dashed,
            float width,
            float alpha) {
        edges(g, pose, b, color, dashed, width, alpha, TYPE);
    }

    private static void edges(
            GuiGraphics g,
            PoseStack pose,
            AABB b,
            int color,
            boolean dashed,
            float width,
            float alpha,
            RenderType type) {
        for (int axis = 0; axis < 3; axis++)
            for (int u = 0; u < 2; u++)
                for (int v = 0; v < 2; v++) {
                    double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ};
                    int j = (axis + 1) % 3, k = (axis + 2) % 3;
                    double[] a = lo.clone(), z = lo.clone();
                    a[j] = z[j] = u == 0 ? lo[j] : hi[j];
                    a[k] = z[k] = v == 0 ? lo[k] : hi[k];
                    z[axis] = hi[axis];
                    var start = new Vector3f((float) a[0], (float) a[1], (float) a[2]);
                    var end = new Vector3f((float) z[0], (float) z[1], (float) z[2]);
                    if (!com.eynny.npships.authoring.geometry.SchematicViewProjection
                                    .projectSegment(screenMatrix(pose), clipDepth, start, end)
                            || !clip(start, end, g.guiWidth(), g.guiHeight())) continue;

                    float length = (float) Math.hypot(end.x - start.x, end.y - start.y);
                    int steps =
                            dashed ? Math.max(1, Math.min(4096, (int) Math.ceil(length / 5))) : 1;
                    for (int i = 0; i < steps; i++)
                        ribbon(
                                g,
                                new Vector3f(start).lerp(end, (float) i / steps),
                                new Vector3f(start).lerp(end, (i + (dashed ? .5f : 1f)) / steps),
                                color,
                                width,
                                alpha,
                                alpha,
                                type);
                }
    }

    // Bound tessellation by the viewport, not a huge off-screen segment at maximum zoom.
    private static boolean clip(Vector3f a, Vector3f b, int width, int height) {
        float lo = 0, hi = 1;
        var delta = new Vector3f(b).sub(a);
        for (int axis = 0; axis < 2; axis++) {
            float start = a.get(axis), d = delta.get(axis), max = (axis == 0 ? width : height) + 3;
            if (Math.abs(d) < .00001f) {
                if (start < -3 || start > max) return false;
                continue;
            }
            float t0 = (-3 - start) / d, t1 = (max - start) / d;
            lo = Math.max(lo, Math.min(t0, t1));
            hi = Math.min(hi, Math.max(t0, t1));
            if (lo > hi) return false;
        }
        b.set(a).fma(hi, delta);
        a.fma(lo, delta);
        return true;
    }

    private static void ribbon(
            GuiGraphics g,
            Vector3f a,
            Vector3f b,
            int color,
            float width,
            float alphaA,
            float alphaB) {
        ribbon(g, a, b, color, width, alphaA, alphaB, false);
    }

    private static void ribbon(
            GuiGraphics g,
            Vector3f a,
            Vector3f b,
            int color,
            float width,
            float alphaA,
            float alphaB,
            boolean depth) {
        ribbon(g, a, b, color, width, alphaA, alphaB, depth ? GRID : TYPE);
    }

    private static void ribbon(
            GuiGraphics g,
            Vector3f a,
            Vector3f b,
            int color,
            float width,
            float alphaA,
            float alphaB,
            RenderType type) {
        if (!a.isFinite() || !b.isFinite()) return;
        a = new Vector3f(a);
        b = new Vector3f(b);
        if (lens.m23() != 0) {
            var rawA = inverseLens.transformProject(new Vector3f(a));
            var rawB = inverseLens.transformProject(new Vector3f(b));
            if (rawA.z > nearZ && rawB.z > nearZ) return;
            if (rawA.z > nearZ)
                a = lens.transformProject(rawA.lerp(rawB, (nearZ - rawA.z) / (rawB.z - rawA.z)));
            else if (rawB.z > nearZ)
                b = lens.transformProject(rawB.lerp(rawA, (nearZ - rawB.z) / (rawA.z - rawB.z)));
        }
        if (!a.isFinite() || !b.isFinite() || !clip(a, b, g.guiWidth(), g.guiHeight())) return;
        boolean depth = type != TYPE;
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .001f) return;
        float px = -dy / length * width / 2, py = dx / length * width / 2;
        var out = g.bufferSource().getBuffer(type);
        int r = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        vertex(out, a.x + px, a.y + py, depth ? a.z : 0, r, green, blue, alphaA);
        vertex(out, a.x - px, a.y - py, depth ? a.z : 0, r, green, blue, alphaA);
        vertex(out, b.x - px, b.y - py, depth ? b.z : 0, r, green, blue, alphaB);
        vertex(out, b.x + px, b.y + py, depth ? b.z : 0, r, green, blue, alphaB);
    }

    private static void vertex(
            VertexConsumer out,
            float x,
            float y,
            float z,
            int r,
            int green,
            int blue,
            float alpha) {
        var p = inverseLens.transformProject(new Vector3f(x, y, z));
        out.addVertex(p.x, p.y, p.z)
                .setColor(r, green, blue, (int) (net.minecraft.util.Mth.clamp(alpha, 0, 1) * 255));
    }

    public static void link(
            GuiGraphics g,
            PoseStack pose,
            net.minecraft.world.phys.Vec3 a,
            net.minecraft.world.phys.Vec3 b,
            int color) {
        var start = a.toVector3f();
        var end = b.toVector3f();
        if (!com.eynny.npships.authoring.geometry.SchematicViewProjection.projectSegment(
                screenMatrix(pose), clipDepth, start, end)) return;
        ribbon(g, start, end, color, 1.5f, .8f, .8f);
    }

    public static void corners(GuiGraphics g, PoseStack pose, AABB b, int color) {
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++) {
                    var start =
                            new Vector3f(
                                    (float) (x == 0 ? b.minX : b.maxX),
                                    (float) (y == 0 ? b.minY : b.maxY),
                                    (float) (z == 0 ? b.minZ : b.maxZ));
                    for (int axis = 0; axis < 3; axis++) {
                        var end = new Vector3f(start);
                        end.setComponent(
                                axis,
                                end.get(axis) + (new int[] {x, y, z}[axis] == 0 ? .22f : -.22f));
                        var projectedStart = new Vector3f(start);
                        if (!com.eynny.npships.authoring.geometry.SchematicViewProjection
                                .projectSegment(screenMatrix(pose), clipDepth, projectedStart, end))
                            continue;
                        ribbon(g, projectedStart, end, color, 1.8f, 1, 1);
                    }
                }
    }

    public static void grid(
            GuiGraphics g, PoseStack pose, AABB b, int spacing, double fade, boolean black) {
        if (!b.equals(gridBounds) || gridSpacing != spacing || gridFade != fade) {
            gridSpacing = spacing;
            gridFade = fade;
            gridBounds = b;
            gridLines = com.eynny.npships.authoring.geometry.SchematicGrid.lines(b, spacing, fade);
        }
        for (var line : gridLines) {
            var p = line.start().toVector3f();
            var q = line.end().toVector3f();
            if (!com.eynny.npships.authoring.geometry.SchematicViewProjection.projectSegment(
                    screenMatrix(pose), clipDepth, p, q)) continue;
            ribbon(
                    g,
                    p,
                    q,
                    black ? 0x000000 : 0xffffff,
                    .8f,
                    line.opacity(line.startAlpha()),
                    line.opacity(line.endAlpha()),
                    true);
        }
    }

    /** View-facing far plane, unprojected through the active lens; never world/floor geometry. */
    public static void backdrop(
            GuiGraphics g,
            int left,
            int top,
            int right,
            int bottom,
            boolean black,
            float pixelsPerUnit) {
        backdrop(g, left, top, right, bottom, black, pixelsPerUnit, false);
    }

    public static void backdrop(
            GuiGraphics g,
            int left,
            int top,
            int right,
            int bottom,
            boolean black,
            float pixelsPerUnit,
            boolean snapshot) {
        float side = Math.max(1, Math.min(right - left, bottom - top));
        float step = Math.max(1, side / 16);
        float stroke = Math.max(1 / Math.max(.001f, pixelsPerUnit), side / 512);
        float z = -clipDepth * .999f;
        int color = black ? 0 : 0xffffff;
        // Bound work by the viewport; camera pan, schematic size and zoom do not add lines.
        for (int axis = 0; axis < 2; axis++) {
            float min = axis == 0 ? left : top, max = axis == 0 ? right : bottom;
            float center = (min + max) / 2;
            int count = Math.min(256, (int) Math.ceil((max - min) / step / 2));
            for (int i = -count; i <= count; i++) {
                float p = center + i * step;
                // The centered snapshot is 16 cells wide. Its first/last major lines sit
                // four cells inside the crop; only the top/left guides receive the extra weight.
                boolean major = i % 4 == 0 && (!snapshot || Math.abs(i) < 8);
                ribbon(
                        g,
                        new Vector3f(axis == 0 ? p : left, axis == 0 ? top : p, z),
                        new Vector3f(axis == 0 ? p : right, axis == 0 ? bottom : p, z),
                        color,
                        stroke * (major ? snapshot && i == -4 ? 12 : 3 : 1),
                        major ? .16f : .08f,
                        major ? .16f : .08f,
                        true);
            }
        }
    }
}
