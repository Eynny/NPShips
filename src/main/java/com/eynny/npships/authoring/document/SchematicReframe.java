package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Pure resize proposals. Nothing is committed until the editor accepts the entire proposal. */
public final class SchematicReframe {
    public record Review(
            String id, String label, SchematicDocument before, BlockPos origin, BlockPos size) {}

    public record Proposal(SchematicDocument result, List<Review> reviews, List<String> changes) {
        public boolean destructive() {
            return changes.stream()
                    .anyMatch(
                            v ->
                                    v.startsWith("Removed ")
                                            || v.startsWith("Remove mob zone: ")
                                            || v.startsWith("Remove segment: "));
        }
    }

    private static int blockCount(SchematicDocument doc) {
        var palette = doc.paletteCopy();
        return (int)
                doc.blocks().values().stream()
                        .filter(
                                i ->
                                        !Set.of(
                                                        "minecraft:air",
                                                        "minecraft:cave_air",
                                                        "minecraft:void_air")
                                                .contains(palette.getCompound(i).getString("Name")))
                        .count();
    }

    private static void removalSummary(
            String label, SchematicDocument before, SchematicDocument after, List<String> changes) {
        int removedPoints =
                com.eynny.npships.authoring.AssemblyPoints.read(before.configCopy()).size()
                        - com.eynny.npships.authoring.AssemblyPoints.read(after.configCopy())
                                .size();
        if (removedPoints > 0)
            changes.add("Removed " + removedPoints + " assembly points: " + label);
        int blocks = blockCount(before) - blockCount(after),
                entities = before.entityCount() - after.entityCount();
        if (blocks > 0 || entities > 0)
            changes.add("Removed " + blocks + " blocks, " + entities + " entities: " + label);
    }

    private SchematicReframe() {}

    public static SchematicDocument plain(
            SchematicDocument source, BlockPos origin, BlockPos size) {
        var tag = source.nbtCopy();
        var config = source.configCopy();
        if (!com.eynny.npships.authoring.patches.ExternalPatches.authoringEntries(source).isEmpty())
            config = SchematicPatches.authoringConfig(source);
        SchematicPatches.embed(tag, config);
        var blocks = new ListTag();
        for (var v : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var b = ((CompoundTag) v).copy();
            var p = SchematicPatches.blockPosition(b).subtract(origin);
            if (!SchematicEdits.inside(size, Vec3.atLowerCornerOf(p))) continue;
            b.put("pos", StructureSegments.ints(p));
            if (b.contains("nbt")) {
                var data = b.getCompound("nbt");
                data.putInt("x", p.getX());
                data.putInt("y", p.getY());
                data.putInt("z", p.getZ());
            }
            blocks.add(b);
        }
        var entities = new ListTag();
        for (var v : tag.getList("entities", Tag.TAG_COMPOUND)) {
            var e = ((CompoundTag) v).copy();
            var p = e.getList("pos", Tag.TAG_DOUBLE);
            var next =
                    new Vec3(
                            p.getDouble(0) - origin.getX(),
                            p.getDouble(1) - origin.getY(),
                            p.getDouble(2) - origin.getZ());
            if (!SchematicEdits.inside(size, next)) continue;
            var coords = new ListTag();
            coords.add(DoubleTag.valueOf(next.x));
            coords.add(DoubleTag.valueOf(next.y));
            coords.add(DoubleTag.valueOf(next.z));
            e.put("pos", coords);
            e.put(
                    "blockPos",
                    StructureSegments.ints(
                            PreviewEntityData.translatedAnchor(e, next, origin.multiply(-1))));
            entities.add(e);
        }
        tag.put("blocks", blocks);
        tag.put("entities", entities);
        tag.put("size", StructureSegments.ints(size));
        com.eynny.npships.authoring.AssemblyPoints.map(
                config,
                p -> p.subtract(origin),
                p ->
                        p.getX() >= origin.getX()
                                && p.getY() >= origin.getY()
                                && p.getZ() >= origin.getZ()
                                && p.getX() < (long) origin.getX() + size.getX()
                                && p.getY() < (long) origin.getY() + size.getY()
                                && p.getZ() < (long) origin.getZ() + size.getZ());
        com.eynny.npships.authoring.AssemblyPoints.embed(tag, config);
        if (config != null) {
            config.remove(LootAssignments.KEY);
            var result = new SchematicDocument(tag, null);
            for (var entry : LootAssignments.read(source.configCopy())) {
                var p = entry.pos().subtract(origin);
                if (result.blocks().containsKey(p))
                    config = LootAssignments.assign(config, p, entry.table(), entry.rolls());
            }
            SchematicPatches.set(config, SchematicPatches.fromTemplate(tag));
            if (config.has("crew")) {
                var zones = new JsonArray();
                for (var v : config.getAsJsonArray("crew")) {
                    var zone = v.getAsJsonObject();
                    var old =
                            SchematicPatches.position(zone.getAsJsonArray("origin"))
                                    .subtract(origin);
                    var zsize = SchematicPatches.position(zone.getAsJsonArray("size"));
                    var lo = max(old, BlockPos.ZERO);
                    var hi = min(old.offset(zsize), size);
                    if (positive(hi.subtract(lo))) {
                        zone.add("origin", SchematicPatches.vector(lo));
                        zone.add("size", SchematicPatches.vector(hi.subtract(lo)));
                        zones.add(zone);
                    }
                }
                config.add("crew", zones);
            }
        }
        var result = EntityPatches.document(tag, config);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.apply(
                        source,
                        result,
                        SchematicExtensions.clip(
                                PatchAuthoring.Kind.CROP, origin, size, origin.multiply(-1)))
                : result;
    }

    public static Proposal segment(
            SchematicDocument source, String id, BlockPos origin, BlockPos size) {
        var config = source.configCopy();
        var tag = source.nbtCopy();
        var reviews = new ArrayList<Review>();
        var changes = new ArrayList<String>();
        var old =
                StructureSegments.read(config).stream()
                        .filter(s -> s.id().equals(id))
                        .findFirst()
                        .orElseThrow();
        for (var v : config.getAsJsonArray("segments"))
            if (v.getAsJsonObject().get("id").getAsString().equals(id))
                resizePart(
                        tag,
                        v.getAsJsonObject(),
                        old,
                        origin,
                        size,
                        origin.subtract(old.origin()),
                        reviews,
                        changes,
                        EmbeddedLoot.read(config));
        return finish(tag, config, reviews, changes);
    }

    public static Proposal whole(SchematicDocument source, BlockPos origin, BlockPos size) {
        var base = plain(source, origin, size);
        var tag = base.nbtCopy();
        var config = base.configCopy();
        var reviews = new ArrayList<Review>();
        var changes = new ArrayList<String>();
        removalSummary("schematic", source, base, changes);
        changes.add(
                "Entities: "
                        + source.entityCount()
                        + " -> "
                        + tag.getList("entities", Tag.TAG_COMPOUND).size());
        var nextZones = StructureCrew.read(config);
        for (var old : StructureCrew.read(source.configCopy())) {
            var next = nextZones.stream().filter(z -> z.id().equals(old.id())).findFirst();
            if (next.isEmpty()) changes.add("Remove mob zone: " + old.id());
            else if (!next.get().size().equals(old.size()))
                changes.add(
                        "Mob zone: " + old.id() + " " + old.size() + " -> " + next.get().size());
        }
        var segments = new JsonArray();
        if (config != null && config.has("segments"))
            for (var v : config.getAsJsonArray("segments")) {
                var obj = v.getAsJsonObject();
                String id = obj.get("id").getAsString();
                var old =
                        StructureSegments.read(source.configCopy()).stream()
                                .filter(s -> s.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                var shifted = old.origin().subtract(origin);
                var lo = max(shifted, BlockPos.ZERO);
                var hi = min(shifted.offset(old.size()), size);
                if (!positive(hi.subtract(lo))) {
                    changes.add("Remove segment: " + id + " (" + old.pool().size() + " variants)");
                    for (var variant : old.pool()) {
                        var part =
                                com.eynny.npships.authoring.AssemblyPoints.segmentDocument(
                                        tag.getCompound(StructureSegments.VARIANTS)
                                                .getCompound(variant.id()),
                                        null);
                        int blocks = blockCount(part), entities = part.entityCount();
                        if (blocks > 0 || entities > 0)
                            changes.add(
                                    "Removed "
                                            + blocks
                                            + " blocks, "
                                            + entities
                                            + " entities: "
                                            + id
                                            + " / "
                                            + variant.id());
                    }
                    continue;
                }
                if (!hi.subtract(lo).equals(old.size()))
                    resizePart(
                            tag,
                            obj,
                            old,
                            lo,
                            hi.subtract(lo),
                            lo.subtract(shifted),
                            reviews,
                            changes,
                            EmbeddedLoot.read(config));
                else obj.add("origin", SchematicPatches.vector(lo));
                segments.add(obj);
            }
        if (config != null) config.add("segments", segments);
        return finish(tag, config, reviews, changes);
    }

    private static void resizePart(
            CompoundTag tag,
            JsonObject obj,
            StructureSegments.Segment old,
            BlockPos origin,
            BlockPos size,
            BlockPos delta,
            List<Review> reviews,
            List<String> changes,
            JsonObject tables) {
        obj.add("origin", SchematicPatches.vector(origin));
        obj.add("size", SchematicPatches.vector(size));
        changes.add(
                old.id()
                        + ": "
                        + old.size()
                        + " -> "
                        + size
                        + " ("
                        + old.pool().size()
                        + " variants)");
        if (!obj.has("pool")) return;
        var all = tag.getCompound(StructureSegments.VARIANTS);
        for (var value : obj.getAsJsonArray("pool")) {
            var entry = value.getAsJsonObject();
            String previous = entry.get("variant").getAsString();
            var partConfig = new JsonObject();
            partConfig.addProperty("cost", 1);
            for (var loot : LootAssignments.read(entry))
                partConfig =
                        LootAssignments.assign(partConfig, loot.pos(), loot.table(), loot.rolls());
            if (!tables.isEmpty()) partConfig.add("loot_tables", tables.deepCopy());
            // plain() reads the embedded variant schema before changing coordinates.
            var before =
                    com.eynny.npships.authoring.AssemblyPoints.segmentDocument(
                            all.getCompound(previous), partConfig);
            var after = plain(before, delta, size);
            removalSummary(old.id() + " / " + previous, before, after, changes);
            String id = UUID.randomUUID().toString();
            all.put(id, after.nbtCopy());
            entry.addProperty("variant", id);
            entry.remove(LootAssignments.KEY);
            for (var loot : LootAssignments.read(after.configCopy())) {
                var updated = LootAssignments.assign(entry, loot.pos(), loot.table(), loot.rolls());
                for (var e : updated.entrySet()) entry.add(e.getKey(), e.getValue());
            }
            reviews.add(
                    new Review(
                            id,
                            old.id()
                                    + " / "
                                    + (entry.has("name")
                                            ? entry.get("name").getAsString()
                                            : previous),
                            before,
                            delta,
                            size));
        }
        tag.put(StructureSegments.VARIANTS, all);
    }

    private static Proposal finish(
            CompoundTag tag, JsonObject config, List<Review> reviews, List<String> changes) {
        collectUnused(tag, config);
        StructureSegments.validate(tag, StructureSegments.read(config));
        var result = EntityPatches.document(tag, config);
        StructureCrew.validateBounds(result, config);
        return new Proposal(result, List.copyOf(reviews), List.copyOf(changes));
    }

    public static void collectUnused(CompoundTag tag, JsonObject config) {
        var used = new HashSet<String>();
        for (var s : StructureSegments.read(config)) for (var v : s.pool()) used.add(v.id());
        var all = tag.getCompound(StructureSegments.VARIANTS);
        for (String id : Set.copyOf(all.getAllKeys())) if (!used.contains(id)) all.remove(id);
    }

    public static SchematicDocument deleteSegment(SchematicDocument source, String id) {
        var c = source.configCopy();
        var list = c.getAsJsonArray("segments");
        list.asList().removeIf(v -> v.getAsJsonObject().get("id").getAsString().equals(id));
        var tag = source.nbtCopy();
        collectUnused(tag, c);
        return new SchematicDocument(tag, c);
    }

    public static SchematicDocument deleteVariant(
            SchematicDocument source, String segment, String id) {
        var c = source.configCopy();
        for (var v : c.getAsJsonArray("segments")) {
            var s = v.getAsJsonObject();
            if (!s.get("id").getAsString().equals(segment) || !s.has("pool")) continue;
            var pool = s.getAsJsonArray("pool");
            pool.asList()
                    .removeIf(e -> e.getAsJsonObject().get("variant").getAsString().equals(id));
            if (pool.isEmpty()) s.remove("pool");
        }
        var tag = source.nbtCopy();
        collectUnused(tag, c);
        return new SchematicDocument(tag, c);
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

    private static boolean positive(BlockPos p) {
        return p.getX() > 0 && p.getY() > 0 && p.getZ() > 0;
    }
}
