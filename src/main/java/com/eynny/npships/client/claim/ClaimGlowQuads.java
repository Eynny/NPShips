package com.eynny.npships.client.claim;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts only authored cyan/blue/green/red texels; source textures are never modified. */
final class ClaimGlowQuads {
    // Keep the mask above the body, joining incident face offsets at shared edges.
    // Independent normal lifts leave a visible slit at convex corners.
    static final float SURFACE_LIFT = .0005f;
    private static final Direction[] DIRECTIONS = Direction.values();

    private ClaimGlowQuads() {}

    static List<BakedQuad> build(List<BakedQuad> source) {
        var edges = edgeNormals(source);
        var images = new HashMap<ResourceLocation, NativeImage>();
        var result = new ArrayList<BakedQuad>();
        try {
            for (var quad : source) {
                var sprite = quad.getSprite();
                var name = sprite.contents().name();
                var image = images.get(name);
                if (image == null) {
                    var path =
                            ResourceLocation.fromNamespaceAndPath(
                                    name.getNamespace(), "textures/" + name.getPath() + ".png");
                    try (var stream = Minecraft.getInstance().getResourceManager().open(path)) {
                        image = NativeImage.read(stream);
                    }
                    images.put(name, image);
                }
                var vertices = quad.getVertices();
                int stride = vertices.length / 4;
                var normal =
                        point(vertices, stride)
                                .sub(point(vertices, 0))
                                .cross(point(vertices, 3 * stride).sub(point(vertices, 0)))
                                .normalize();
                if (normal.dot(quad.getDirection().step()) < 0) normal.negate();
                int[] joins = new int[4];
                for (int edge = 0; edge < 4; edge++) {
                    joins[edge] =
                            edges.get(
                                    new Edge(
                                            Point.at(vertices, edge * stride),
                                            Point.at(vertices, (edge + 1) % 4 * stride)));
                }
                double[] u = new double[4], v = new double[4];
                for (int i = 0; i < 4; i++) {
                    u[i] =
                            (Float.intBitsToFloat(vertices[i * stride + 4]) - sprite.getU0())
                                    / (sprite.getU1() - sprite.getU0())
                                    * image.getWidth();
                    v[i] =
                            (Float.intBitsToFloat(vertices[i * stride + 5]) - sprite.getV0())
                                    / (sprite.getV1() - sprite.getV0())
                                    * image.getHeight();
                }
                double du1 = u[1] - u[0], dv1 = v[1] - v[0];
                double du3 = u[3] - u[0], dv3 = v[3] - v[0];
                double determinant = du1 * dv3 - du3 * dv1;
                if (Math.abs(determinant) < 1e-8) continue;
                double minU = java.util.Arrays.stream(u).min().orElseThrow();
                double maxU = java.util.Arrays.stream(u).max().orElseThrow();
                double minV = java.util.Arrays.stream(v).min().orElseThrow();
                double maxV = java.util.Arrays.stream(v).max().orElseThrow();
                for (int y = Math.max(0, (int) Math.floor(minV));
                        y < Math.min(image.getHeight(), Math.ceil(maxV));
                        y++) {
                    for (int x = Math.max(0, (int) Math.floor(minU));
                            x < Math.min(image.getWidth(), Math.ceil(maxU));
                            x++) {
                        int pixel = image.getPixelRGBA(x, y);
                        int r = pixel & 255, g = (pixel >>> 8) & 255, b = (pixel >>> 16) & 255;
                        if ((pixel >>> 24) == 0
                                || Math.max(r, Math.max(g, b)) < 55
                                || !(b > r * 1.35 && g > r * 1.15
                                        || g > r * 1.5 && g > b * 1.2
                                        || name.getPath().endsWith("claim_block_deny")
                                                && r > 100
                                                && r > g * 2.5
                                                && r > b * 2.5)) continue;
                        double left = Math.max(x, minU), right = Math.min(x + 1, maxU);
                        double top = Math.max(y, minV), bottom = Math.min(y + 1, maxV);
                        if (right - left < .001 || bottom - top < .001) continue;
                        int[] copy = vertices.clone();
                        // Preserve the original UV winding, including rotated/mirrored faces.
                        for (int i = 0; i < 4; i++) {
                            double pu = u[i] < (minU + maxU) / 2 ? left : right;
                            double pv = v[i] < (minV + maxV) / 2 ? top : bottom;
                            double s = ((pu - u[0]) * dv3 - (pv - v[0]) * du3) / determinant;
                            double t = (du1 * (pv - v[0]) - dv1 * (pu - u[0])) / determinant;
                            int incident = 0;
                            if (Math.abs(t) < 1e-6) incident |= joins[0];
                            if (Math.abs(s - 1) < 1e-6) incident |= joins[1];
                            if (Math.abs(t - 1) < 1e-6) incident |= joins[2];
                            if (Math.abs(s) < 1e-6) incident |= joins[3];
                            var offset = new Vector3f(normal);
                            for (var direction : DIRECTIONS) {
                                if (direction.getAxis() == quad.getDirection().getAxis()) continue;
                                int bit = 1 << direction.ordinal();
                                int opposite = 1 << direction.getOpposite().ordinal();
                                // An ambiguous/non-manifold edge must not gain an arbitrary side.
                                if ((incident & bit) != 0 && (incident & opposite) == 0)
                                    offset.add(direction.step());
                            }
                            for (int axis = 0; axis < 3; axis++) {
                                float origin = Float.intBitsToFloat(vertices[axis]);
                                double pos =
                                        origin
                                                + s
                                                        * (Float.intBitsToFloat(
                                                                        vertices[stride + axis])
                                                                - origin)
                                                + t
                                                        * (Float.intBitsToFloat(
                                                                        vertices[3 * stride + axis])
                                                                - origin);
                                copy[i * stride + axis] =
                                        Float.floatToRawIntBits(
                                                (float) pos + offset.get(axis) * SURFACE_LIFT);
                            }
                            copy[i * stride + 4] =
                                    Float.floatToRawIntBits(
                                            sprite.getU0()
                                                    + (float) (pu / image.getWidth())
                                                            * (sprite.getU1() - sprite.getU0()));
                            copy[i * stride + 5] =
                                    Float.floatToRawIntBits(
                                            sprite.getV0()
                                                    + (float) (pv / image.getHeight())
                                                            * (sprite.getV1() - sprite.getV0()));
                        }
                        result.add(
                                new BakedQuad(copy, -1, quad.getDirection(), sprite, false, false));
                    }
                }
            }
        } catch (java.io.IOException e) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to build Claim indicator glow", e);
        } finally {
            images.values().forEach(NativeImage::close);
        }
        return List.copyOf(result);
    }

    private static Map<Edge, Integer> edgeNormals(List<BakedQuad> source) {
        var result = new HashMap<Edge, Integer>();
        for (var quad : source) {
            var vertices = quad.getVertices();
            int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++)
                result.merge(
                        new Edge(
                                Point.at(vertices, i * stride),
                                Point.at(vertices, (i + 1) % 4 * stride)),
                        1 << quad.getDirection().ordinal(),
                        (a, b) -> a | b);
        }
        return result;
    }

    private record Point(float x, float y, float z) implements Comparable<Point> {
        static Point at(int[] vertices, int offset) {
            return new Point(
                    Float.intBitsToFloat(vertices[offset]),
                    Float.intBitsToFloat(vertices[offset + 1]),
                    Float.intBitsToFloat(vertices[offset + 2]));
        }

        public int compareTo(Point other) {
            int order = Float.compare(x, other.x);
            if (order == 0) order = Float.compare(y, other.y);
            return order == 0 ? Float.compare(z, other.z) : order;
        }
    }

    private record Edge(Point a, Point b) {
        Edge {
            if (a.compareTo(b) > 0) {
                var swap = a;
                a = b;
                b = swap;
            }
        }
    }

    private static Vector3f point(int[] vertices, int offset) {
        return new Vector3f(
                Float.intBitsToFloat(vertices[offset]),
                Float.intBitsToFloat(vertices[offset + 1]),
                Float.intBitsToFloat(vertices[offset + 2]));
    }
}
