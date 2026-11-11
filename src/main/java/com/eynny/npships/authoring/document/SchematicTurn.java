package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;
import com.simibubi.create.content.contraptions.StructureTransform;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Atomic quarter turn, including the detached authoring graph and embedded variants. */
public final class SchematicTurn {
    private SchematicTurn() {}

    private static BlockPos size(BlockPos s) {
        return new BlockPos(s.getZ(), s.getY(), s.getX());
    }

    private static BlockPos point(BlockPos p, BlockPos s, boolean right) {
        return right
                ? new BlockPos(s.getZ() - 1 - p.getZ(), p.getY(), p.getX())
                : new BlockPos(p.getZ(), p.getY(), s.getX() - 1 - p.getX());
    }

    public static SchematicRegions.Region region(
            SchematicRegions.Region region, BlockPos size, boolean right) {
        var a = point(region.origin(), size, right);
        var b = point(region.origin().offset(region.size()).offset(-1, -1, -1), size, right);
        return new SchematicRegions.Region(
                new BlockPos(
                        Math.min(a.getX(), b.getX()),
                        Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ())),
                size(region.size()));
    }

    private static Vec3 vector(Vec3 p, boolean right) {
        return right ? new Vec3(-p.z, p.y, p.x) : new Vec3(p.z, p.y, -p.x);
    }

    private static Vec3 point(Vec3 p, BlockPos s, boolean right) {
        return vector(p, right).add(right ? s.getZ() : 0, 0, right ? 0 : s.getX());
    }

    private static Vec3 vec(ListTag p) {
        return new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2));
    }

    private static ListTag list(Vec3 p) {
        var out = new ListTag();
        out.add(DoubleTag.valueOf(p.x));
        out.add(DoubleTag.valueOf(p.y));
        out.add(DoubleTag.valueOf(p.z));
        return out;
    }

    public static SchematicDocument turn(SchematicDocument source, boolean right, Level level) {
        var omittedFields = SchematicCaptureReceipts.omittedFields(source);
        var oldSize = source.size();
        var tag = source.nbtCopy();
        var config = SchematicPatches.authoringConfig(source);
        SchematicPatches.embed(tag, config);
        var rotation = right ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
        var transform =
                new StructureTransform(
                        right
                                ? new BlockPos(oldSize.getZ() - 1, 0, 0)
                                : new BlockPos(0, 0, oldSize.getX() - 1),
                        Direction.Axis.Y,
                        rotation,
                        Mirror.NONE);
        var preview = new net.createmod.catnip.levelWrappers.SchematicLevel(BlockPos.ZERO, level);
        var original = source.paletteCopy();
        var palettes = new ArrayList<ListTag>();
        if (tag.contains("palette", Tag.TAG_LIST))
            palettes.add(tag.getList("palette", Tag.TAG_COMPOUND));
        else for (var value : tag.getList("palettes", Tag.TAG_LIST)) palettes.add((ListTag) value);
        for (var palette : palettes)
            for (int i = 0; i < palette.size(); i++)
                palette.set(
                        i,
                        NbtUtils.writeBlockState(
                                transform.apply(
                                        NbtUtils.readBlockState(
                                                level.holderLookup(Registries.BLOCK),
                                                palette.getCompound(i)))));
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var b = (CompoundTag) value;
            var old = SchematicPatches.blockPosition(b);
            var next = point(old, oldSize, right);
            b.put("pos", StructureSegments.ints(next));
            for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                code.rotateRecord(b, rotation);
            if (b.contains("nbt")) {
                var data = b.getCompound("nbt");
                var state =
                        NbtUtils.readBlockState(
                                level.holderLookup(Registries.BLOCK),
                                original.getCompound(b.getInt("state")));
                // Use registered Create/mod NBT transformers on detached block entities.
                if (state.getBlock() instanceof EntityBlock factory) {
                    var defaults = factory.newBlockEntity(old, state);
                    if (defaults != null)
                        data = defaults.saveWithoutMetadata(level.registryAccess()).merge(data);
                }
                var be = BlockEntity.loadStatic(old, state, data, level.registryAccess());
                if (be == null && state.getBlock() instanceof EntityBlock factory) {
                    be = factory.newBlockEntity(old, state);
                    if (be != null) {
                        be.setLevel(preview);
                        be.loadWithComponents(data, level.registryAccess());
                    }
                }
                if (be != null) {
                    be.setLevel(preview);
                    transform.apply(be);
                    var saved = be.saveWithFullMetadata(level.registryAccess());
                    data = data.copy();
                    data.merge(saved);
                }
                // Native serialization can recreate omitted default fields. Their source values
                // were already validated absent; providers still face strict final validation.
                for (String key : omittedFields.getOrDefault(old, Set.of())) data.remove(key);
                CompoundTag originalNative = null;
                for (var code :
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                    if (code.rotatesData(state)) {
                        if (originalNative == null) originalNative = source.blockEntityData(old);
                        code.rotateData(state, originalNative, data, rotation);
                    }
                data.putInt("x", next.getX());
                data.putInt("y", next.getY());
                data.putInt("z", next.getZ());
                b.put("nbt", data);
            }
            if (b.contains(SchematicPatches.EMBEDDED)) {
                var patches = SchematicPatches.embeddedEntries(b);
                patches.forEach(patch -> rotatePatch(patch, oldSize, right));
                SchematicPatches.writeEmbedded(b, patches);
            }
        }
        for (var value : tag.getList("entities", Tag.TAG_COMPOUND)) {
            var e = (CompoundTag) value;
            var data = e.getCompound("nbt");
            var position = vec(e.getList("pos", Tag.TAG_DOUBLE));
            var next =
                    SchematicGlue.supported(data.getString("id"))
                            ? SchematicGlue.transformVolume(
                                    data, position, p -> point(p, oldSize, right))
                            : point(position, oldSize, right);
            e.put("pos", list(next));
            var bp = e.getList("blockPos", Tag.TAG_INT);
            e.put(
                    "blockPos",
                    StructureSegments.ints(
                            point(
                                    new BlockPos(bp.getInt(0), bp.getInt(1), bp.getInt(2)),
                                    oldSize,
                                    right)));
            entity(e.getCompound("nbt"), oldSize, right);
        }
        tag.put("size", StructureSegments.ints(size(oldSize)));
        rotateLoot(config, oldSize, right);
        com.eynny.npships.authoring.AssemblyPoints.map(
                config, p -> point(p, oldSize, right), p -> true);
        var patches = new JsonArray();
        for (var patch : SchematicPatches.read(config)) {
            rotatePatch(patch, oldSize, right);
            patches.add(patch);
        }
        if (config.has("patches"))
            SchematicPatches.set(
                    config, patches.asList().stream().map(JsonElement::getAsJsonObject).toList());
        for (String key : List.of("segments", "crew"))
            if (config.has(key))
                for (var value : config.getAsJsonArray(key)) {
                    var zone = value.getAsJsonObject();
                    var origin = SchematicPatches.position(zone.getAsJsonArray("origin"));
                    var extent = SchematicPatches.position(zone.getAsJsonArray("size"));
                    var a = point(origin, oldSize, right);
                    var b = point(origin.offset(extent).offset(-1, -1, -1), oldSize, right);
                    zone.add(
                            "origin",
                            SchematicPatches.vector(
                                    new BlockPos(
                                            Math.min(a.getX(), b.getX()),
                                            a.getY(),
                                            Math.min(a.getZ(), b.getZ()))));
                    zone.add("size", SchematicPatches.vector(size(extent)));
                    if (key.equals("segments") && zone.has("pool"))
                        for (var entry : zone.getAsJsonArray("pool"))
                            rotateLoot(entry.getAsJsonObject(), extent, right);
                }
        var variants = tag.getCompound(StructureSegments.VARIANTS);
        for (String id : Set.copyOf(variants.getAllKeys()))
            variants.put(
                    id,
                    turn(
                                    com.eynny.npships.authoring.AssemblyPoints.segmentDocument(
                                            variants.getCompound(id), null),
                                    right,
                                    level)
                            .nbtCopy());
        SchematicPatches.embed(tag, config);
        var result = new SchematicDocument(tag, config);
        StructureSegments.validate(tag, StructureSegments.read(config));
        StructureCrew.validateBounds(result, config);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.apply(
                        source, result, SchematicExtensions.turn(oldSize, right))
                : result;
    }

    private static void rotateLoot(JsonObject config, BlockPos size, boolean right) {
        var old = LootAssignments.read(config);
        config.remove(LootAssignments.KEY);
        for (var e : old) {
            var changed =
                    LootAssignments.assign(
                            config, point(e.pos(), size, right), e.table(), e.rolls());
            for (var v : changed.entrySet()) config.add(v.getKey(), v.getValue());
        }
    }

    private static void rotatePatch(JsonObject patch, BlockPos size, boolean right) {
        PatchInstances.stabilize(patch, false);
        patch.add(
                "pos",
                SchematicPatches.vector(
                        point(
                                SchematicPatches.position(patch.getAsJsonArray("pos")),
                                size,
                                right)));
        offsets(patch, right);
    }

    private static void offsets(JsonElement value, boolean right) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject())
            for (var e : value.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("offset")) {
                    var p = SchematicPatches.position(e.getValue().getAsJsonArray());
                    var r = vector(Vec3.atLowerCornerOf(p), right);
                    e.setValue(SchematicPatches.vector(BlockPos.containing(r)));
                } else offsets(e.getValue(), right);
            }
        else if (value.isJsonArray()) for (var e : value.getAsJsonArray()) offsets(e, right);
    }

    private static ListTag rotation(ListTag angles, boolean right) {
        var out = new ListTag();
        out.add(FloatTag.valueOf(angles.getFloat(0) + (right ? 90 : -90)));
        out.add(FloatTag.valueOf(angles.getFloat(1)));
        return out;
    }

    private static void entity(CompoundTag data, BlockPos size, boolean right) {
        if (data.contains("Pos", Tag.TAG_LIST)) {
            var position = vec(data.getList("Pos", Tag.TAG_DOUBLE));
            data.put("Pos", list(point(position, size, right)));
        }
        if (data.contains("Motion", Tag.TAG_LIST))
            data.put("Motion", list(vector(vec(data.getList("Motion", Tag.TAG_DOUBLE)), right)));
        data.put("Rotation", rotation(data.getList("Rotation", Tag.TAG_FLOAT), right));
        EntitySettings.transformPendingRotation(data, angles -> rotation(angles, right));
        if (data.getString("id").equals("minecraft:painting")
                && data.contains("facing", Tag.TAG_BYTE)) {
            var dir = Direction.from2DDataValue(data.getByte("facing"));
            data.putByte(
                    "facing",
                    (byte)
                            (right ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90)
                                    .rotate(dir)
                                    .get2DDataValue());
        }
        if (data.contains("Facing", Tag.TAG_BYTE)) {
            var dir = Direction.from3DDataValue(data.getByte("Facing"));
            data.putByte(
                    "Facing",
                    (byte)
                            (right ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90)
                                    .rotate(dir)
                                    .get3DDataValue());
        }
        if (data.contains("TileX")) {
            var p =
                    point(
                            new BlockPos(
                                    data.getInt("TileX"),
                                    data.getInt("TileY"),
                                    data.getInt("TileZ")),
                            size,
                            right);
            data.putInt("TileX", p.getX());
            data.putInt("TileY", p.getY());
            data.putInt("TileZ", p.getZ());
        }
        for (var passenger : data.getList("Passengers", Tag.TAG_COMPOUND))
            entity((CompoundTag) passenger, size, right);
    }
}
