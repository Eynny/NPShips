package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.geometry.HollowShell;
import com.eynny.npships.authoring.io.AuthoringCreation;
import com.eynny.npships.authoring.io.ExportProfile;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

import java.util.*;
import java.util.function.*;

/**
 * Atomic bulk edits on detached data. Coordinates and graph edges are transformed simultaneously.
 */
public final class SchematicRegions {
    private SchematicRegions() {}

    public record Region(BlockPos origin, BlockPos size) {
        public Region {
            if (size.getX() < 1
                    || size.getY() < 1
                    || size.getZ() < 1
                    || size.getX() > SchematicDocument.MAX_SIDE
                    || size.getY() > SchematicDocument.MAX_SIDE
                    || size.getZ() > SchematicDocument.MAX_SIDE)
                throw new IllegalArgumentException("Region dimensions must be within 1..512.");
        }

        public boolean contains(BlockPos p) {
            return p.getX() >= origin.getX()
                    && p.getY() >= origin.getY()
                    && p.getZ() >= origin.getZ()
                    && p.getX() < (long) origin.getX() + size.getX()
                    && p.getY() < (long) origin.getY() + size.getY()
                    && p.getZ() < (long) origin.getZ() + size.getZ();
        }

        public AABB bounds() {
            return new AABB(
                    Vec3.atLowerCornerOf(origin), Vec3.atLowerCornerOf(origin.offset(size)));
        }

        public boolean contains(Region other) {
            return contains(other.origin())
                    && contains(other.origin().offset(other.size()).offset(-1, -1, -1));
        }

        public void validate(SchematicDocument doc) {
            if (!doc.contains(origin) || !doc.contains(origin.offset(size).offset(-1, -1, -1)))
                throw new IllegalArgumentException("Region is outside the schematic.");
            if (doc.hasMultiplePalettes())
                throw new IllegalArgumentException("Region editing requires a single palette.");
        }
    }

    public record Contents(boolean blocks, boolean entities, boolean zones) {}

    public enum Shape {
        NONE,
        BOX,
        ELLIPSOID,
        DIAMOND,
        CYLINDER,
        CYLINDER_X,
        CYLINDER_Z,
        CYLINDER_TILTED,
        PYRAMID,
        TETRAHEDRON,
        TETRAHEDRON_SIDE,
        OCTAGONAL_PRISM,
        HEXAGONAL_PRISM,
        HEMISPHERE,
        HEMISPHERE_DOWN,
        PLANE_XZ,
        PLANE_XY,
        PLANE_YZ;

        public boolean includes(int x, int y, int z, BlockPos size) {
            int sx = size.getX(), sy = size.getY(), sz = size.getZ();
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false;
            double nx = (2.0 * x + 1 - sx) / sx,
                    ny = (2.0 * y + 1 - sy) / sy,
                    nz = (2.0 * z + 1 - sz) / sz;
            double cx = center(x, sx), cy = center(y, sy), cz = center(z, sz);
            double up = (double) y / Math.max(1, sy - 1),
                    forward = (double) z / Math.max(1, sz - 1);
            return switch (this) {
                case NONE -> false;
                case BOX -> true;
                case ELLIPSOID -> nx * nx + ny * ny + nz * nz <= 1.000000001;
                case DIAMOND ->
                        Math.max(0, Math.abs(2.0 * x + 1 - sx) - 1) / Math.max(1, sx - 1)
                                        + Math.max(0, Math.abs(2.0 * y + 1 - sy) - 1)
                                                / Math.max(1, sy - 1)
                                        + Math.max(0, Math.abs(2.0 * z + 1 - sz) - 1)
                                                / Math.max(1, sz - 1)
                                <= 1.000000001;
                case CYLINDER -> nx * nx + nz * nz <= 1.000000001;
                case CYLINDER_X -> ny * ny + nz * nz <= 1.000000001;
                case CYLINDER_Z -> nx * nx + ny * ny <= 1.000000001;
                case CYLINDER_TILTED -> (cx - cy) * (cx - cy) + cz * cz <= 1.000000001;
                case PYRAMID -> Math.max(Math.abs(cx), Math.abs(cz)) <= 1 - up + 1e-9;
                case TETRAHEDRON -> triangle(cx, cz, 1 - up);
                case TETRAHEDRON_SIDE -> triangle(cx, cy, 1 - forward);
                case OCTAGONAL_PRISM -> Math.abs(cx) + Math.abs(cz) <= Math.sqrt(2) + 1e-9;
                case HEXAGONAL_PRISM -> Math.abs(cx) + Math.abs(cz) * .5 <= 1 + 1e-9;
                case HEMISPHERE -> cx * cx + cz * cz + up * up <= 1.000000001;
                case HEMISPHERE_DOWN -> cx * cx + cz * cz + (1 - up) * (1 - up) <= 1.000000001;
                case PLANE_XZ -> y == 0;
                case PLANE_XY -> z == 0;
                case PLANE_YZ -> x == 0;
            };
        }

        private static double center(int p, int size) {
            double n = 2.0 * p + 1 - size;
            return Math.copySign(Math.max(0, Math.abs(n) - 1) / Math.max(1, size - 1), n);
        }

        private static boolean triangle(double x, double z, double width) {
            return z >= -width - 1e-9
                    && z <= width + 1e-9
                    && Math.abs(x) <= (width - z) * .5 + 1e-9;
        }

        public boolean shell(int x, int y, int z, BlockPos size) {
            if (!includes(x, y, z, size)) return false;
            // Erode by the full 3x3x3 neighborhood. Six-neighbor erosion leaves
            // diagonal-only contact on curved shells, splitting physical components.
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (!includes(x + dx, y + dy, z + dz, size)) return true;
            return false;
        }
    }

    public static SchematicDocument delete(
            SchematicDocument source, Region region, Contents contents) {
        region.validate(source);
        return merge(source, null, null, region.origin(), region, contents, false);
    }

    public static SchematicDocument paste(
            SchematicDocument target,
            SchematicDocument snapshot,
            Region region,
            BlockPos to,
            Contents contents) {
        region.validate(snapshot);
        new Region(to, region.size()).validate(target);
        return merge(target, snapshot, region, to, new Region(to, region.size()), contents, false);
    }

    public static SchematicDocument move(
            SchematicDocument source, Region region, BlockPos to, Contents contents) {
        region.validate(source);
        new Region(to, region.size()).validate(source);
        if (to.equals(region.origin())) return source;
        return merge(source, source, region, to, new Region(to, region.size()), contents, true);
    }

    public static SchematicDocument moveSelection(
            SchematicDocument source, Region original, Region destination, Contents contents) {
        original.validate(source);
        destination.validate(source);
        var s = original.size();
        var t = destination.size();
        var clipped =
                new Region(
                        original.origin(),
                        new BlockPos(
                                Math.min(s.getX(), t.getX()),
                                Math.min(s.getY(), t.getY()),
                                Math.min(s.getZ(), t.getZ())));
        return merge(
                source,
                source,
                clipped,
                destination.origin(),
                destination,
                contents,
                true,
                original);
    }

    public static SchematicDocument turn(
            SchematicDocument source,
            Region region,
            Contents contents,
            boolean right,
            net.minecraft.world.level.Level level) {
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(source, "selected-region cardinal turn");
        region.validate(source);
        var size = region.size();
        var turnedSize = new BlockPos(size.getZ(), size.getY(), size.getX());
        // Keep the minimum corner fixed as dimensions swap, including odd/even extents.
        // This makes left/right exact inverses without half-block rounding drift.
        var to = region.origin();
        var destination = new Region(to, turnedSize);
        destination.validate(source);
        var empty = AuthoringCreation.empty(size, ExportProfile.EMPTY);
        var extracted = paste(empty, source, region, BlockPos.ZERO, contents);
        var rotated = SchematicTurn.turn(extracted, right, level);
        var result =
                paste(
                        delete(source, region, contents),
                        rotated,
                        new Region(BlockPos.ZERO, turnedSize),
                        to,
                        contents);
        if (contents.blocks()) {
            // Rebuild from original graph so external links and computer identities survive.
            var mapping = new HashMap<BlockPos, BlockPos>();
            for (var p : source.blocks().keySet())
                if (region.contains(p) && !source.blockName(p).equals("minecraft:air")) {
                    var q = p.subtract(region.origin());
                    mapping.put(
                            p,
                            to.offset(
                                    right
                                            ? new BlockPos(
                                                    size.getZ() - 1 - q.getZ(), q.getY(), q.getX())
                                            : new BlockPos(
                                                    q.getZ(),
                                                    q.getY(),
                                                    size.getX() - 1 - q.getX())));
                }
            var occupied = new HashSet<>(mapping.values());
            var patches = new ArrayList<JsonObject>();
            for (var patch : SchematicPatches.read(config(source))) {
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                if (occupied.contains(owner) && !mapping.containsKey(owner)) continue;
                for (var endpoint : PatchDevices.targets(patch))
                    if (occupied.contains(endpoint) && !mapping.containsKey(endpoint))
                        PatchDevices.removeTarget(patch, owner, endpoint);
                remap(patch, mapping);
                patches.add(patch);
            }
            var config = result.configCopy();
            setPatches(config, patches);
            result = finish(source, result.nbtCopy(), config);
        }
        return result;
    }

    private static SchematicDocument merge(
            SchematicDocument target,
            SchematicDocument snapshot,
            Region from,
            BlockPos to,
            Region replaced,
            Contents contents,
            boolean move) {
        return merge(target, snapshot, from, to, replaced, contents, move, from);
    }

    private static SchematicDocument merge(
            SchematicDocument target,
            SchematicDocument snapshot,
            Region from,
            BlockPos to,
            Region replaced,
            Contents contents,
            boolean move,
            Region clearedSource) {
        var patchRemap = new PatchInstances.Remap();
        var tag = target.nbtCopy();
        var config = config(target);
        var palette = target.paletteCopy();
        tag.put("palette", palette);
        tag.remove("palettes");
        BlockPos delta = from == null ? BlockPos.ZERO : to.subtract(from.origin());
        var mapping = new HashMap<BlockPos, BlockPos>();
        if (snapshot != null && contents.blocks())
            for (var p : snapshot.blocks().keySet())
                if (from.contains(p) && !snapshot.blockName(p).equals("minecraft:air"))
                    mapping.put(p, p.offset(delta));
        var destinations = new HashSet<>(mapping.values());
        Predicate<BlockPos> erased =
                p ->
                        (snapshot == null ? replaced.contains(p) : destinations.contains(p))
                                || (move && clearedSource.contains(p));
        if (contents.blocks()) {
            var blocks = new LinkedHashMap<BlockPos, CompoundTag>();
            for (var v : tag.getList("blocks", Tag.TAG_COMPOUND)) {
                var b = (CompoundTag) v;
                var p = SchematicPatches.blockPosition(b);
                if (!erased.test(p)) blocks.put(p, b);
            }
            if (snapshot != null) {
                var encoded = snapshot.paletteCopy();
                var indices = new HashMap<CompoundTag, Integer>();
                for (int i = 0; i < palette.size(); i++) indices.put(palette.getCompound(i), i);
                for (var v : snapshot.nbtCopy().getList("blocks", Tag.TAG_COMPOUND)) {
                    var b = ((CompoundTag) v).copy();
                    var p = SchematicPatches.blockPosition(b);
                    if (!from.contains(p)) continue;
                    var state = encoded.getCompound(b.getInt("state"));
                    if (state.getString("Name").equals("minecraft:air")) continue;
                    var index = indices.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.add(state.copy());
                        indices.put(state, index);
                    }
                    b.putInt("state", index);
                    p = p.offset(delta);
                    b.put("pos", StructureSegments.ints(p));
                    if (b.contains("nbt")) {
                        var data = b.getCompound("nbt");
                        data.putInt("x", p.getX());
                        data.putInt("y", p.getY());
                        data.putInt("z", p.getZ());
                    }
                    blocks.put(p, b);
                }
            }
            var list = new ListTag();
            blocks.values().forEach(list::add);
            tag.put("blocks", list);
            var loot = new ArrayList<LootAssignments.Entry>();
            for (var e : LootAssignments.read(config)) if (!erased.test(e.pos())) loot.add(e);
            if (snapshot != null)
                for (var e : LootAssignments.read(snapshot.configCopy()))
                    if (from.contains(e.pos()))
                        loot.add(
                                new LootAssignments.Entry(
                                        e.pos().offset(delta), e.table(), e.rolls()));
            config.remove(LootAssignments.KEY);
            for (var e : loot)
                config = LootAssignments.assign(config, e.pos(), e.table(), e.rolls());
            // Clipboard stays on this schematic, so local loot table IDs retain their meaning.
            if (snapshot != null) {
                var tables = EmbeddedLoot.read(snapshot.configCopy());
                for (var e : tables.entrySet())
                    if (!EmbeddedLoot.read(config).has(e.getKey()))
                        config =
                                EmbeddedLoot.assign(
                                        config, e.getKey(), e.getValue().getAsJsonObject());
            }
            var patches = new ArrayList<JsonObject>();
            for (var patch : SchematicPatches.read(config)) {
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                if (erased.test(owner) && !(move && mapping.containsKey(owner))) continue;
                for (var endpoint : PatchDevices.targets(patch))
                    if (erased.test(endpoint) && !(move && mapping.containsKey(endpoint)))
                        PatchDevices.removeTarget(patch, owner, endpoint);
                if (move) remap(patch, mapping);
                patches.add(patch);
            }
            if (snapshot != null && !move)
                for (var patch : SchematicPatches.read(config(snapshot))) {
                    var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                    if (!from.contains(owner)) continue;
                    patch = patchRemap.copy(patch, false);
                    for (var endpoint : PatchDevices.targets(patch))
                        if (!mapping.containsKey(endpoint))
                            PatchDevices.removeTarget(patch, owner, endpoint);
                    remap(patch, mapping);
                    com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.copied(
                            patch,
                            com.eynny.npships.authoring.patches.PatchSourceOperations.CopyMode
                                    .REGION);
                    patches.add(patch);
                }
            setPatches(config, patches);
        }
        if (contents.entities()) {
            var entityPatches = new ArrayList<>(EntityPatches.entries(target));
            var sourcePatches =
                    snapshot == null ? List.<JsonObject>of() : EntityPatches.entries(snapshot);
            var list = new ListTag();
            for (var v : tag.getList("entities", Tag.TAG_COMPOUND)) {
                var e = (CompoundTag) v;
                if (!(snapshot == null && entityInside(e, replaced))
                        && !(move && entityInside(e, clearedSource))) list.add(e);
            }
            if (snapshot != null)
                for (var v : snapshot.entitiesCopy()) {
                    var e = (CompoundTag) v;
                    if (entityInside(e, from)) {
                        var copy = translateEntity(e, delta);
                        if (!move)
                            copy =
                                    EntityPatches.cloneRoot(
                                            copy,
                                            sourcePatches,
                                            entityPatches,
                                            patchRemap,
                                            EntityPatches.schema(snapshot));
                        list.add(copy);
                    }
                }
            tag.put("entities", list);
            if (!entityPatches.isEmpty() || config.has(EntityPatches.KEY))
                EntityPatches.set(config, entityPatches);
        }
        if (!tag.contains(StructureSegments.VARIANTS))
            tag.put(StructureSegments.VARIANTS, new CompoundTag());
        if (contents.zones())
            com.eynny.npships.authoring.AssemblyPoints.overlay(
                    config,
                    snapshot == null ? null : snapshot.configCopy(),
                    p -> from != null && from.contains(p),
                    p ->
                            (snapshot == null ? replaced.contains(p) : replaced.contains(p))
                                    || move && clearedSource.contains(p),
                    delta,
                    move);
        var sourceVariants =
                snapshot == null
                        ? new CompoundTag()
                        : snapshot.extraCompoundCopy(StructureSegments.VARIANTS);
        if (contents.zones())
            for (String key : List.of("segments", "crew")) {
                var list = new JsonArray();
                if (config.has(key))
                    for (var v : config.getAsJsonArray(key))
                        if (!(snapshot == null && replaced.contains(zoneRegion(v)))
                                && !(move && clearedSource.contains(zoneRegion(v)))) list.add(v);
                if (snapshot != null
                        && snapshot.configCopy() != null
                        && snapshot.configCopy().has(key))
                    for (var v : snapshot.configCopy().getAsJsonArray(key))
                        if (from.contains(zoneRegion(v))) {
                            var z = v.getAsJsonObject().deepCopy();
                            if (!move) {
                                if (z.has(EntityPatches.OCCURRENCE))
                                    z.addProperty(
                                            EntityPatches.OCCURRENCE,
                                            patchRemap
                                                    .occurrence(
                                                            UUID.fromString(
                                                                    z.get(EntityPatches.OCCURRENCE)
                                                                            .getAsString()))
                                                    .toString());
                                String name = z.get("id").getAsString();
                                z.addProperty(
                                        "id",
                                        name.substring(0, Math.min(43, name.length()))
                                                + "_"
                                                + UUID.randomUUID());
                            }
                            z.add(
                                    "origin",
                                    SchematicPatches.vector(zoneRegion(v).origin().offset(delta)));
                            if (key.equals("segments") && z.has("pool"))
                                for (var entry : z.getAsJsonArray("pool")) {
                                    var obj = entry.getAsJsonObject();
                                    String old = obj.get("variant").getAsString(),
                                            id = move ? old : UUID.randomUUID().toString();
                                    var part = sourceVariants.getCompound(old);
                                    if (!move
                                            && part.contains(
                                                    SchematicExtensions.KEY, Tag.TAG_COMPOUND))
                                        SchematicExtensions.refuse(
                                                com.eynny.npships.authoring.AssemblyPoints
                                                        .segmentDocument(part, null),
                                                "copying a segment zone with provider-owned variant"
                                                        + " data");
                                    tag.getCompound(StructureSegments.VARIANTS).put(id, part);
                                    obj.addProperty("variant", id);
                                }
                            list.add(z);
                        }
                config.add(key, list);
            }
        boolean extensions =
                SchematicExtensions.present(target)
                        || snapshot != null && SchematicExtensions.present(snapshot);
        var finalConfig = config;
        var result =
                extensions
                        ? SchematicExtensions.intermediate(() -> finish(target, tag, finalConfig))
                        : finish(target, tag, config);
        if (!extensions) return result;
        var source = snapshot == null || move ? target : snapshot;
        var kind =
                snapshot == null
                        ? PatchAuthoring.Kind.DELETE
                        : move ? PatchAuthoring.Kind.MOVE : PatchAuthoring.Kind.COPY;
        var operation =
                SchematicExtensions.operation(
                        kind,
                        p -> {
                            if (snapshot == null)
                                return contents.blocks() && replaced.contains(p)
                                        ? List.of()
                                        : List.of(p);
                            if (!move)
                                return contents.blocks() && from.contains(p)
                                        ? List.of(p.offset(delta))
                                        : List.of();
                            if (contents.blocks() && from.contains(p))
                                return List.of(p.offset(delta));
                            return contents.blocks() && erased.test(p) ? List.of() : List.of(p);
                        },
                        p -> {
                            if (snapshot == null)
                                return contents.entities() && replaced.bounds().contains(p)
                                        ? List.of()
                                        : List.of(p);
                            if (!move)
                                return contents.entities() && from.bounds().contains(p)
                                        ? List.of(p.add(delta.getX(), delta.getY(), delta.getZ()))
                                        : List.of();
                            if (contents.entities() && from.bounds().contains(p))
                                return List.of(p.add(delta.getX(), delta.getY(), delta.getZ()));
                            return contents.entities() && clearedSource.bounds().contains(p)
                                    ? List.of()
                                    : List.of(p);
                        },
                        snapshot != null && !move ? patchRemap::remap : UnaryOperator.identity(),
                        snapshot != null && !move ? patchRemap::mappedRoot : Optional::of);
        var selection = from == null ? replaced : from;
        return SchematicExtensions.apply(
                source,
                result,
                new PatchAuthoring.Operation(
                        kind, operation.mapping(), selection.origin(), selection.size(), null));
    }

    public static SchematicDocument fill(
            SchematicDocument source,
            Region region,
            Shape shape,
            boolean hollow,
            BlockState state) {
        return fill(source, region, shape, hollow, state, 0);
    }

    public static SchematicDocument fill(
            SchematicDocument source,
            Region region,
            Shape shape,
            boolean hollow,
            BlockState state,
            int turns) {
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(
                    source, "procedural region generation without a declared provider map");
        region.validate(source);
        var size = region.size();
        int rotation = Math.floorMod(turns, 4);
        var modelSize =
                rotation % 2 == 0 ? size : new BlockPos(size.getZ(), size.getY(), size.getX());
        if ((long) size.getX() * size.getY() * size.getZ() > 8_000_000)
            throw new IllegalArgumentException(
                    "Split generation into regions of at most 8,000,000 cells.");
        var tag = source.nbtCopy();
        var config = config(source);
        var palette = source.paletteCopy();
        var encoded = NbtUtils.writeBlockState(state);
        int index = palette.indexOf(encoded);
        if (index < 0) {
            index = palette.size();
            palette.add(encoded);
        }
        tag.put("palette", palette);
        tag.remove("palettes");
        Predicate<BlockPos> erased =
                p -> {
                    var q = unturn(p.subtract(region.origin()), size, rotation);
                    return shape.includes(q.getX(), q.getY(), q.getZ(), modelSize);
                };
        var blocks = new ListTag();
        for (var v : tag.getList("blocks", Tag.TAG_COMPOUND))
            if (!erased.test(SchematicPatches.blockPosition((CompoundTag) v))) blocks.add(v);
        var shell = hollow && !state.isAir() ? HollowShell.build(shape, modelSize) : null;
        if (!state.isAir())
            for (int y = 0; y < size.getY(); y++)
                for (int z = 0; z < size.getZ(); z++)
                    for (int x = 0; x < size.getX(); x++) {
                        int a =
                                rotation == 0
                                        ? x
                                        : rotation == 1
                                                ? z
                                                : rotation == 2
                                                        ? size.getX() - 1 - x
                                                        : size.getZ() - 1 - z;
                        int c =
                                rotation == 0
                                        ? z
                                        : rotation == 1
                                                ? size.getX() - 1 - x
                                                : rotation == 2 ? size.getZ() - 1 - z : x;
                        if (!(hollow
                                ? shell.get((y * modelSize.getZ() + c) * modelSize.getX() + a)
                                : shape.includes(a, y, c, modelSize))) continue;
                        if (blocks.size() >= SchematicDocument.MAX_BLOCKS)
                            throw new IllegalArgumentException(
                                    "Result exceeds the editor block limit: "
                                            + SchematicDocument.MAX_BLOCKS);
                        var b = new CompoundTag();
                        b.put("pos", StructureSegments.ints(region.origin().offset(x, y, z)));
                        b.putInt("state", index);
                        var defaults = new CompoundTag();
                        for (var code :
                                com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch
                                        .all())
                            if (code.authoredBlock(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .getKey(state.getBlock())
                                            .toString(),
                                    defaults,
                                    null)) b.put("nbt", defaults);
                        blocks.add(b);
                    }
        tag.put("blocks", blocks);
        var loot = LootAssignments.read(config);
        config.remove(LootAssignments.KEY);
        for (var e : loot)
            if (!erased.test(e.pos()))
                config = LootAssignments.assign(config, e.pos(), e.table(), e.rolls());
        var patches = new ArrayList<JsonObject>();
        for (var p : SchematicPatches.read(config)) {
            var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
            if (erased.test(owner)) continue;
            for (var end : PatchDevices.targets(p))
                if (erased.test(end)) PatchDevices.removeTarget(p, owner, end);
            patches.add(p);
        }
        setPatches(config, patches);
        return finish(source, tag, config);
    }

    public static SchematicDocument fillMask(
            SchematicDocument source,
            Region region,
            BitSet solid,
            boolean hollow,
            BlockState state) {
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(
                    source, "procedural mask generation without a declared provider map");
        region.validate(source);
        var size = region.size();
        if ((long) size.getX() * size.getY() * size.getZ() > 8_000_000)
            throw new IllegalArgumentException(
                    "Split generation into regions of at most 8,000,000 cells.");
        var tag = source.nbtCopy();
        var config = config(source);
        var palette = source.paletteCopy();
        var encoded = NbtUtils.writeBlockState(state);
        int index = palette.indexOf(encoded);
        if (index < 0) {
            index = palette.size();
            palette.add(encoded);
        }
        tag.put("palette", palette);
        tag.remove("palettes");
        Predicate<BlockPos> erased =
                p -> {
                    var q = p.subtract(region.origin());
                    return region.contains(p)
                            && solid.get(
                                    (q.getY() * size.getZ() + q.getZ()) * size.getX() + q.getX());
                };
        var blocks = new ListTag();
        for (var v : tag.getList("blocks", Tag.TAG_COMPOUND))
            if (!erased.test(SchematicPatches.blockPosition((CompoundTag) v))) blocks.add(v);
        var shell = hollow && !state.isAir() ? HollowShell.build(solid, size) : null;
        if (!state.isAir())
            for (int y = 0; y < size.getY(); y++)
                for (int z = 0; z < size.getZ(); z++)
                    for (int x = 0; x < size.getX(); x++) {
                        if (!(hollow ? shell : solid).get((y * size.getZ() + z) * size.getX() + x))
                            continue;
                        if (blocks.size() >= SchematicDocument.MAX_BLOCKS)
                            throw new IllegalArgumentException(
                                    "Result exceeds the editor block limit: "
                                            + SchematicDocument.MAX_BLOCKS);
                        var b = new CompoundTag();
                        b.put("pos", StructureSegments.ints(region.origin().offset(x, y, z)));
                        b.putInt("state", index);
                        var defaults = new CompoundTag();
                        for (var code :
                                com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch
                                        .all())
                            if (code.authoredBlock(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .getKey(state.getBlock())
                                            .toString(),
                                    defaults,
                                    null)) b.put("nbt", defaults);
                        blocks.add(b);
                    }
        tag.put("blocks", blocks);
        var loot = LootAssignments.read(config);
        config.remove(LootAssignments.KEY);
        for (var e : loot)
            if (!erased.test(e.pos()))
                config = LootAssignments.assign(config, e.pos(), e.table(), e.rolls());
        var patches = new ArrayList<JsonObject>();
        for (var p : SchematicPatches.read(config)) {
            var owner = SchematicPatches.position(p.getAsJsonArray("pos"));
            if (erased.test(owner)) continue;
            for (var end : PatchDevices.targets(p))
                if (erased.test(end)) PatchDevices.removeTarget(p, owner, end);
            patches.add(p);
        }
        setPatches(config, patches);
        return finish(source, tag, config);
    }

    private static BlockPos unturn(BlockPos p, BlockPos size, int rotation) {
        return switch (rotation) {
            case 1 -> new BlockPos(p.getZ(), p.getY(), size.getX() - 1 - p.getX());
            case 2 ->
                    new BlockPos(size.getX() - 1 - p.getX(), p.getY(), size.getZ() - 1 - p.getZ());
            case 3 -> new BlockPos(size.getZ() - 1 - p.getZ(), p.getY(), p.getX());
            default -> p;
        };
    }

    private static SchematicDocument finish(
            SchematicDocument owner, CompoundTag tag, JsonObject config) {
        config = EntityPatches.prune(tag, config);
        SchematicReframe.collectUnused(tag, config);
        SchematicPatches.embed(tag, config);
        var result = new SchematicDocument(tag, config).inheritPatchValidation(owner);
        StructureSegments.validate(tag, StructureSegments.read(config));
        StructureCrew.validateBounds(result, config);
        com.eynny.npships.authoring.SchematicSafety.validate(tag);
        SchematicPatches.validatePatches(result, true);
        return result;
    }

    private static JsonObject config(SchematicDocument doc) {
        return SchematicPatches.authoringConfig(doc);
    }

    private static void setPatches(JsonObject config, List<JsonObject> patches) {
        SchematicPatches.set(config, patches);
    }

    private static Region zoneRegion(JsonElement value) {
        var z = value.getAsJsonObject();
        return new Region(
                SchematicPatches.position(z.getAsJsonArray("origin")),
                SchematicPatches.position(z.getAsJsonArray("size")));
    }

    private static boolean entityInside(CompoundTag entity, Region region) {
        var p = entity.getList("pos", Tag.TAG_DOUBLE);
        var point = new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2));
        var data = entity.getCompound("nbt");
        var box = SchematicGlue.bounds(data, point);
        if (box != null)
            return region.bounds().contains(box.minX, box.minY, box.minZ)
                    && region.bounds().contains(box.maxX - 1e-7, box.maxY - 1e-7, box.maxZ - 1e-7);
        return region.bounds().contains(point);
    }

    private static CompoundTag translateEntity(CompoundTag entity, BlockPos delta) {
        var e = entity.copy();
        var p = e.getList("pos", Tag.TAG_DOUBLE);
        var point =
                new Vec3(
                        p.getDouble(0) + delta.getX(),
                        p.getDouble(1) + delta.getY(),
                        p.getDouble(2) + delta.getZ());
        var list = new ListTag();
        list.add(DoubleTag.valueOf(point.x));
        list.add(DoubleTag.valueOf(point.y));
        list.add(DoubleTag.valueOf(point.z));
        e.put("pos", list);
        e.put(
                "blockPos",
                StructureSegments.ints(PreviewEntityData.translatedAnchor(e, point, delta)));
        e.getCompound("nbt").remove("UUID");
        return e;
    }

    private static void remap(JsonObject patch, Map<BlockPos, BlockPos> positions) {
        var old = SchematicPatches.position(patch.getAsJsonArray("pos"));
        var next = positions.getOrDefault(old, old);
        if (!old.equals(next)) PatchInstances.stabilize(patch, false);
        remapOffsets(patch, old, next, positions);
        patch.add("pos", SchematicPatches.vector(next));
    }

    private static void remapOffsets(
            JsonElement value, BlockPos owner, BlockPos next, Map<BlockPos, BlockPos> positions) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("offset")) {
                var p = owner.offset(SchematicPatches.position(o.getAsJsonArray("offset")));
                o.add(
                        "offset",
                        SchematicPatches.vector(positions.getOrDefault(p, p).subtract(next)));
            }
            for (var e : o.entrySet())
                if (!e.getKey().equals("offset"))
                    remapOffsets(e.getValue(), owner, next, positions);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) remapOffsets(e, owner, next, positions);
    }
}
