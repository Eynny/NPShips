package com.eynny.npships.integration.contraptions;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.io.*;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.util.*;

/** Converts Sable schematic payloads to editable assembly poses, without touching a live world. */
public final class NativeSubLevelImport {
    public static final String GROUP = SubLevelImport.GROUP;

    private NativeSubLevelImport() {}

    private static final class Part {
        final CompoundTag tag;
        final String id;
        Part parent;
        BlockPos joint, plate, origin;
        boolean visiting;

        Part(CompoundTag tag, String id) {
            this.tag = tag;
            this.id = id;
        }
    }

    public static SchematicDocument read(CompoundTag input, JsonObject config) {
        if (!input.contains("sub_levels")) return new SchematicDocument(input, config);
        if (!(input.get("sub_levels") instanceof ListTag list))
            throw new IllegalArgumentException("Invalid sub_levels list");
        if (list.isEmpty()) return new SchematicDocument(input, config);
        if (list.size() > 256) throw new IllegalArgumentException("Too many physical sublevels");
        var root = new Part(input.copy(), "");
        root.tag.remove("sub_levels");
        root.origin = BlockPos.ZERO;
        var parts = new LinkedHashMap<String, Part>();
        for (var value : list) {
            if (!(value instanceof CompoundTag tag) || !tag.hasUUID("uuid"))
                throw new IllegalArgumentException("Sublevel has no portable UUID");
            if (tag.contains(com.eynny.npships.authoring.document.SchematicExtensions.KEY))
                throw new IllegalArgumentException(
                        "Author data in a native sublevel has an unsupported nested coordinate frame; "
                                + "import its supported flat schematic instead. Original source is unchanged.");
            String id = tag.getUUID("uuid").toString();
            if (parts.putIfAbsent(id, new Part(tag.copy(), id)) != null)
                throw new IllegalArgumentException("Duplicate sublevel UUID " + id);
        }
        var all = new ArrayList<Part>();
        all.add(root);
        all.addAll(parts.values());
        for (var parent : all) {
            var palette = parent.tag.getList("palette", Tag.TAG_COMPOUND);
            for (var value : parent.tag.getList("blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) value;
                var state = palette.getCompound(block.getInt("state"));
                var data = block.getCompound("nbt");
                if (!state.getString("Name").equals("simulated:swivel_bearing")
                        || !data.hasUUID("SubLevelID")) continue;
                var child = parts.get(data.getUUID("SubLevelID").toString());
                if (child == null)
                    throw new IllegalArgumentException(
                            "Swivel bearing references a missing sublevel");
                if (child.parent != null)
                    throw new IllegalArgumentException(
                            "Multiple swivel bearings reference the same sublevel");
                child.parent = parent;
                child.joint = SchematicPatches.blockPosition(block);
                child.plate =
                        NbtUtils.readBlockPos(data, "SwivelPlate")
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Missing swivel plate position"));
                data.remove("SubLevelID");
                data.remove("SwivelPlate");
                var idle = state.copy();
                idle.getCompound("Properties").putString("assembled", "false");
                block.putInt("state", palette.size());
                palette.add(idle);
                var patch =
                        block.contains(SchematicPatches.EMBEDDED)
                                ? SchematicPatches.embeddedBuiltin(block)
                                : null;
                if (patch == null) patch = new JsonObject();
                patch.addProperty("kind", "mechanism");
                patch.addProperty("block", "simulated:swivel_bearing");
                patch.add("pos", SchematicPatches.vector(child.joint));
                patch.addProperty("assemble", true);
                var patches = new java.util.ArrayList<>(SchematicPatches.embeddedEntries(block));
                patches.removeIf(
                        p -> !com.eynny.npships.authoring.patches.ExternalPatches.external(p));
                patch =
                        com.eynny.npships.authoring.patches.PatchInstances.materialize(
                                patch, false);
                patches.add(patch);
                SchematicPatches.writeEmbedded(block, patches);
            }
        }
        var entities = root.tag.getList("entities", Tag.TAG_COMPOUND);
        root.tag.put("entities", entities);
        for (var part : parts.values()) {
            var origin = origin(part);
            var docTag = part.tag.copy();
            docTag.remove("uuid");
            docTag.remove("position");
            docTag.remove("orientation");
            var originalBlocks = docTag.getList("blocks", Tag.TAG_COMPOUND);
            if (originalBlocks.isEmpty())
                throw new IllegalArgumentException("Empty physical sublevel");
            var positions = new HashSet<BlockPos>();
            for (var value : originalBlocks)
                positions.add(SchematicPatches.blockPosition((CompoundTag) value));
            for (var value : originalBlocks) {
                var block = (CompoundTag) value;
                for (var code :
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                    code.importedBlock(
                            docTag.getList("palette", Tag.TAG_COMPOUND)
                                    .getCompound(block.getInt("state")),
                            block,
                            positions);
            }
            String anchorKey = "npships:import_anchor";
            BlockPos anchorBefore = SchematicPatches.blockPosition(originalBlocks.getCompound(0));
            originalBlocks.getCompound(0).putBoolean(anchorKey, true);
            var decoded = ContraptionImport.convert(new SchematicDocument(docTag, config(docTag)));
            docTag = decoded.nbtCopy();
            BlockPos shift = BlockPos.ZERO;
            for (var value : docTag.getList("blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) value;
                if (block.getBoolean(anchorKey))
                    shift = SchematicPatches.blockPosition(block).subtract(anchorBefore);
                block.remove(anchorKey);
            }
            origin = origin.subtract(shift);
            BlockPos platePosition = part.plate == null ? null : part.plate.offset(shift);
            var blocks = new ListTag();
            boolean plateFound = part.parent == null;
            var palette = docTag.getList("palette", Tag.TAG_COMPOUND);
            for (var value : docTag.getList("blocks", Tag.TAG_COMPOUND)) {
                var b = (CompoundTag) value;
                var pos = SchematicPatches.blockPosition(b);
                var name = palette.getCompound(b.getInt("state")).getString("Name");
                if (part.parent != null && pos.equals(platePosition)) {
                    if (!name.equals("simulated:swivel_bearing_link_block"))
                        throw new IllegalArgumentException(
                                "Swivel plate payload does not match its bearing");
                    plateFound = true;
                    continue;
                }
                if (name.equals("simulated:swivel_bearing_link_block"))
                    throw new IllegalArgumentException("Unowned swivel plate in sublevel");
                var packed = b.copy();
                packed.remove("pos");
                packed.remove("state");
                packed.remove("nbt");
                packed.putLong("Pos", pos.asLong());
                packed.putInt("State", b.getInt("state"));
                if (b.contains("nbt")) packed.put("Data", b.getCompound("nbt").copy());
                if (part.parent == null)
                    packed.putString(
                            GROUP,
                            UUID.nameUUIDFromBytes(
                                            (part.id + ":import")
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))
                                    .toString());
                blocks.add(packed);
            }
            if (!plateFound) throw new IllegalArgumentException("Missing swivel plate payload");
            if (blocks.isEmpty() && part.parent != null) continue;
            if (blocks.isEmpty())
                throw new IllegalArgumentException("Empty free physical sublevel");
            var packed = new CompoundTag();
            packed.put("Palette", palette);
            packed.put("BlockList", blocks);
            var payload = new CompoundTag();
            payload.putString("Type", "npships:sublevel");
            payload.put("Blocks", packed);
            payload.put("NPShipsEntities", docTag.getList("entities", Tag.TAG_COMPOUND).copy());
            var entity = new CompoundTag();
            entity.putString("id", "npships:imported_sublevel");
            entity.put("Contraption", payload);
            var entry = new CompoundTag();
            entry.put("nbt", entity);
            var pos = new ListTag();
            pos.add(DoubleTag.valueOf(origin.getX()));
            pos.add(DoubleTag.valueOf(origin.getY()));
            pos.add(DoubleTag.valueOf(origin.getZ()));
            entry.put("pos", pos);
            entry.put("blockPos", StructureSegments.ints(origin));
            entities.add(entry);
        }
        var combined = config == null ? new JsonObject() : config.deepCopy();
        var patches = new LinkedHashMap<String, JsonObject>();
        for (var p : SchematicPatches.readIdentified(combined))
            patches.put(com.eynny.npships.authoring.patches.PatchInstances.key(p, false), p);
        var identifiedRoot =
                SchematicPatches.authoringConfig(new SchematicDocument(root.tag, null));
        for (var p : SchematicPatches.read(identifiedRoot))
            patches.put(com.eynny.npships.authoring.patches.PatchInstances.key(p, false), p);
        setPatches(combined, patches.values());
        return ContraptionImport.convert(new SchematicDocument(root.tag, combined));
    }

    static JsonObject config(CompoundTag tag) {
        return SchematicPatches.authoringConfig(new SchematicDocument(tag, null));
    }

    private static void setPatches(JsonObject config, Collection<JsonObject> values) {
        SchematicPatches.set(config, List.copyOf(values));
    }

    private static BlockPos origin(Part part) {
        if (part.origin != null) return part.origin;
        if (part.visiting) throw new IllegalArgumentException("Cyclic swivel bearing graph");
        part.visiting = true;
        if (part.parent != null)
            part.origin = origin(part.parent).offset(part.joint).subtract(part.plate);
        else {
            var q = part.tag.getCompound("orientation");
            double length = 0;
            for (String axis : List.of("x", "y", "z", "w")) {
                double v = q.getDouble(axis);
                if (!Double.isFinite(v))
                    throw new IllegalArgumentException("Invalid sublevel orientation");
                length += v * v;
            }
            if (Math.abs(length - 1) > 1e-4)
                throw new IllegalArgumentException("Invalid sublevel orientation");
            // Like Create imports, use the editable grid-aligned assembly pose. The shared
            // import report explicitly tells the author that animated angles are not retained.
            var p = part.tag.getCompound("position");
            double x = p.getDouble("x"), y = p.getDouble("y"), z = p.getDouble("z");
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > 30000000)
                throw new IllegalArgumentException("Invalid sublevel position");
            part.origin =
                    new BlockPos((int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
        }
        part.visiting = false;
        return part.origin;
    }
}
