package com.eynny.npships.integration.cannons;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.SchematicPatches;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Function;

/**
 * Explicit reciprocal instructions, independent of block patch slots and native Connections/Welds.
 */
public final class CannonWelds {
    public static final String KEY = "npships:cbc_welds";

    private CannonWelds() {}

    public record Pair(BlockPos from, BlockPos to) {}

    public static List<CompoundTag> links(CompoundTag data) {
        if (!data.contains(KEY)) return List.of();
        if (!(data.get(KEY) instanceof CompoundTag root)
                || !(root.get("version") instanceof IntTag)
                || root.getInt("version") != 1
                || !(root.get("links") instanceof ListTag list)
                || list.size() > 6)
            throw new IllegalArgumentException("Invalid CBC weld instruction version/links");
        var out = new ArrayList<CompoundTag>();
        var seen = new HashSet<BlockPos>();
        for (var tag : list) {
            if (!(tag instanceof CompoundTag link)
                    || !(link.get("offset") instanceof IntArrayTag offset)
                    || offset.getAsIntArray().length != 3
                    || !(link.get("block") instanceof StringTag)
                    || ResourceLocation.tryParse(link.getString("block")) == null)
                throw new IllegalArgumentException("Invalid CBC weld offset/expected block");
            if (!seen.add(offset(link)))
                throw new IllegalArgumentException("Duplicate CBC weld endpoint");
            out.add(link.copy());
        }
        return out;
    }

    public static BlockPos offset(CompoundTag link) {
        int[] v = link.getIntArray("offset");
        return new BlockPos(v[0], v[1], v[2]);
    }

    /** Geometry-only check also works in data-only Save/export without CBC. */
    public static List<Pair> pairs(SchematicDocument doc) {
        var all = new LinkedHashMap<BlockPos, List<CompoundTag>>();
        int count = 0;
        for (var p : doc.blocks().keySet()) {
            if (!doc.hasBlockEntityData(p)) continue;
            var links = links(doc.blockEntityData(p));
            if (!links.isEmpty()) validateNativeFaces(doc.blockEntityData(p));
            if (!links.isEmpty()) all.put(p, links);
            count += links.size();
            if (count > 2048) throw new IllegalArgumentException("CBC weld limit is 1024 pairs");
        }
        var pairs = new ArrayList<Pair>();
        for (var e : all.entrySet())
            for (var link : e.getValue()) {
                var p = e.getKey();
                var q = p.offset(offset(link));
                if (p.distManhattan(q) != 1
                        || !doc.blocks().containsKey(q)
                        || !doc.blockName(q).equals(link.getString("block")))
                    throw invalid(
                            p,
                            q,
                            "Endpoints must remain adjacent and retain their expected native blocks; correct or remove the instruction");
                var reverse =
                        all.getOrDefault(q, List.of()).stream()
                                .filter(v -> q.offset(offset(v)).equals(p))
                                .findFirst();
                if (reverse.isEmpty() || !reverse.get().getString("block").equals(doc.blockName(p)))
                    throw invalid(p, q, "Missing reciprocal authored instruction");
                if (p.compareTo(q) < 0) pairs.add(new Pair(p, q));
            }
        return List.copyOf(pairs);
    }

    /** CBC getList would silently treat a wrong tag type as an empty saved disconnection. */
    public static void validateNativeFaces(CompoundTag data) {
        for (String key : List.of("Connections", "Welds")) {
            if (!data.contains(key)) continue;
            if (!(data.get(key) instanceof ListTag list) || list.size() > 6)
                throw new IllegalArgumentException(
                        "Malformed required CBC " + key + " direction list");
            var seen = EnumSet.noneOf(Direction.class);
            for (var value : list) {
                var direction =
                        value instanceof StringTag ? Direction.byName(value.getAsString()) : null;
                if (direction == null || !seen.add(direction))
                    throw new IllegalArgumentException(
                            "Malformed/duplicate required CBC " + key + " direction");
            }
        }
        if (data.contains("Facing")
                && (!(data.get("Facing") instanceof StringTag)
                        || Direction.byName(data.getString("Facing")) == null))
            throw new IllegalArgumentException("Malformed required CBC Facing cache");
    }

    public static IllegalArgumentException invalid(BlockPos a, BlockPos b, String reason) {
        return new IllegalArgumentException(
                "CBC weld " + a.toShortString() + " -> " + b.toShortString() + ": " + reason);
    }

    public static SchematicDocument add(
            SchematicDocument doc, BlockPos a, BlockPos b, net.minecraft.world.level.Level level) {
        CannonWelding.validatePair(doc, a, b, level);
        var changes = new HashMap<BlockPos, CompoundTag>();
        addEnd(doc, a, b, changes);
        addEnd(doc, b, a, changes);
        var result = doc.withBlocks(changes, doc.paletteCopy(), doc.configCopy());
        pairs(result);
        return result;
    }

    /**
     * Commit only the caller's explicit edges in one detached document edit. Native validation
     * observes original Connections/Welds; accumulated instructions do not mutate those fields.
     */
    public static SchematicDocument addAll(
            SchematicDocument doc,
            Collection<Pair> accepted,
            net.minecraft.world.level.Level level) {
        if (accepted.isEmpty()) return doc;
        var palette = doc.paletteCopy();
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (var pair : accepted) {
            CannonWelding.validatePair(doc, palette, pair.from(), pair.to(), level);
            addEnd(doc, pair.from(), pair.to(), changes);
            addEnd(doc, pair.to(), pair.from(), changes);
        }
        // No intermediate author state can escape if any pair or reciprocal instruction fails.
        var result = doc.withBlocks(changes, palette, doc.configCopy());
        pairs(result);
        return result;
    }

    private static void addEnd(
            SchematicDocument doc, BlockPos a, BlockPos b, Map<BlockPos, CompoundTag> changes) {
        var entry = changes.computeIfAbsent(a, doc::blockEntryCopy);
        var data = entry.getCompound("nbt");
        var list = new ArrayList<>(links(data));
        if (list.stream().anyMatch(v -> a.offset(offset(v)).equals(b)))
            throw invalid(a, b, "Instruction already exists");
        var link = new CompoundTag();
        var d = b.subtract(a);
        link.putIntArray("offset", new int[] {d.getX(), d.getY(), d.getZ()});
        link.putString("block", doc.blockName(b));
        list.add(link);
        write(data, list);
        entry.put("nbt", data);
        changes.put(a, entry);
    }

    /** Remove just the authored edge, including a broken edge; never touch native cannon data. */
    public static SchematicDocument remove(SchematicDocument doc, BlockPos a, BlockPos b) {
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (var p : List.of(a, b)) {
            var entry = doc.blockEntryCopy(p);
            if (entry == null) continue;
            var data = entry.getCompound("nbt");
            var links = new ArrayList<>(links(data));
            var other = p.equals(a) ? b : a;
            if (!links.removeIf(v -> p.offset(offset(v)).equals(other))) continue;
            write(data, links);
            entry.put("nbt", data);
            changes.put(p, entry);
        }
        return doc.withBlocks(changes, doc.paletteCopy(), doc.configCopy());
    }

    /** A single-block brush has no copied neighbor with which to retain an authored weld. */
    public static void detachedCopy(CompoundTag data) {
        if (data.contains(KEY)) {
            links(data); // Preserve malformed-source refusal before modifying any known field.
            write(data, List.of());
        }
    }

    /** Detach only known incident edges of an explicitly replaced cell; no document/world scan. */
    public static void blockReplaced(
            SchematicDocument source, BlockPos at, Map<BlockPos, CompoundTag> changes) {
        if (!source.blockName(at).startsWith("createbigcannons:")) return;
        for (var direction : Direction.values()) {
            var neighbor = at.relative(direction);
            var entry =
                    changes.containsKey(neighbor)
                            ? changes.get(neighbor)
                            : source.blockEntryCopy(neighbor);
            if (entry == null) continue;
            entry = entry.copy();
            var data = entry.getCompound("nbt");
            var remaining = new ArrayList<>(links(data));
            if (!remaining.removeIf(
                    link ->
                            neighbor.offset(offset(link)).equals(at)
                                    && link.getString("block").equals(source.blockName(at))))
                continue;
            write(data, remaining);
            entry.put("nbt", data);
            changes.put(neighbor, entry);
        }
    }

    private static void write(CompoundTag data, List<CompoundTag> links) {
        var root = data.getCompound(KEY).copy();
        root.putInt("version", 1);
        var list = new ListTag();
        links.forEach(list::add);
        root.put("links", list);
        // Keep unknown extension fields, even after the last known instruction is removed.
        if (links.isEmpty() && root.getAllKeys().size() == 2) data.remove(KEY);
        else data.put(KEY, root);
    }

    /**
     * Remap existing native faces exactly once from original data; never synthesize native faces.
     */
    public static void rotate(
            CompoundTag original, CompoundTag output, Function<BlockPos, BlockPos> vector) {
        for (String key : List.of("Connections", "Welds")) {
            if (!original.contains(key)) continue;
            if (!(original.get(key) instanceof ListTag list))
                throw new IllegalArgumentException("Malformed CBC " + key);
            var result = new ListTag();
            for (var face : list) {
                var direction = Direction.byName(face.getAsString());
                if (direction == null)
                    throw new IllegalArgumentException("Invalid CBC direction " + face);
                var v = vector.apply(new BlockPos(direction.getNormal()));
                var mapped = Direction.fromDelta(v.getX(), v.getY(), v.getZ());
                if (mapped == null)
                    throw new IllegalArgumentException(
                            "CBC direction requires an exact cardinal rotation");
                result.add(StringTag.valueOf(mapped.getSerializedName()));
            }
            output.put(key, result);
        }
        if (original.contains("Facing", Tag.TAG_STRING)) {
            var facing = Direction.byName(original.getString("Facing"));
            if (facing != null) {
                var v = vector.apply(new BlockPos(facing.getNormal()));
                output.putString(
                        "Facing",
                        Direction.fromDelta(v.getX(), v.getY(), v.getZ()).getSerializedName());
            }
        }
        if (original.contains(KEY)) {
            var links = new ArrayList<>(links(original));
            for (var link : links) {
                var v = vector.apply(offset(link));
                link.putIntArray("offset", new int[] {v.getX(), v.getY(), v.getZ()});
            }
            output.put(KEY, original.getCompound(KEY).copy());
            write(output, links);
        }
    }

    /** Nonlinear output retains instructions, including invalid endpoints for explicit repair. */
    public static void remap(
            CompoundTag output,
            SchematicDocument source,
            Map<BlockPos, BlockPos> mapping,
            Function<BlockPos, BlockPos> rotation) {
        var oldByNew = new HashMap<BlockPos, BlockPos>();
        mapping.forEach((old, next) -> oldByNew.put(next, old));
        for (var value : output.getList("blocks", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            var next = SchematicPatches.blockPosition(entry);
            var old = oldByNew.get(next);
            if (old == null || !source.blockName(old).startsWith("createbigcannons:")) continue;
            var original = source.blockEntityData(old);
            var data = entry.getCompound("nbt");
            if (rotation != null) rotate(original, data, rotation);
            else if (original.contains(KEY)) {
                var links = new ArrayList<>(links(original));
                for (var link : links) {
                    var endpoint = old.offset(offset(link));
                    var d = mapping.getOrDefault(endpoint, endpoint).subtract(next);
                    link.putIntArray("offset", new int[] {d.getX(), d.getY(), d.getZ()});
                }
                data.put(KEY, original.getCompound(KEY).copy());
                write(data, links);
            }
            if (!data.isEmpty()) entry.put("nbt", data);
        }
    }
}
