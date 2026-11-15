package com.eynny.npships.client.editor;

import com.eynny.npships.client.editor.widget.EditorButtonTheme;
import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Icon-only footprint editing. Screen-space axes keep edge-on dragging well defined. */
final class SnapshotGridHandles {
    private AABB bounds, start;
    private int edge = -1;
    private float alpha;
    private long lastFrame;
    private double mouseX, mouseY, axisX, axisY;

    AABB bounds(BlockPos size) {
        if (bounds == null) bounds = new AABB(0, 0, 0, size.getX(), size.getY(), size.getZ());
        return bounds;
    }

    private Vector3f[] corners(Matrix4f camera) {
        return new Vector3f[] {
            com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                    camera, new Vector3f((float) bounds.minX, 0, (float) bounds.minZ)),
            com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                    camera, new Vector3f((float) bounds.maxX, 0, (float) bounds.minZ)),
            com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                    camera, new Vector3f((float) bounds.maxX, 0, (float) bounds.maxZ)),
            com.eynny.npships.authoring.geometry.SchematicViewProjection.projectHandle(
                    camera, new Vector3f((float) bounds.minX, 0, (float) bounds.maxZ))
        };
    }

    private int hit(double x, double y, Vector3f[] points) {
        double best = 64;
        int found = -1;
        for (int i = 0; i < 4; i++) {
            var a = points[i];
            var b = points[(i + 1) % 4];
            if (!a.isFinite() || !b.isFinite()) continue;
            double dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
            if (length < 1) continue;
            double t = Math.max(0, Math.min(1, ((x - a.x) * dx + (y - a.y) * dy) / length));
            double ex = x - a.x - t * dx, ey = y - a.y - t * dy, distance = ex * ex + ey * ey;
            if (distance < best) {
                best = distance;
                found = i;
            }
        }
        return found;
    }

    boolean begin(double x, double y, Matrix4f camera) {
        if (bounds == null) return false;
        int picked = hit(x, y, corners(camera));
        if (picked < 0) return false;
        var axis =
                com.eynny.npships.authoring.geometry.SchematicViewProjection.direction(
                        camera,
                        bounds.getCenter().toVector3f(),
                        new Vector3f(picked % 2 == 1 ? 1 : 0, 0, picked % 2 == 0 ? 1 : 0));
        if (axis.x * axis.x + axis.y * axis.y < .01) return false;
        edge = picked;
        start = bounds;
        mouseX = x;
        mouseY = y;
        axisX = axis.x;
        axisY = axis.y;
        return true;
    }

    boolean drag(double x, double y) {
        if (edge < 0) return false;
        double delta =
                Math.rint(
                        ((x - mouseX) * axisX + (y - mouseY) * axisY)
                                / (axisX * axisX + axisY * axisY));
        double x0 = start.minX, x1 = start.maxX, z0 = start.minZ, z1 = start.maxZ;
        // Bounded footprint prevents an accidental extreme drag generating millions of grid lines.
        double limit = 4096;
        switch (edge) {
            case 0 -> z0 = Math.max(z1 - limit, Math.min(z1 - 1, z0 + delta));
            case 1 -> x1 = Math.min(x0 + limit, Math.max(x0 + 1, x1 + delta));
            case 2 -> z1 = Math.min(z0 + limit, Math.max(z0 + 1, z1 + delta));
            case 3 -> x0 = Math.max(x1 - limit, Math.min(x1 - 1, x0 + delta));
        }
        bounds = new AABB(x0, 0, z0, x1, start.maxY, z1);
        return true;
    }

    boolean end() {
        boolean active = edge >= 0;
        edge = -1;
        return active;
    }

    void render(GuiGraphics g, Matrix4f camera, double x, double y, boolean pinned) {
        var points = corners(camera);
        int hover = hit(x, y, points);
        long now = net.minecraft.Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        alpha =
                Math.max(
                        0,
                        Math.min(1, alpha + (pinned || hover >= 0 || edge >= 0 ? 1 : -1) * dt * 2));
        if (alpha <= 0) return;
        for (int i = 0; i < 4; i++) {
            var a = points[i];
            var b = points[(i + 1) % 4];
            if (!a.isFinite() || !b.isFinite()) continue;
            int colour =
                    i == edge || i == hover ? EditorButtonTheme.text() : EditorButtonTheme.accent();
            colour = (colour & 0xffffff) | ((int) (alpha * 255) << 24);
            double length = Math.hypot(b.x - a.x, b.y - a.y);
            g.pose().pushPose();
            g.pose().translate(a.x, a.y, 400);
            g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(b.y - a.y, b.x - a.x)));
            g.fill(0, -1, (int) Math.ceil(length), 1, colour);
            g.pose().popPose();
            int mx = (int) ((a.x + b.x) / 2), my = (int) ((a.y + b.y) / 2);
            g.fill(mx - 3, my - 3, mx + 4, my + 4, colour);
        }
    }
}
