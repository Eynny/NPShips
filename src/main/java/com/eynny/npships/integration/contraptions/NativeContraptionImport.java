package com.eynny.npships.integration.contraptions;

import com.eynny.npships.authoring.SchematicSafety;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.io.*;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Recover block payloads in their grid-aligned assembly pose; never activates a mechanism. */
public final class NativeContraptionImport {
    public static final String REPORT = ContraptionImport.REPORT;

    private NativeContraptionImport() {}

    private record ObjectData(
            String type,
            BlockPos origin,
            SchematicDocument content,
            BlockPos controller,
            int sourceEntity,
            BlockPos sourceAnchor) {}

    public static SchematicDocument convert(SchematicDocument source) {
        var objects = new ArrayList<ObjectData>();
        var remaining = new ListTag();
        int sourceEntity = 0;
        for (var value : source.entitiesCopy()) {
            int entityIndex = sourceEntity++;
            var entry = (CompoundTag) value;
            var entity = entry.getCompound("nbt");
            String id = entity.getString("id");
            if (!entity.contains("Contraption", Tag.TAG_COMPOUND)) {
                if (SchematicSafety.movingEntity(id))
                    throw unsupported(id, "missing block payload");
                remaining.add(entry.copy());
                continue;
            }
            // Addons inherit Create's serialized block payload. Match that schema below,
            // not entity IDs: a new propeller/bearing must not need an NPShips whitelist.
            var payload = entity.getCompound("Contraption");
            if (entry.contains(com.eynny.npships.authoring.document.SchematicExtensions.KEY)
                    || entity.contains(com.eynny.npships.authoring.document.SchematicExtensions.KEY)
                    || payload.contains(
                            com.eynny.npships.authoring.document.SchematicExtensions.KEY))
                throw unsupported(id, "author data has an unsupported nested coordinate frame");
            for (String key : List.of("SubContraptions", "Passengers", "Storage", "FluidStorage")) {
                var part = payload.get(key);
                if (part == null
                        || part instanceof CollectionTag<?> list && list.isEmpty()
                        || part instanceof CompoundTag compound && compound.isEmpty()) continue;
                throw unsupported(id, "nested objects, passengers or legacy storage: " + key);
            }
            if (entity.contains("Passengers")
                    && (!(entity.get("Passengers") instanceof ListTag list) || !list.isEmpty()))
                throw unsupported(id, "entity passengers");
            String kind = payload.getString("Type");
            if (kind.contains("train") || kind.contains("carriage"))
                throw unsupported(id, "train data");
            var packed = payload.getCompound("Blocks");
            var palette = packed.getList("Palette", Tag.TAG_COMPOUND);
            var blocks = packed.getList("BlockList", Tag.TAG_COMPOUND);
            if (palette.isEmpty() || blocks.isEmpty())
                throw unsupported(id, "missing paletted blocks");
            if (blocks.size() > 1_000_000) throw unsupported(id, "too many blocks");
            BlockPos min = null, max = null;
            var cells = new HashSet<BlockPos>();
            for (var b : blocks) {
                var block = (CompoundTag) b;
                if (!block.contains("Pos", Tag.TAG_LONG) || !block.contains("State", Tag.TAG_INT))
                    throw unsupported(id, "invalid block entry");
                int index = block.getInt("State");
                if (index < 0 || index >= palette.size())
                    throw unsupported(id, "invalid palette index");
                var p = BlockPos.of(block.getLong("Pos"));
                if (!cells.add(p)) throw unsupported(id, "duplicate block coordinates");
                min = min == null ? p : min(min, p);
                max = max == null ? p : max(max, p);
            }
            var local = new CompoundTag();
            local.put("size", StructureSegments.ints(max.subtract(min).offset(1, 1, 1)));
            local.put("palette", palette.copy());
            var entries = new ListTag();
            for (var b : blocks) {
                var block = (CompoundTag) b;
                var p = BlockPos.of(block.getLong("Pos")).subtract(min);
                var out = block.copy();
                out.remove("Pos");
                out.remove("State");
                out.remove("Data");
                out.put("pos", StructureSegments.ints(p));
                out.putInt("state", block.getInt("State"));
                if (block.contains("Data", Tag.TAG_COMPOUND)) {
                    var data = block.getCompound("Data").copy();
                    data.putInt("x", p.getX());
                    data.putInt("y", p.getY());
                    data.putInt("z", p.getZ());
                    out.put("nbt", data);
                }
                entries.add(out);
            }
            MountedStorageSnapshot.attach(payload, palette, entries, min);
            local.put("blocks", entries);
            var localEntities = new ListTag();
            for (var nestedEntity : payload.getList("NPShipsEntities", Tag.TAG_COMPOUND))
                localEntities.add(
                        StructureSegments.translateEntity(
                                (CompoundTag) nestedEntity, min.multiply(-1)));
            local.put("entities", localEntities);
            var pos = entry.getList("pos", Tag.TAG_DOUBLE);
            if (pos.size() != 3) throw unsupported(id, "missing position");
            for (int axis = 0; axis < 3; axis++)
                if (!Double.isFinite(pos.getDouble(axis))
                        || Math.abs(pos.getDouble(axis)) > 30_000_000)
                    throw unsupported(id, "invalid position");
            var anchor = BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
            for (var glue : payload.getList("Superglue", Tag.TAG_COMPOUND)) {
                var glueData = ((CompoundTag) glue).copy();
                glueData.putString("id", "create:super_glue");
                glueData.put("Pos", pos.copy());
                var glueEntry = new CompoundTag();
                glueEntry.put("nbt", glueData);
                glueEntry.put("pos", pos.copy());
                glueEntry.put("blockPos", StructureSegments.ints(anchor));
                remaining.add(glueEntry);
            }
            BlockPos controller = null;
            var relative = entity.getIntArray("ControllerRelative");
            if (relative.length == 3)
                controller = anchor.offset(relative[0], relative[1], relative[2]);
            objects.add(
                    new ObjectData(
                            id,
                            anchor.offset(min),
                            new SchematicDocument(local, NativeSubLevelImport.config(local)),
                            controller,
                            entityIndex,
                            anchor));
        }
        if (objects.isEmpty()) return source;
        if (source.nbtCopy().contains(BlockAreaLayers.KEY))
            throw new IllegalArgumentException(
                    "Import physical objects from the original NBT before editing area layers.");
        var tag = source.nbtCopy();
        tag.put("entities", remaining);
        var controllers = new HashSet<BlockPos>();
        objects.forEach(
                o -> {
                    if (o.controller != null) controllers.add(o.controller);
                });
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var pos = block.getList("pos", Tag.TAG_INT);
            if (!controllers.contains(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2))))
                continue;
            var data = block.getCompound("nbt");
            data.putBoolean("Running", false);
            data.putFloat("Speed", 0);
            data.remove("TryReassembling");
            for (String key : List.of("PitchInterface", "YawInterface"))
                if (data.contains(key, Tag.TAG_COMPOUND))
                    data.getCompound(key).putFloat("Speed", 0);
        }
        BlockPos lower = BlockPos.ZERO, upper = source.size();
        for (var object : objects) {
            lower = min(lower, object.origin);
            upper = max(upper, object.origin.offset(object.content.size()));
        }
        var base = new SchematicDocument(tag, source.configCopy());
        var state = BlockAreaLayers.load(base);
        if (!lower.equals(BlockPos.ZERO) || !upper.equals(source.size()))
            state = state.reframe(lower, upper.subtract(lower));
        var occupied = new HashSet<BlockPos>();
        var basePalette = base.paletteCopy();
        final BlockPos shift = lower;
        base.blocks()
                .forEach(
                        (p, i) -> {
                            if (!basePalette
                                    .getCompound(i)
                                    .getString("Name")
                                    .equals("minecraft:air")) occupied.add(p.subtract(shift));
                        });
        var reports = new JsonArray();
        int index = 0;
        for (var object : objects) {
            var origin = object.origin.subtract(lower);
            var palette = object.content.paletteCopy();
            int overlaps = 0;
            BlockPos firstOverlap = null;
            for (var cell : object.content.blocks().entrySet()) {
                if (palette.getCompound(cell.getValue()).getString("Name").equals("minecraft:air"))
                    continue;
                var position = origin.offset(cell.getKey());
                if (!occupied.add(position)) {
                    overlaps++;
                    if (firstOverlap == null || position.compareTo(firstOverlap) < 0)
                        firstOverlap = position;
                }
            }
            String name = "Imported object " + (++index);
            state =
                    state.importObject(
                            name,
                            new SchematicRegions.Region(origin, object.content.size()),
                            object.content,
                            UUID.nameUUIDFromBytes(
                                            (tag.hashCode() + ":" + index)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))
                                    .toString());
            var report = new JsonObject();
            report.addProperty("area", name);
            report.addProperty("type", object.type);
            report.addProperty("overlaps", overlaps);
            report.addProperty("source_entity", object.sourceEntity);
            report.addProperty("pose", "grid_aligned_assembly");
            report.add("source_anchor", vector(object.sourceAnchor));
            report.add("source_origin", vector(object.origin));
            report.add("origin", vector(origin));
            report.add("size", vector(object.content.size()));
            if (object.controller != null) report.add("controller", vector(object.controller));
            if (firstOverlap != null) {
                report.add("first_overlap", vector(firstOverlap));
                report.add("source_first_overlap", vector(firstOverlap.offset(lower)));
            }
            reports.add(report);
        }
        var result = state.save();
        var config = result.configCopy();
        config.add(REPORT, reports);
        var saved = state.withBaseConfig(config).save();
        SchematicSafety.validate(saved.nbtCopy());
        return saved;
    }

    /**
     * Detached diagnostic only; older saved import reports remain readable. Coordinates are
     * explicit.
     */
    public static String diagnostic(JsonObject report) {
        var text =
                new StringBuilder(
                        report.has("area") ? report.get("area").getAsString() : "Imported object");
        if (report.has("type"))
            text.append(" [").append(report.get("type").getAsString()).append(']');
        if (report.has("source_entity"))
            text.append(" source entity #").append(report.get("source_entity").getAsInt());
        if (report.has("origin"))
            text.append("; project origin ").append(coordinates(report.getAsJsonArray("origin")));
        if (report.has("size"))
            text.append("; size ").append(coordinates(report.getAsJsonArray("size")));
        if (report.has("controller"))
            text.append("; source controller ")
                    .append(coordinates(report.getAsJsonArray("controller")));
        if (report.has("first_overlap"))
            text.append("; first project overlap ")
                    .append(coordinates(report.getAsJsonArray("first_overlap")));
        if (report.has("overlaps"))
            text.append("; overlapping cells ").append(report.get("overlaps").getAsInt());
        return text.toString();
    }

    private static String coordinates(JsonArray position) {
        return "("
                + position.get(0).getAsInt()
                + ", "
                + position.get(1).getAsInt()
                + ", "
                + position.get(2).getAsInt()
                + ")";
    }

    private static JsonArray vector(BlockPos position) {
        var result = new JsonArray();
        result.add(position.getX());
        result.add(position.getY());
        result.add(position.getZ());
        return result;
    }

    private static IllegalArgumentException unsupported(String id, String reason) {
        return new IllegalArgumentException(
                "Cannot safely disassemble imported object "
                        + id
                        + ": "
                        + reason
                        + ". Original file is unchanged; disassemble it in the source world first.");
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    public static SchematicDocument capture(
            ServerLevel level, BlockPos origin, SchematicDocument source) throws Exception {
        var controllers = new HashSet<BlockPos>();
        for (var value : source.entitiesCopy()) {
            var entry = (CompoundTag) value;
            var data = entry.getCompound("nbt");
            if (!data.contains("Contraption", Tag.TAG_COMPOUND)) continue;
            var relative = NbtUtils.readBlockPos(data, "ControllerRelative");
            if (relative.isPresent()) {
                var at = entry.getList("pos", Tag.TAG_DOUBLE);
                var controller =
                        BlockPos.containing(at.getDouble(0), at.getDouble(1), at.getDouble(2))
                                .offset(relative.get());
                if (!source.blocks().containsKey(controller))
                    throw new IllegalArgumentException(
                            "Include the moving mechanism's controller in the selection: "
                                    + data.getString("id")
                                    + " at source "
                                    + controller.toShortString()
                                    + " (world "
                                    + origin.offset(controller).toShortString()
                                    + ")");
                controllers.add(controller);
            }
        }
        // Reports describe one conversion, not the current contents of an edited project.
        var sourceConfig = source.configCopy();
        sourceConfig.remove(ContraptionImport.REPORT);
        var converted = ContraptionImport.convert(source.withConfig(sourceConfig));
        if (!converted.size().equals(source.size()))
            throw new IllegalArgumentException(
                    "Moving blocks extend outside the project: source size "
                            + source.size().toShortString()
                            + ", recovered assembly size "
                            + converted.size().toShortString()
                            + ". Enlarge the selection to include the complete grid-aligned mechanism before export.");
        var config = converted.configCopy();
        if (config.has(ContraptionImport.REPORT))
            for (var row : config.getAsJsonArray(ContraptionImport.REPORT))
                if (row.getAsJsonObject().get("overlaps").getAsInt() > 0)
                    throw new IllegalArgumentException(
                            "Moving blocks overlap existing selected blocks or an earlier imported object in the grid-aligned assembly pose. "
                                    + ContraptionImport.diagnostic(row.getAsJsonObject())
                                    + ". Move or disassemble the named mechanism in the source world, then capture again; the source world and accepted document were not changed.");
        SchematicSafety.validate(converted.nbtCopy());
        return SchematicPatches.capture(level, origin, converted, controllers);
    }
}
