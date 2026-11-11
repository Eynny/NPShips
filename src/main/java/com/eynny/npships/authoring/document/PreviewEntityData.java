package com.eynny.npships.authoring.document;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;

/** Translate saved world anchors before loading a detached schematic entity. */
public final class PreviewEntityData {
    private PreviewEntityData() {}

    public static void loadAt(
            Entity entity, CompoundTag saved, net.minecraft.core.BlockPos anchor) {
        var entry = new CompoundTag();
        entry.put("nbt", saved.copy());
        entry.put("blockPos", com.eynny.npships.authoring.StructureSegments.ints(anchor));
        var pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(anchor.getX() + .5));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(anchor.getY()));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(anchor.getZ() + .5));
        entry.put("pos", pos);
        load(entity, entry);
    }

    /**
     * Preserve the registered native attachment cell independently of an even painting's center.
     */
    public static net.minecraft.core.BlockPos translatedAnchor(
            CompoundTag entry,
            net.minecraft.world.phys.Vec3 translatedPosition,
            net.minecraft.core.BlockPos delta) {
        String id = entry.getCompound("nbt").getString("id");
        if (id.equals("minecraft:item_frame")
                || id.equals("minecraft:glow_item_frame")
                || id.equals("minecraft:painting")) {
            var anchor = entry.getList("blockPos", Tag.TAG_INT);
            if (anchor.size() != 3)
                throw new IllegalArgumentException(
                        "Native hanging entity requires an attachment cell");
            return new net.minecraft.core.BlockPos(
                            anchor.getInt(0), anchor.getInt(1), anchor.getInt(2))
                    .offset(delta);
        }
        return net.minecraft.core.BlockPos.containing(translatedPosition);
    }

    public static void load(Entity entity, CompoundTag entry) {
        var data = entry.getCompound("nbt").copy();
        data.remove("UUID");
        data.remove("Passengers");
        var position = entry.getList("pos", Tag.TAG_DOUBLE);
        data.put("Pos", position.copy());
        if (entity instanceof BlockAttachedEntity) {
            var anchor = entry.getList("blockPos", Tag.TAG_INT);
            data.putInt("TileX", anchor.getInt(0));
            data.putInt("TileY", anchor.getInt(1));
            data.putInt("TileZ", anchor.getInt(2));
        }
        entity.load(data);
    }
}
