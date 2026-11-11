package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.*;
import com.eynny.npships.authoring.patches.PatchVolumeFormat;

import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;

/** Both supported glue entities store From/To relative to their bottom-centre position. */
public final class NativeGlueFormat implements PatchVolumeFormat {
    private final boolean honey;

    public NativeGlueFormat(boolean honey) {
        this.honey = honey;
    }

    @Override
    public void capture(
            net.minecraft.server.level.ServerLevel level, AABB selection, CompoundTag template) {
        if (!honey)
            com.simibubi.create.content.schematics.SchematicAndQuillItem.clampGlueBoxes(
                    level, selection, template);
    }

    @Override
    public AABB bounds(CompoundTag data, Vec3 position) {
        var from = data.getList("From", Tag.TAG_DOUBLE);
        var to = data.getList("To", Tag.TAG_DOUBLE);
        return new AABB(
                position.add(from.getDouble(0), from.getDouble(1), from.getDouble(2)),
                position.add(to.getDouble(0), to.getDouble(1), to.getDouble(2)));
    }

    @Override
    public net.minecraft.world.entity.Entity restore(
            net.minecraft.server.level.ServerLevel level, AABB bounds, CompoundTag saved) {
        if (honey) return Honey.restore(level, bounds, saved);
        var entity =
                new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level, bounds);
        var data = saved.copy();
        data.remove("UUID");
        entity.load(data);
        entity.setPos(
                (bounds.minX + bounds.maxX) * .5, bounds.minY, (bounds.minZ + bounds.maxZ) * .5);
        entity.setBoundingBox(bounds);
        return entity;
    }

    /** Loaded only when the installed Simulated provider restores an actual honey volume. */
    private static final class Honey {
        static net.minecraft.world.entity.Entity restore(
                net.minecraft.server.level.ServerLevel level, AABB bounds, CompoundTag saved) {
            var entity =
                    new dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity(
                            level, bounds);
            var data = saved.copy();
            data.remove("UUID");
            entity.load(data);
            entity.setBounds(bounds);
            return entity;
        }

        static void admitted(net.minecraft.world.entity.Entity entity) {
            var glue =
                    (dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity)
                            entity;
            glue.setBoundsAndSync(glue.getBoundingBox());
        }
    }

    @Override
    public void admitted(net.minecraft.world.entity.Entity entity) {
        if (honey) Honey.admitted(entity);
    }

    public String type() {
        return honey ? "simulated:honey_glue" : "create:super_glue";
    }

    public String label() {
        return honey ? "glue_honey" : "glue_normal";
    }

    public int color() {
        return honey ? 0xffb743 : 0x65df68;
    }

    public int order() {
        return honey ? 1 : 0;
    }

    public SchematicDocument put(SchematicDocument source, int index, AABB bounds) {
        var size = source.size();
        if (!Double.isFinite(bounds.getSize())
                || bounds.getXsize() < 1
                || bounds.getYsize() < 1
                || bounds.getZsize() < 1
                || bounds.minX < 0
                || bounds.minY < 0
                || bounds.minZ < 0
                || bounds.maxX > size.getX()
                || bounds.maxY > size.getY()
                || bounds.maxZ > size.getZ())
            throw new IllegalArgumentException(
                    "Glue bounds must fit inside the schematic and span at least one block per axis.");
        var pos =
                new Vec3(
                        (bounds.minX + bounds.maxX) / 2,
                        bounds.minY,
                        (bounds.minZ + bounds.maxZ) / 2);
        var data =
                index < 0
                        ? new CompoundTag()
                        : source.nbtCopy()
                                .getList("entities", Tag.TAG_COMPOUND)
                                .getCompound(index)
                                .getCompound("nbt")
                                .copy();
        data.putString("id", type());
        data.put("From", vector(new Vec3(bounds.minX, bounds.minY, bounds.minZ).subtract(pos)));
        data.put("To", vector(new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ).subtract(pos)));
        return SchematicEdits.entity(source, index, pos, data);
    }

    /**
     * Map a supported volume through an exact axis-aligned rigid transform. The caller must
     * establish representability first; an AABB is not a substitute for rotated/nonlinear glue.
     * Return the native bottom-centre anchor, rather than rotating the old anchor: both native
     * entities recenter their box around that anchor when subsequently positioned.
     */
    public Vec3 transform(
            CompoundTag data, Vec3 position, java.util.function.Function<Vec3, Vec3> forward) {
        var from = data.getList("From", Tag.TAG_DOUBLE);
        var to = data.getList("To", Tag.TAG_DOUBLE);
        if (from.size() != 3 || to.size() != 3) return forward.apply(position);
        var bounds =
                new AABB(
                                new Vec3(from.getDouble(0), from.getDouble(1), from.getDouble(2)),
                                new Vec3(to.getDouble(0), to.getDouble(1), to.getDouble(2)))
                        .move(position);
        Vec3 lo = null, hi = null;
        for (int i = 0; i < 8; i++) {
            var p =
                    forward.apply(
                            new Vec3(
                                    (i & 1) == 0 ? bounds.minX : bounds.maxX,
                                    (i & 2) == 0 ? bounds.minY : bounds.maxY,
                                    (i & 4) == 0 ? bounds.minZ : bounds.maxZ));
            // Remove quarter-turn floating point residue before native block-bound flooring.
            p = new Vec3(gridBoundary(p.x), gridBoundary(p.y), gridBoundary(p.z));
            lo =
                    lo == null
                            ? p
                            : new Vec3(
                                    Math.min(lo.x, p.x), Math.min(lo.y, p.y), Math.min(lo.z, p.z));
            hi =
                    hi == null
                            ? p
                            : new Vec3(
                                    Math.max(hi.x, p.x), Math.max(hi.y, p.y), Math.max(hi.z, p.z));
        }
        var anchor = new Vec3((lo.x + hi.x) / 2, lo.y, (lo.z + hi.z) / 2);
        data.put("From", vector(lo.subtract(anchor)));
        data.put("To", vector(hi.subtract(anchor)));
        return anchor;
    }

    private static double gridBoundary(double value) {
        double integer = Math.rint(value);
        return Math.abs(value - integer) < 1e-8 ? integer : value;
    }

    private static ListTag vector(Vec3 p) {
        var list = new ListTag();
        list.add(DoubleTag.valueOf(p.x));
        list.add(DoubleTag.valueOf(p.y));
        list.add(DoubleTag.valueOf(p.z));
        return list;
    }
}
