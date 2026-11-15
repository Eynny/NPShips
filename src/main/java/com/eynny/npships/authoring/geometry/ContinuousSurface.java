package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** Conservative coverage of the transformed voxel boundary, supplementing interior sampling. */
final class ContinuousSurface {
    private ContinuousSurface() {}

    static void raster(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            Set<BlockPos> protectedBlocks,
            Function<Vec3, Vec3> forward,
            BiConsumer<Integer, BlockPos> sink) {
        var solid = AreaGeometry.occupied(source);
        solid.and(sourceMask);
        var size = source.size();
        for (int i = solid.nextSetBit(0); i >= 0; i = solid.nextSetBit(i + 1)) {
            var name = source.blockName(AreaGeometry.position(i, size));
            if (name.equals("minecraft:air")
                    || name.equals("minecraft:cave_air")
                    || name.equals("minecraft:void_air")) solid.clear(i);
        }
        // Bound refinement work for extreme folds instead of multiplying every face by 4^8.
        // Large surfaces get a coarser conservative triangle approximation, not an angle limit.
        int maxDepth = 0;
        long leaves = Math.max(1L, (long) solid.cardinality() * 6);
        while (maxDepth < 4 && leaves * 4 <= 262_144) {
            maxDepth++;
            leaves *= 4;
        }
        // Sorted voxel indices make material ownership deterministic when surfaces overlap.
        for (int i = solid.nextSetBit(0); i >= 0; i = solid.nextSetBit(i + 1)) {
            var p = AreaGeometry.position(i, size);

            for (var face : Direction.values()) {
                var neighbor = p.relative(face);
                if (source.contains(neighbor) && solid.get(AreaGeometry.index(neighbor, size)))
                    continue;
                int axis = face.getAxis().ordinal();
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                // Exact grid-aligned faces must not claim the air voxel on the other side.
                double[] origin = {p.getX() + 1e-6, p.getY() + 1e-6, p.getZ() + 1e-6};
                if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                    origin[axis] += 1 - 2e-6;
                Vec3 a = new Vec3(origin[0], origin[1], origin[2]);
                double[] du = new double[3], dv = new double[3];
                du[u] = 1 - 2e-6;
                dv[v] = 1 - 2e-6;
                var b = a.add(du[0], du[1], du[2]);
                var d = a.add(dv[0], dv[1], dv[2]);
                var c = b.add(dv[0], dv[1], dv[2]);
                quad(a, b, c, d, forward, target, cell -> sink.accept(cell, p), 0, maxDepth);
            }
        }
    }

    private static void quad(
            Vec3 a,
            Vec3 b,
            Vec3 c,
            Vec3 d,
            Function<Vec3, Vec3> map,
            BlockPos size,
            IntConsumer sink,
            int depth,
            int maxDepth) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        var ma = map.apply(a);
        var mb = map.apply(b);
        var mc = map.apply(c);
        var md = map.apply(d);
        var ab = a.lerp(b, .5);
        var bc = b.lerp(c, .5);
        var cd = c.lerp(d, .5);
        var da = d.lerp(a, .5);
        var mid = a.lerp(c, .5);
        double error =
                Math.max(
                        map.apply(mid).distanceToSqr(ma.add(mb).add(mc).add(md).scale(.25)),
                        Math.max(
                                Math.max(
                                        map.apply(ab).distanceToSqr(ma.lerp(mb, .5)),
                                        map.apply(bc).distanceToSqr(mb.lerp(mc, .5))),
                                Math.max(
                                        map.apply(cd).distanceToSqr(mc.lerp(md, .5)),
                                        map.apply(da).distanceToSqr(md.lerp(ma, .5)))));
        double edge =
                Math.max(
                        Math.max(ma.distanceToSqr(mb), mb.distanceToSqr(mc)),
                        Math.max(mc.distanceToSqr(md), md.distanceToSqr(ma)));
        // Refine curved faces to 0.01 blocks and keep triangle bounding-box work bounded.
        if (depth < maxDepth && (error > .0001 || edge > 16)) {
            quad(a, ab, mid, da, map, size, sink, depth + 1, maxDepth);
            quad(ab, b, bc, mid, map, size, sink, depth + 1, maxDepth);
            quad(mid, bc, c, cd, map, size, sink, depth + 1, maxDepth);
            quad(da, mid, cd, d, map, size, sink, depth + 1, maxDepth);
            return;
        }
        FittedSurface.triangle(ma, mb, mc, size, sink);
        FittedSurface.triangle(ma, mc, md, size, sink);
    }
}
