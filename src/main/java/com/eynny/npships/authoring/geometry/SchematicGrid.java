package com.eynny.npships.authoring.geometry;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Uniform graph-paper grid with a scale-dependent fading border. */
public final class SchematicGrid {
    public record Line(Vec3 start, Vec3 end, float startAlpha, float endAlpha, boolean dashed) {
        public Line(Vec3 start, Vec3 end, float startAlpha, float endAlpha) {
            this(start, end, startAlpha, endAlpha, false);
        }

        public float opacity(float alpha) {
            return alpha * .28f;
        }
    }

    private SchematicGrid() {}

    /** mode: 0 auto, 1 sparse, 2 unit; resolution is measured in output pixels per block. */
    public static int spacing(double pixelsPerBlock, int mode) {
        if (mode == 2 || mode == 0 && pixelsPerBlock >= 6) return 1;
        int step = 4;
        while (step < 4096 && step * Math.max(.001, pixelsPerBlock) < 12) step *= 2;
        return step;
    }

    public static List<Line> lines(AABB bounds) {
        return lines(bounds, 1);
    }

    public static List<Line> lines(AABB bounds, int spacing) {
        return lines(bounds, spacing, 3);
    }

    public static List<Line> lines(AABB bounds, int spacing, double fadeCells) {
        if (!Double.isFinite(fadeCells) || fadeCells < 0 || fadeCells > 8)
            throw new IllegalArgumentException("Invalid grid fade");
        if (spacing < 1) throw new IllegalArgumentException("Grid spacing must be positive");
        var lines = new ArrayList<Line>();
        double fade = fadeCells * spacing;
        double step = spacing == 1 ? 1 : spacing / 2.0;
        for (int axis = 0; axis < 2; axis++) {
            double min = axis == 0 ? bounds.minX : bounds.minZ;
            double max = axis == 0 ? bounds.maxX : bounds.maxZ;
            double otherMin = axis == 0 ? bounds.minZ : bounds.minX;
            double otherMax = axis == 0 ? bounds.maxZ : bounds.maxX;
            // Keep the world-aligned lattice; only clip its segments and opacity plateau.
            // Snapping the footprint to whole cells shifts the fading edge away from the scheme.
            for (double i = Math.ceil((min - fade) / step) * step; i <= max + fade; i += step) {
                float opacity =
                        fade == 0
                                ? 1
                                : (float)
                                        Math.max(
                                                0,
                                                Math.min(1, 1 - Math.max(min - i, i - max) / fade));
                if (opacity <= 0) continue;
                boolean dashed = spacing > 1 && Math.round(i / step) % 2 != 0;
                double[] points = {otherMin - fade, otherMin, otherMax, otherMax + fade};
                float[] alpha = {0, opacity, opacity, 0};
                for (int n = 0; n < 3; n++) {
                    double length = points[n + 1] - points[n];
                    if (length <= 0) continue;
                    double period = dashed ? spacing / 4.0 : length;
                    double first = dashed ? Math.floor(points[n] / period) * period : points[n];
                    for (double at = first; at < points[n + 1]; at += period) {
                        double start = Math.max(points[n], at);
                        double end = Math.min(points[n + 1], at + (dashed ? period / 2 : length));
                        if (end <= start) continue;
                        double from = (start - points[n]) / length;
                        double to = (end - points[n]) / length;
                        float strength = dashed ? .65f : 1;
                        lines.add(
                                new Line(
                                        new Vec3(
                                                axis == 0 ? i : start,
                                                bounds.minY - .02,
                                                axis == 0 ? start : i),
                                        new Vec3(
                                                axis == 0 ? i : end,
                                                bounds.minY - .02,
                                                axis == 0 ? end : i),
                                        strength
                                                * (float)
                                                        (alpha[n]
                                                                + (alpha[n + 1] - alpha[n]) * from),
                                        strength
                                                * (float)
                                                        (alpha[n] + (alpha[n + 1] - alpha[n]) * to),
                                        dashed));
                    }
                }
            }
        }
        return List.copyOf(lines);
    }
}
