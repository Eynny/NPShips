package com.eynny.npships.authoring.io;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** All world reads run synchronously on the authoritative server. Packaging happens afterward. */
public final class ExportCapture {
    public static final int MAX_VOLUME = 65536;

    private ExportCapture() {}

    public static SchematicDocument capture(
            ServerPlayer player, BlockPos first, BlockPos second, ExportProfile requested)
            throws Exception {
        var level = player.serverLevel();
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("World capture requires the server thread");
        if (!player.hasPermissions(2))
            throw new IllegalArgumentException(
                    "World capture requires operator permission level 2");
        var origin =
                new BlockPos(
                        Math.min(first.getX(), second.getX()),
                        Math.min(first.getY(), second.getY()),
                        Math.min(first.getZ(), second.getZ()));
        long x = Math.abs((long) first.getX() - second.getX()) + 1,
                y = Math.abs((long) first.getY() - second.getY()) + 1,
                z = Math.abs((long) first.getZ() - second.getZ()) + 1;
        if (x > 512 || y > 512 || z > 512 || x * y * z > MAX_VOLUME)
            throw new IllegalArgumentException("Export limit: 65536 blocks, 512 per axis.");
        var size = new BlockPos((int) x, (int) y, (int) z);
        var end = origin.offset(size).offset(-1, -1, -1);
        if (origin.getY() < level.getMinBuildHeight()
                || end.getY() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(origin)
                || !level.getWorldBorder().isWithinBounds(end))
            throw new IllegalArgumentException("Selection is outside world bounds.");
        if (origin.distSqr(player.blockPosition()) > 512.0 * 512
                || end.distSqr(player.blockPosition()) > 512.0 * 512)
            throw new IllegalArgumentException(
                    "Selection must be within 512 blocks of the author.");
        for (int cx = origin.getX() >> 4; cx <= end.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= end.getZ() >> 4; cz++)
                if (!level.hasChunk(cx, cz))
                    throw new IllegalArgumentException("Selection contains unloaded chunks.");
        if (com.eynny.npships.naturalspawn.NaturalSpawnPhysicalValidator.findOverlapDetails(
                        level, origin, size)
                != null)
            throw new IllegalArgumentException(
                    "Export currently requires world blocks. Disassemble the physical ship before capturing it.");
        var template = new StructureTemplate();
        template.fillFromWorld(level, origin, size, true, Blocks.AIR);
        CompoundTag tag = template.save(new CompoundTag());
        // Native fillEntityList appends an empty NBT record when Entity.save returns false
        // (e.g. passenger roots or nonpersistent helpers). Only this fresh native capture is
        // filtered: authored/imported opaque records and serialized passenger trees stay intact.
        var capturedEntities = tag.getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND);
        int kept = 0;
        for (int i = 0; i < capturedEntities.size(); i++) {
            var entry = capturedEntities.getCompound(i);
            if (entry.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    && entry.getCompound("nbt").isEmpty()) continue;
            if (kept != i) capturedEntities.set(kept, entry);
            kept++;
        }
        // Tail removal avoids ListTag's quadratic repeated shifting when many native saves refuse.
        while (capturedEntities.size() > kept) capturedEntities.remove(capturedEntities.size() - 1);
        com.simibubi.create.content.schematics.SchematicAndQuillItem.replaceStructureVoidWithAir(
                tag);
        com.eynny.npships.authoring.document.SchematicGlue.captureVolumes(
                level,
                new AABB(Vec3.atLowerCornerOf(origin), Vec3.atLowerCornerOf(origin.offset(size))),
                tag);
        var profile = requested;
        var document =
                AuthoringCreation.withProfile(
                        new SchematicDocument(tag, null), profile, level.getSeaLevel());
        return WorldContraptions.capture(level, origin, document);
    }
}
