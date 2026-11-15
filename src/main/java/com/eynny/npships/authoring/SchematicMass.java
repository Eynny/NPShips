package com.eynny.npships.authoring;

import com.eynny.npships.authoring.document.SchematicDocument;

import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;

import it.unimi.dsi.fastutil.longs.*;

import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.*;

import java.util.*;

/** Face-connected document components; not an assembler or a running physics simulation. */
public final class SchematicMass {
    public record Part(int blocks, double mass, Vec3 center, Vec3 geometricCenter, BlockPos size) {}

    /** Main-thread incremental calculation: addon block callbacks must not run on a worker. */
    public static final class Analysis {
        private final BlockGetter level;
        private final AABB region;
        private final Iterator<BlockPos> scan;
        private final LongLinkedOpenHashSet remaining = new LongLinkedOpenHashSet();
        private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        private final int total;
        private int scanned, processed;
        private static final Direction[] NEIGHBORS = Direction.values();
        private final List<Part> parts = new ArrayList<>();
        private boolean done;
        private int count;
        private double mass, x, y, z;
        private int minX, minY, minZ, maxX, maxY, maxZ;

        public Analysis(SchematicDocument doc, BlockGetter level, AABB region) {
            this.level = level;
            this.region = region;
            scan = doc.blocks().keySet().iterator();
            total = doc.blocks().size();
        }

        public boolean step(int budget) {
            if (done) return true;
            while (budget-- > 0) {
                if (scan.hasNext()) {
                    var p = scan.next();
                    scanned++;
                    if ((region == null || region.contains(Vec3.atCenterOf(p)))
                            && !level.getBlockState(p).isAir()) remaining.add(p.asLong());
                    continue;
                }
                if (queue.isEmpty()) {
                    if (count > 0) {
                        parts.add(
                                new Part(
                                        count,
                                        mass,
                                        mass > 0 ? new Vec3(x / mass, y / mass, z / mass) : null,
                                        new Vec3(
                                                (minX + maxX + 1) / 2.0,
                                                (minY + maxY + 1) / 2.0,
                                                (minZ + maxZ + 1) / 2.0),
                                        new BlockPos(
                                                maxX - minX + 1,
                                                maxY - minY + 1,
                                                maxZ - minZ + 1)));
                        count = 0;
                        mass = x = y = z = 0;
                    }
                    if (remaining.isEmpty()) {
                        parts.sort(Comparator.comparingDouble(Part::mass).reversed());
                        done = true;
                        return true;
                    }
                    queue.enqueue(remaining.removeFirstLong());
                }
                var p = BlockPos.of(queue.dequeueLong());
                processed++;
                if (count == 0) {
                    minX = maxX = p.getX();
                    minY = maxY = p.getY();
                    minZ = maxZ = p.getZ();
                } else {
                    minX = Math.min(minX, p.getX());
                    maxX = Math.max(maxX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxY = Math.max(maxY, p.getY());
                    minZ = Math.min(minZ, p.getZ());
                    maxZ = Math.max(maxZ, p.getZ());
                }
                count++;
                var state = level.getBlockState(p);
                if (VoxelNeighborhoodState.isSolid(level, p, state)) {
                    double m = PhysicsBlockPropertyHelper.getMass(level, p, state);
                    if (!Double.isFinite(m) || m < 0)
                        throw new IllegalArgumentException("Invalid block mass at " + p);
                    var c = MassTracker.BLOCK_CENTER_OF_MASS.apply(level, state);
                    mass += m;
                    x += m * (p.getX() + c.x());
                    y += m * (p.getY() + c.y());
                    z += m * (p.getZ() + c.z());
                }
                for (var d : NEIGHBORS) {
                    long n =
                            BlockPos.asLong(
                                    p.getX() + d.getStepX(),
                                    p.getY() + d.getStepY(),
                                    p.getZ() + d.getStepZ());
                    if (remaining.remove(n)) queue.enqueue(n);
                }
            }
            return false;
        }

        /** Small batches retain main-thread addon callbacks while respecting the frame budget. */
        public boolean stepForNanos(long nanos) {
            long deadline = System.nanoTime() + Math.max(1, nanos);
            do {
                if (step(128)) return true;
            } while (System.nanoTime() < deadline);
            return false;
        }

        public int progressPercent() {
            return done
                    ? 100
                    : (int) Math.min(99, 100L * (scanned + processed) / Math.max(1L, 2L * total));
        }

        public List<Part> result() {
            if (!done) throw new IllegalStateException("Calculation is incomplete");
            return List.copyOf(parts);
        }
    }

    public static List<Part> calculate(SchematicDocument doc, BlockGetter level, AABB region) {
        var a = new Analysis(doc, level, region);
        while (!a.step(4096)) {}
        return a.result();
    }

    private SchematicMass() {}
}
