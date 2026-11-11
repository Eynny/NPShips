package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Mirrors creation/deletion deltas in schematic coordinates, in the same undo transaction. */
public final class SchematicSymmetry {
    private SchematicSymmetry() {}

    public static BlockPos point(BlockPos p, BlockPos size, int axes) {
        return new BlockPos(
                (axes & 1) == 0 ? p.getX() : size.getX() - 1 - p.getX(),
                (axes & 2) == 0 ? p.getY() : size.getY() - 1 - p.getY(),
                (axes & 4) == 0 ? p.getZ() : size.getZ() - 1 - p.getZ());
    }

    public static SchematicRegions.Region region(
            SchematicRegions.Region r, BlockPos size, int axes) {
        var p = point(r.origin().offset(r.size()).offset(-1, -1, -1), size, axes);
        return new SchematicRegions.Region(
                new BlockPos(
                        (axes & 1) == 0 ? r.origin().getX() : p.getX(),
                        (axes & 2) == 0 ? r.origin().getY() : p.getY(),
                        (axes & 4) == 0 ? r.origin().getZ() : p.getZ()),
                r.size());
    }

    private static Vec3 vector(Vec3 p, int axes) {
        return new Vec3(
                (axes & 1) == 0 ? p.x : -p.x,
                (axes & 2) == 0 ? p.y : -p.y,
                (axes & 4) == 0 ? p.z : -p.z);
    }

    private static Vec3 point(Vec3 p, BlockPos size, int axes) {
        return new Vec3(
                (axes & 1) == 0 ? p.x : size.getX() - p.x,
                (axes & 2) == 0 ? p.y : size.getY() - p.y,
                (axes & 4) == 0 ? p.z : size.getZ() - p.z);
    }

    private static ListTag list(Vec3 p) {
        var a = new ListTag();
        a.add(DoubleTag.valueOf(p.x));
        a.add(DoubleTag.valueOf(p.y));
        a.add(DoubleTag.valueOf(p.z));
        return a;
    }

    private static Vec3 vec(ListTag a) {
        return new Vec3(a.getDouble(0), a.getDouble(1), a.getDouble(2));
    }

    private static BlockState state(BlockState s, int axes) {
        if ((axes & 1) != 0) s = s.mirror(Mirror.FRONT_BACK);
        if ((axes & 4) != 0) s = s.mirror(Mirror.LEFT_RIGHT);
        if ((axes & 2) != 0) {
            for (var property : s.getProperties()) {
                if (property instanceof DirectionProperty direction) {
                    var d = s.getValue(direction);
                    if (d.getAxis() == Direction.Axis.Y) s = s.setValue(direction, d.getOpposite());
                } else if (property == BlockStateProperties.ATTACH_FACE) {
                    var face = s.getValue(BlockStateProperties.ATTACH_FACE);
                    if (face != net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                        s =
                                s.setValue(
                                        BlockStateProperties.ATTACH_FACE,
                                        face
                                                        == net.minecraft.world.level.block.state
                                                                .properties.AttachFace.FLOOR
                                                ? net.minecraft.world.level.block.state.properties
                                                        .AttachFace.CEILING
                                                : net.minecraft.world.level.block.state.properties
                                                        .AttachFace.FLOOR);
                } else if (property == BlockStateProperties.HALF)
                    s =
                            s.setValue(
                                    BlockStateProperties.HALF,
                                    s.getValue(BlockStateProperties.HALF) == Half.TOP
                                            ? Half.BOTTOM
                                            : Half.TOP);
                else if (property == BlockStateProperties.SLAB_TYPE
                        && s.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE)
                    s =
                            s.setValue(
                                    BlockStateProperties.SLAB_TYPE,
                                    s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP
                                            ? SlabType.BOTTOM
                                            : SlabType.TOP);
            }
        }
        return s;
    }

    private static CompoundTag entity(
            CompoundTag entry, BlockPos size, int axes, net.minecraft.world.level.Level level) {
        var e = entry.copy();
        var p = point(vec(e.getList("pos", Tag.TAG_DOUBLE)), size, axes);
        e.put("pos", list(p));
        e.put("blockPos", StructureSegments.ints(BlockPos.containing(p)));
        var n = e.getCompound("nbt");
        n.remove("UUID");
        n.remove("Pos");
        n.put("Rotation", rotation(n.getList("Rotation", Tag.TAG_FLOAT), axes));
        EntitySettings.transformPendingRotation(n, angles -> rotation(angles, axes));
        if (n.contains("Motion"))
            n.put("Motion", list(vector(vec(n.getList("Motion", Tag.TAG_DOUBLE)), axes)));
        if (SchematicGlue.supported(n.getString("id"))) {
            // Native setPos/moveTo re-centers glue at its bottom-center anchor. A vertical
            // reflection must reflect its complete volume and then choose that new anchor.
            var anchor =
                    SchematicGlue.transformVolume(
                            n,
                            vec(entry.getList("pos", Tag.TAG_DOUBLE)),
                            value -> point(value, size, axes));
            e.put("pos", list(anchor));
            e.put("blockPos", StructureSegments.ints(BlockPos.containing(anchor)));
        }
        SchematicMirrorData.entity(e, entry, size, axes, level);
        return e;
    }

    private static ListTag rotation(ListTag angles, int axes) {
        float yaw = angles.getFloat(0), pitch = angles.getFloat(1);
        if ((axes & 1) != 0) yaw = -yaw;
        if ((axes & 4) != 0) yaw = 180 - yaw;
        if ((axes & 2) != 0) pitch = -pitch;
        var out = new ListTag();
        out.add(FloatTag.valueOf(yaw));
        out.add(FloatTag.valueOf(pitch));
        return out;
    }

    private static boolean sameEntity(CompoundTag a, CompoundTag b) {
        var x = a.copy();
        var y = b.copy();
        x.getCompound("nbt").remove("UUID");
        y.getCompound("nbt").remove("UUID");
        // Missing rotation and [0,0] have the same visual meaning.
        for (var e : List.of(x, y)) {
            var n = e.getCompound("nbt");
            var r = n.getList("Rotation", Tag.TAG_FLOAT);
            if (r.getFloat(0) == 0 && r.getFloat(1) == 0) n.remove("Rotation");
        }
        return x.equals(y);
    }

    public static SchematicDocument mirror(SchematicDocument source, int axes) {
        return mirror(source, axes, null);
    }

    public static SchematicDocument mirror(
            SchematicDocument source, int axes, net.minecraft.world.level.Level level) {
        return mirror(source, axes, level, false);
    }

    /** New layer copies need distinct variant names when merged beside the original layer. */
    static SchematicDocument mirrorCopy(
            SchematicDocument source, int axes, net.minecraft.world.level.Level level) {
        if ((axes & 7) != 0 && SchematicExtensions.present(source))
            SchematicExtensions.refuse(source, "mirror-copy layer identity projection");
        return mirror(source, axes, level, true);
    }

    private static SchematicDocument mirror(
            SchematicDocument source,
            int axes,
            net.minecraft.world.level.Level level,
            boolean copyIdentifiers) {
        if ((axes & 7) == 0) return source;
        var tag = source.nbtCopy();
        var palette = source.paletteCopy();
        var originalPalette = palette.copy();
        boolean deferUnknown = SchematicExtensions.canDeferMirror(source) && !copyIdentifiers;
        Set<BlockPos> deferredPositions = deferUnknown ? new HashSet<>() : Set.of();
        for (int i = 0; i < palette.size(); i++) {
            SchematicMirrorData.requireKnownState(palette.getCompound(i));
            palette.set(
                    i,
                    NbtUtils.writeBlockState(
                            state(
                                    NbtUtils.readBlockState(
                                            BuiltInRegistries.BLOCK.asLookup(),
                                            palette.getCompound(i)),
                                    axes)));
        }
        tag.put("palette", palette);
        tag.remove("palettes");
        for (var v : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var b = (CompoundTag) v;
            var a = b.getList("pos", Tag.TAG_INT);
            var p = point(new BlockPos(a.getInt(0), a.getInt(1), a.getInt(2)), source.size(), axes);
            b.put("pos", StructureSegments.ints(p));
            if (b.contains("nbt")) {
                var n = b.getCompound("nbt");
                if (!SchematicMirrorData.blockEntity(
                        NbtUtils.readBlockState(
                                BuiltInRegistries.BLOCK.asLookup(),
                                originalPalette.getCompound(b.getInt("state"))),
                        n,
                        axes,
                        deferUnknown)) deferredPositions.add(p);
                n.putInt("x", p.getX());
                n.putInt("y", p.getY());
                n.putInt("z", p.getZ());
            }
        }
        var entities = new ListTag();
        for (var v : source.entitiesCopy())
            entities.add(entity((CompoundTag) v, source.size(), axes, level));
        tag.put("entities", entities);
        var c = SchematicPatches.authoringConfig(source);
        // read() returns detached entries, so rebuild the patch list explicitly.
        var entries = new JsonArray();
        for (var patch : SchematicPatches.read(c)) {
            com.eynny.npships.authoring.patches.PatchInstances.stabilize(patch, false);
            patch.add(
                    "pos",
                    SchematicPatches.vector(
                            point(
                                    SchematicPatches.position(patch.getAsJsonArray("pos")),
                                    source.size(),
                                    axes)));
            offsets(patch, axes);
            entries.add(patch);
        }
        if (c.has("patches"))
            SchematicPatches.set(
                    c, entries.asList().stream().map(JsonElement::getAsJsonObject).toList());
        for (String key : List.of("crew", "segments"))
            if (c.has(key))
                for (var v : c.getAsJsonArray(key)) {
                    var z = v.getAsJsonObject();
                    if (copyIdentifiers) {
                        if (z.has("id"))
                            z.addProperty("id", mirrorId(z.get("id").getAsString(), axes));
                        if (key.equals("segments") && z.has("pool"))
                            for (var item : z.getAsJsonArray("pool")) {
                                var variant = item.getAsJsonObject();
                                variant.addProperty(
                                        "variant",
                                        mirrorId(variant.get("variant").getAsString(), axes));
                            }
                    }
                    var r =
                            region(
                                    new SchematicRegions.Region(
                                            SchematicPatches.position(z.getAsJsonArray("origin")),
                                            SchematicPatches.position(z.getAsJsonArray("size"))),
                                    source.size(),
                                    axes);
                    z.add("origin", SchematicPatches.vector(r.origin()));
                    if (key.equals("segments") && z.has("pool"))
                        for (var choice : z.getAsJsonArray("pool"))
                            mirrorLoot(choice.getAsJsonObject(), r.size(), axes);
                }
        com.eynny.npships.authoring.AssemblyPoints.map(
                c, p -> point(p, source.size(), axes), p -> true);
        if (copyIdentifiers && c.has(com.eynny.npships.authoring.AssemblyPoints.KEY))
            for (var entry : c.getAsJsonArray(com.eynny.npships.authoring.AssemblyPoints.KEY)) {
                var marker = entry.getAsJsonObject();
                marker.addProperty(
                        "id",
                        java.util
                                .UUID
                                .nameUUIDFromBytes(
                                        ("npships:assembly-point-mirror:"
                                                        + marker.get("id").getAsString()
                                                        + ":"
                                                        + axes)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .toString());
            }
        mirrorLoot(c, source.size(), axes);
        var variants = tag.getCompound(StructureSegments.VARIANTS);
        var mirroredVariants = new CompoundTag();
        for (String id : variants.getAllKeys())
            mirroredVariants.put(
                    copyIdentifiers ? mirrorId(id, axes) : id,
                    mirror(
                                    com.eynny.npships.authoring.AssemblyPoints.segmentDocument(
                                            variants.getCompound(id), null),
                                    axes,
                                    level,
                                    copyIdentifiers)
                            .nbtCopy());
        if (!mirroredVariants.isEmpty()) tag.put(StructureSegments.VARIANTS, mirroredVariants);
        SchematicPatches.embed(tag, c);
        var result = new SchematicDocument(tag, c);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.validateDocument(result);
        return SchematicExtensions.present(source)
                ? SchematicExtensions.applyMirror(
                        source,
                        result,
                        SchematicExtensions.mirror(source.size(), axes),
                        deferredPositions,
                        axes)
                : result;
    }

    private static void mirrorLoot(JsonObject owner, BlockPos size, int axes) {
        com.eynny.npships.authoring.LootAssignments.read(owner);
        if (!owner.has(com.eynny.npships.authoring.LootAssignments.KEY)) return;
        for (var value : owner.getAsJsonArray(com.eynny.npships.authoring.LootAssignments.KEY)) {
            var entry = value.getAsJsonObject();
            entry.add(
                    "pos",
                    SchematicPatches.vector(
                            point(
                                    SchematicPatches.position(entry.getAsJsonArray("pos")),
                                    size,
                                    axes)));
        }
    }

    private static String mirrorId(String id, int axes) {
        return "mirror_"
                + axes
                + "_"
                + UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
    }

    private static void offsets(JsonElement v, int axes) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(v)) return;
        if (v.isJsonObject())
            for (var e : v.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("offset")) {
                    var p = SchematicPatches.position(e.getValue().getAsJsonArray());
                    var q = vector(Vec3.atLowerCornerOf(p), axes);
                    e.setValue(SchematicPatches.vector(BlockPos.containing(q)));
                } else offsets(e.getValue(), axes);
            }
        else if (v.isJsonArray()) for (var e : v.getAsJsonArray()) offsets(e, axes);
    }

    public static SchematicDocument delta(
            SchematicDocument before, SchematicDocument after, int axes) {
        return delta(before, after, axes, after.size());
    }

    public static SchematicDocument delta(
            SchematicDocument before, SchematicDocument after, int axes, BlockPos mirrorSpan) {
        return delta(before, after, axes, mirrorSpan, null);
    }

    public static SchematicDocument delta(
            SchematicDocument before,
            SchematicDocument after,
            int axes,
            BlockPos mirrorSpan,
            net.minecraft.world.level.Level level) {
        if (axes == 0 || !before.size().equals(after.size())) return after;
        if (SchematicExtensions.present(after))
            SchematicExtensions.refuse(
                    after,
                    "created/removed symmetry copies without an exact provider transaction map");
        var changed = after.changedBlocks(before, SchematicDocument.MAX_BLOCKS);
        if (changed == null) {
            changed = new HashSet<>(before.blocks().keySet());
            changed.addAll(after.blocks().keySet());
        }
        var palette = after.paletteCopy();
        var changes = new HashMap<BlockPos, CompoundTag>();
        var config = SchematicPatches.authoringConfig(after);
        var sourcePatches = SchematicPatches.read(config);
        var currentEntities = after.extensionEntitiesReadOnly();
        var entities = currentEntities.copy();
        var oldEntities = before.entitiesCopy();
        var entityPatches = new ArrayList<>(EntityPatches.entries(after));
        for (int mask = 1; mask < 8; mask++) {
            if ((mask & axes) != mask) continue;
            var patchRemap = new com.eynny.npships.authoring.patches.PatchInstances.Remap();
            for (var p : changed) {
                var q = point(p, mirrorSpan, mask);
                if (changed.contains(q)
                        || q.getX() < 0
                        || q.getY() < 0
                        || q.getZ() < 0
                        || q.getX() >= after.size().getX()
                        || q.getY() >= after.size().getY()
                        || q.getZ() >= after.size().getZ()) continue;
                var b = after.blockEntryCopy(p);
                if (b != null) {
                    SchematicMirrorData.requireKnownState(palette.getCompound(b.getInt("state")));
                    var encoded =
                            NbtUtils.writeBlockState(
                                    state(
                                            NbtUtils.readBlockState(
                                                    BuiltInRegistries.BLOCK.asLookup(),
                                                    palette.getCompound(b.getInt("state"))),
                                            mask));
                    int index = palette.indexOf(encoded);
                    if (index < 0) {
                        index = palette.size();
                        palette.add(encoded);
                    }
                    b.putInt("state", index);
                    b.put("pos", StructureSegments.ints(q));
                    if (b.contains("nbt")) {
                        var n = b.getCompound("nbt");
                        SchematicMirrorData.blockEntity(
                                NbtUtils.readBlockState(
                                        BuiltInRegistries.BLOCK.asLookup(),
                                        palette.getCompound(after.blocks().get(p))),
                                n,
                                mask);
                        n.putInt("x", q.getX());
                        n.putInt("y", q.getY());
                        n.putInt("z", q.getZ());
                    }
                }
                changes.put(q, b);
                config = SchematicPatches.move(config, q, q, false);
                if (b != null) {
                    var patches = new ArrayList<>(SchematicPatches.read(config));
                    for (var patch : sourcePatches)
                        if (SchematicPatches.position(patch.getAsJsonArray("pos")).equals(p)) {
                            var reflected = patchRemap.copy(patch, false);
                            reflected.add("pos", SchematicPatches.vector(q));
                            offsets(reflected, mask);
                            com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch
                                    .copied(
                                            reflected,
                                            com.eynny.npships.authoring.patches
                                                    .PatchSourceOperations.CopyMode.SYMMETRY);
                            patches.add(reflected);
                        }
                    SchematicPatches.set(config, patches);
                }
                config = com.eynny.npships.authoring.LootAssignments.assign(config, q, null);
                if (b != null)
                    for (var loot :
                            com.eynny.npships.authoring.LootAssignments.read(after.configCopy()))
                        if (loot.pos().equals(p))
                            config =
                                    com.eynny.npships.authoring.LootAssignments.assign(
                                            config, q, loot.table(), loot.rolls());
            }
            if (!before.sameEntities(after)) {
                for (var e : oldEntities)
                    if (!currentEntities.contains(e)) {
                        var reflected = entity((CompoundTag) e, mirrorSpan, mask, level);
                        entities.removeIf(v -> sameEntity((CompoundTag) v, reflected));
                    }
                for (var e : currentEntities)
                    if (!oldEntities.contains(e)) {
                        var reflected = entity((CompoundTag) e, mirrorSpan, mask, level);
                        if (entities.stream()
                                .noneMatch(v -> sameEntity((CompoundTag) v, reflected))) {
                            var id = EntityIdentity.read(((CompoundTag) e).getCompound("nbt"));
                            var previous =
                                    oldEntities.stream()
                                            .map(CompoundTag.class::cast)
                                            .filter(
                                                    v ->
                                                            id != null
                                                                    && id.equals(
                                                                            com.eynny.npships
                                                                                    .authoring
                                                                                    .patches
                                                                                    .EntityIdentity
                                                                                    .read(
                                                                                            v
                                                                                                    .getCompound(
                                                                                                            "nbt"))))
                                            .findFirst();
                            java.util.List<CompoundTag> matches = java.util.List.of();
                            if (previous.isPresent()) {
                                var oldReflection = entity(previous.get(), mirrorSpan, mask, level);
                                matches =
                                        oldEntities.stream()
                                                .map(CompoundTag.class::cast)
                                                .filter(
                                                        v ->
                                                                sameEntity(v, oldReflection)
                                                                        && !id.equals(
                                                                                com.eynny.npships
                                                                                        .authoring
                                                                                        .patches
                                                                                        .EntityIdentity
                                                                                        .read(
                                                                                                v
                                                                                                        .getCompound(
                                                                                                                "nbt"))))
                                                .toList();
                                if (matches.size() > 1)
                                    throw new IllegalArgumentException(
                                            "Cannot preserve ambiguous coincident entity identities"
                                                    + " in symmetry; edit without symmetry");
                            }
                            if (matches.size() == 1
                                    && EntityIdentity.read(matches.getFirst().getCompound("nbt"))
                                            != null) {
                                EntityPatches.preserve(
                                        matches.getFirst().getCompound("nbt"),
                                        reflected.getCompound("nbt"));
                                entities.add(reflected);
                            } else
                                entities.add(
                                        EntityPatches.cloneRoot(
                                                reflected,
                                                EntityPatches.entries(after),
                                                entityPatches,
                                                patchRemap,
                                                EntityPatches.schema(after)));
                        }
                    }
            }
            for (String key : List.of("crew", "segments")) {
                var old =
                        before.hasConfig() && before.configCopy().has(key)
                                ? before.configCopy().getAsJsonArray(key)
                                : new JsonArray();
                var current =
                        after.hasConfig() && after.configCopy().has(key)
                                ? after.configCopy().getAsJsonArray(key)
                                : new JsonArray();
                var output = config.has(key) ? config.getAsJsonArray(key) : new JsonArray();
                var reflectedIds = new HashMap<String, String>();
                for (var e : old)
                    if (!current.contains(e)) {
                        var z = e.getAsJsonObject();
                        var r =
                                region(
                                        new SchematicRegions.Region(
                                                SchematicPatches.position(
                                                        z.getAsJsonArray("origin")),
                                                SchematicPatches.position(
                                                        z.getAsJsonArray("size"))),
                                        mirrorSpan,
                                        mask);
                        output.asList()
                                .removeIf(
                                        v -> {
                                            var candidate = v.getAsJsonObject();
                                            boolean matches =
                                                    candidate
                                                                    .get("origin")
                                                                    .equals(
                                                                            SchematicPatches.vector(
                                                                                    r.origin()))
                                                            && candidate
                                                                    .get("size")
                                                                    .equals(
                                                                            SchematicPatches.vector(
                                                                                    r.size()));
                                            if (matches)
                                                reflectedIds.put(
                                                        z.get("id").getAsString(),
                                                        candidate.get("id").getAsString());
                                            return matches;
                                        });
                    }
                for (var e : current)
                    if (!old.contains(e)) {
                        var z = e.getAsJsonObject().deepCopy();
                        var r =
                                region(
                                        new SchematicRegions.Region(
                                                SchematicPatches.position(
                                                        z.getAsJsonArray("origin")),
                                                SchematicPatches.position(
                                                        z.getAsJsonArray("size"))),
                                        mirrorSpan,
                                        mask);
                        boolean exists =
                                output.asList().stream()
                                        .anyMatch(
                                                v ->
                                                        v.getAsJsonObject()
                                                                        .get("origin")
                                                                        .equals(
                                                                                SchematicPatches
                                                                                        .vector(
                                                                                                r
                                                                                                        .origin()))
                                                                && v.getAsJsonObject()
                                                                        .get("size")
                                                                        .equals(
                                                                                SchematicPatches
                                                                                        .vector(
                                                                                                r
                                                                                                        .size())));
                        if (!exists) {
                            z.add("origin", SchematicPatches.vector(r.origin()));
                            z.addProperty(
                                    "id",
                                    reflectedIds.getOrDefault(
                                            z.get("id").getAsString(),
                                            z.get("id").getAsString() + "_mirror_" + mask));
                            if (z.has(EntityPatches.OCCURRENCE))
                                z.addProperty(
                                        EntityPatches.OCCURRENCE,
                                        java.util.UUID.randomUUID().toString());
                            output.add(z);
                        }
                    }
                if (!output.isEmpty() || config.has(key)) config.add(key, output);
            }
        }
        com.eynny.npships.authoring.AssemblyPoints.mirrorDelta(
                before.configCopy(), after.configCopy(), config, mirrorSpan, axes);
        SchematicBlocks.embedChanges(after, changes, config);
        if (!entityPatches.isEmpty()) EntityPatches.set(config, entityPatches);
        var result = after.withBlocks(changes, palette, config).withEntities(entities);
        if (config.has("segments"))
            StructureSegments.validate(result.nbtCopy(), StructureSegments.read(config));
        com.eynny.npships.authoring.StructureCrew.validateBounds(result, config);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.validateDocument(result);
        return result;
    }
}
