package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureCrew;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.document.EntitySettings;
import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicGlue;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.authoring.validation.ValidationProblem;
import com.google.gson.*;
import com.simibubi.create.content.contraptions.StructureTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Pure, deterministic layer generation. Sources stay immutable across previews and resizes. */
public final class AreaGeometry {
    private AreaGeometry() {}

    public record Result(SchematicDocument content, BitSet mask) {}

    public record Weighted(BlockState state, double weight) {
        public Weighted {
            if (!Double.isFinite(weight) || weight <= 0)
                throw new IllegalArgumentException("Block weights must be positive.");
        }
    }

    public static int index(BlockPos p, BlockPos size) {
        return (p.getY() * size.getZ() + p.getZ()) * size.getX() + p.getX();
    }

    public static BlockPos position(int i, BlockPos size) {
        return new BlockPos(
                i % size.getX(), i / (size.getX() * size.getZ()), i / size.getX() % size.getZ());
    }

    public static Result hollow(Result full, BitSet solid) {
        var size = full.content().size();
        var interior = (BitSet) solid.clone();
        interior.andNot(HollowShell.build(solid, size));
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (int i = interior.nextSetBit(0); i >= 0; i = interior.nextSetBit(i + 1))
            changes.put(position(i, size), null);
        if (!changes.isEmpty()
                && com.eynny.npships.authoring.document.SchematicExtensions.present(full.content()))
            com.eynny.npships.authoring.document.SchematicExtensions.refuse(
                    full.content(),
                    "hollow geometry deletion without an exact provider reference map");
        return new Result(
                replace(full.content(), changes, full.content().paletteCopy()), full.mask());
    }

    public static BitSet occupied(SchematicDocument document) {
        var result = new BitSet();
        for (var p : document.blocks().keySet()) result.set(index(p, document.size()));
        return result;
    }

    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed) {
        return render(
                source,
                sourceMask,
                size,
                shape,
                generated,
                hollow,
                brush,
                deformation,
                random,
                seed,
                0);
    }

    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed,
            int generatedTurns) {
        return render(
                source,
                sourceMask,
                size,
                shape,
                generated,
                hollow,
                brush,
                deformation,
                random,
                seed,
                generatedTurns,
                0);
    }

    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed,
            int generatedTurns,
            double originalWeight) {
        return render(
                source,
                sourceMask,
                size,
                shape,
                generated,
                hollow,
                brush,
                deformation,
                random,
                seed,
                generatedTurns,
                originalWeight,
                false);
    }

    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed,
            int generatedTurns,
            double originalWeight,
            boolean continuous) {
        return render(
                source,
                sourceMask,
                size,
                shape,
                generated,
                hollow,
                brush,
                deformation,
                random,
                seed,
                generatedTurns,
                originalWeight,
                continuous,
                false);
    }

    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed,
            int generatedTurns,
            double originalWeight,
            boolean continuous,
            boolean allowConfiguredLoss) {
        return render(
                source,
                sourceMask,
                size,
                shape,
                generated,
                hollow,
                brush,
                deformation,
                random,
                seed,
                generatedTurns,
                originalWeight,
                continuous,
                allowConfiguredLoss,
                false);
    }

    /** Retained source cells can take priority over generated and random material. */
    public static Result render(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos size,
            SchematicRegions.Shape shape,
            boolean generated,
            boolean hollow,
            BlockState brush,
            ShapeDeformation deformation,
            List<Weighted> random,
            long seed,
            int generatedTurns,
            double originalWeight,
            boolean continuous,
            boolean allowConfiguredLoss,
            boolean preserveSource) {
        if (!Double.isFinite(originalWeight) || originalWeight < 0)
            throw new IllegalArgumentException(
                    "Original block weight must be finite and nonnegative.");
        if ((long) size.getX() * size.getY() * size.getZ() > 8_000_000)
            throw new IllegalArgumentException("Area exceeds 8,000,000 cells.");
        if (com.eynny.npships.authoring.document.SchematicExtensions.present(source)
                && (generated
                        || hollow
                        || !random.isEmpty()
                        || !deformation.identity()
                        || !source.size().equals(size)))
            com.eynny.npships.authoring.document.SchematicExtensions.refuse(
                    source,
                    "procedural/resampled area geometry without an exact provider reference map");
        if (generated && hollow && !brush.isAir() && !deformation.identity()) {
            // Transform the solid first: resampling a one-voxel wall tears holes in it.
            var full =
                    render(
                            source,
                            sourceMask,
                            size,
                            shape,
                            true,
                            false,
                            brush,
                            deformation,
                            random,
                            seed,
                            generatedTurns,
                            originalWeight,
                            continuous,
                            allowConfiguredLoss,
                            preserveSource);
            int turns = Math.floorMod(generatedTurns, 4);
            var model = turns % 2 == 0 ? size : new BlockPos(size.getZ(), size.getY(), size.getX());
            var inverse = deformation.inverseSampler(size);
            var solid = new BitSet();
            for (int y = 0; y < size.getY(); y++)
                for (int z = 0; z < size.getZ(); z++)
                    for (int x = 0; x < size.getX(); x++) {
                        var p = new BlockPos(x, y, z);
                        var sampled = inverse.apply(Vec3.atCenterOf(p));
                        if (sampled == null) continue;
                        var q = BlockPos.containing(sampled);
                        var local =
                                switch (turns) {
                                    case 1 ->
                                            new BlockPos(
                                                    q.getZ(), q.getY(), size.getX() - 1 - q.getX());
                                    case 2 ->
                                            new BlockPos(
                                                    size.getX() - 1 - q.getX(),
                                                    q.getY(),
                                                    size.getZ() - 1 - q.getZ());
                                    case 3 ->
                                            new BlockPos(
                                                    size.getZ() - 1 - q.getZ(), q.getY(), q.getX());
                                    default -> q;
                                };
                        if (shape.includes(local.getX(), local.getY(), local.getZ(), model))
                            solid.set(index(p, size));
                    }
            var interior = (BitSet) solid.clone();
            interior.andNot(HollowShell.build(solid, size));
            if (preserveSource && !sourceMask.isEmpty()) {
                var retained =
                        resample(
                                source,
                                sourceMask,
                                size,
                                deformation,
                                continuous,
                                allowConfiguredLoss);
                var surviving = retained.mask();
                // Continuity sampling differs for a sparse source and the completed solid.
                // Protect actual retained material, not an unrelated generated neighbor.
                for (int i = surviving.nextSetBit(0); i >= 0; i = surviving.nextSetBit(i + 1)) {
                    var p = position(i, size);
                    if (!full.content().blockName(p).equals(retained.content().blockName(p))
                            || !Objects.equals(
                                    full.content().blockEntityData(p),
                                    retained.content().blockEntityData(p))) surviving.clear(i);
                }
                interior.andNot(surviving);
            }
            var changes = new HashMap<BlockPos, CompoundTag>();
            for (int i = interior.nextSetBit(0); i >= 0; i = interior.nextSetBit(i + 1))
                changes.put(position(i, size), null);
            return new Result(
                    replace(full.content(), changes, full.content().paletteCopy()), full.mask());
        }
        Result resized =
                resample(
                        source,
                        sourceMask,
                        size,
                        ShapeDeformation.IDENTITY,
                        continuous,
                        allowConfiguredLoss);
        var content = resized.content();
        var mask = resized.mask();
        var retained =
                preserveSource && (generated || !random.isEmpty()) && !mask.isEmpty()
                        ? (BitSet) mask.clone()
                        : null;
        if (generated) {
            int turns = Math.floorMod(generatedTurns, 4);
            var model = turns % 2 == 0 ? size : new BlockPos(size.getZ(), size.getY(), size.getX());
            var solid = ShapeDeformation.IDENTITY.raster(shape, model);
            var raw = hollow && !brush.isAir() ? HollowShell.build(solid, model) : solid;
            var cells = new BitSet();
            var coverage = new BitSet();
            for (int i = solid.nextSetBit(0); i >= 0; i = solid.nextSetBit(i + 1)) {
                var p = position(i, model);
                var q =
                        switch (turns) {
                            case 1 -> new BlockPos(size.getX() - 1 - p.getZ(), p.getY(), p.getX());
                            case 2 ->
                                    new BlockPos(
                                            size.getX() - 1 - p.getX(),
                                            p.getY(),
                                            size.getZ() - 1 - p.getZ());
                            case 3 -> new BlockPos(p.getZ(), p.getY(), size.getZ() - 1 - p.getX());
                            default -> p;
                        };
                int at = index(q, size);
                coverage.set(at);
                if (raw.get(i)) cells.set(at);
            }
            // A hollow interior is explicit air. Outside the figure, background/source cells stay
            // intact.
            var palette = content.paletteCopy();
            var encoded = NbtUtils.writeBlockState(brush);
            int slot = palette.indexOf(encoded);
            if (slot < 0) {
                slot = palette.size();
                palette.add(encoded);
            }
            var changes = new HashMap<BlockPos, CompoundTag>();
            for (int i = coverage.nextSetBit(0); i >= 0; i = coverage.nextSetBit(i + 1)) {
                if (retained != null && retained.get(i)) continue;
                var p = position(i, size);
                boolean filled = cells.get(i) && !brush.isAir();
                mask.set(i);
                if (!filled) {
                    if (content.blocks().containsKey(p)) changes.put(p, null);
                    continue;
                }
                var entry = matchingEntry(content, p, brush);
                entry.put("pos", StructureSegments.ints(p));
                entry.putInt("state", slot);
                changes.put(p, entry);
            }
            content = replace(content, changes, palette);
        }
        if (!random.isEmpty()) {
            double total = originalWeight + random.stream().mapToDouble(Weighted::weight).sum();
            if (!Double.isFinite(total))
                throw new IllegalArgumentException("Total block weight is too large.");
            var palette = content.paletteCopy();
            var indices = new ArrayList<Integer>();
            for (var item : random) {
                var encoded = NbtUtils.writeBlockState(item.state());
                int i = palette.indexOf(encoded);
                if (i < 0) {
                    i = palette.size();
                    palette.add(encoded);
                }
                indices.add(i);
            }
            var changes = new HashMap<BlockPos, CompoundTag>();
            for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
                if (retained != null && retained.get(i)) continue;
                var p = position(i, size);
                if (content.blockName(p).equals("minecraft:air")) continue;
                var seedPos =
                        switch (Math.floorMod(generatedTurns, 4)) {
                            case 1 -> new BlockPos(p.getZ(), p.getY(), size.getX() - 1 - p.getX());
                            case 2 ->
                                    new BlockPos(
                                            size.getX() - 1 - p.getX(),
                                            p.getY(),
                                            size.getZ() - 1 - p.getZ());
                            case 3 -> new BlockPos(size.getZ() - 1 - p.getZ(), p.getY(), p.getX());
                            default -> p;
                        };
                long hash = mix(seed ^ seedPos.asLong());
                double pick = (hash >>> 11) * 0x1.0p-53 * total;
                if (pick < originalWeight) continue;
                pick -= originalWeight;
                int chosen = 0;
                while (chosen < random.size() - 1 && (pick -= random.get(chosen).weight()) >= 0)
                    chosen++;
                var block = matchingEntry(content, p, random.get(chosen).state());
                block.put("pos", StructureSegments.ints(p));
                block.putInt("state", indices.get(chosen));
                changes.put(p, random.get(chosen).state().isAir() ? null : block);
            }
            content = replace(content, changes, palette);
        }
        return deformation.identity()
                ? new Result(content, mask)
                : resample(content, mask, size, deformation, continuous, allowConfiguredLoss);
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static CompoundTag matchingEntry(
            SchematicDocument source, BlockPos p, BlockState state) {
        var old = source.blockEntryCopy(p);
        return old != null
                        && source.blockName(p)
                                .equals(
                                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                                .getKey(state.getBlock())
                                                .toString())
                ? old
                : new CompoundTag();
    }

    private static SchematicDocument replace(
            SchematicDocument source, Map<BlockPos, CompoundTag> changes, ListTag palette) {
        var replaced = new HashSet<BlockPos>();
        for (var e : changes.entrySet())
            if (!source.blockName(e.getKey())
                    .equals(
                            e.getValue() == null
                                    ? "minecraft:air"
                                    : palette.getCompound(e.getValue().getInt("state"))
                                            .getString("Name"))) replaced.add(e.getKey());
        var config = source.configCopy();
        if (config != null && !replaced.isEmpty()) {
            var kept = new JsonArray();
            for (var patch : SchematicPatches.read(config)) {
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                if (replaced.contains(owner)) continue;
                for (var target : com.eynny.npships.authoring.patches.PatchDevices.targets(patch))
                    if (replaced.contains(target))
                        com.eynny.npships.authoring.patches.PatchDevices.removeTarget(
                                patch, owner, target);
                kept.add(patch);
            }
            if (config.has("patches")) {
                SchematicPatches.set(
                        config, kept.asList().stream().map(JsonElement::getAsJsonObject).toList());
            }
            var loot = LootAssignments.read(config);
            config.remove(LootAssignments.KEY);
            for (var e : loot)
                if (!replaced.contains(e.pos()))
                    config = LootAssignments.assign(config, e.pos(), e.table(), e.rolls());
        }
        SchematicBlocks.embedChanges(source, changes, config);
        return source.withBlocks(changes, palette, config);
    }

    public static Result resample(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation) {
        return resample(source, sourceMask, target, deformation, false);
    }

    public static Result resample(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation,
            boolean continuous) {
        return resample(source, sourceMask, target, deformation, continuous, false);
    }

    public static Result resample(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation,
            boolean continuous,
            boolean allowConfiguredLoss) {
        var sample = deformation.inverseSampler(target);
        var from = source.size();
        return resampleMapped(
                source,
                sourceMask,
                target,
                deformation,
                point -> {
                    var p = sample.apply(point);
                    return p == null
                            ? null
                            : new Vec3(
                                    p.x * from.getX() / target.getX(),
                                    p.y * from.getY() / target.getY(),
                                    p.z * from.getZ() / target.getZ());
                },
                point -> project(point, from, target, deformation),
                continuous,
                continuous,
                allowConfiguredLoss);
    }

    static Result resampleMapped(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation,
            java.util.function.Function<Vec3, Vec3> inverse,
            java.util.function.Function<Vec3, Vec3> forward) {
        return resampleMapped(
                source, sourceMask, target, deformation, inverse, forward, false, false);
    }

    static Result resampleMapped(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation,
            java.util.function.Function<Vec3, Vec3> inverse,
            java.util.function.Function<Vec3, Vec3> forward,
            boolean continuous,
            boolean allowLoss) {
        return resampleMapped(
                source,
                sourceMask,
                target,
                deformation,
                inverse,
                forward,
                continuous,
                allowLoss,
                false);
    }

    static Result resampleMapped(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            ShapeDeformation deformation,
            java.util.function.Function<Vec3, Vec3> inverse,
            java.util.function.Function<Vec3, Vec3> forward,
            boolean continuous,
            boolean allowLoss,
            boolean allowConfiguredLoss) {
        var from = source.size();
        if (from.equals(target) && deformation.identity())
            return new Result(source, (BitSet) sourceMask.clone());
        if (com.eynny.npships.authoring.document.SchematicExtensions.present(source))
            com.eynny.npships.authoring.document.SchematicExtensions.refuse(
                    source,
                    "nonlinear/resampled area geometry without an exact provider reference map");
        if (source.hasConfig() && !StructureSegments.read(source.configCopy()).isEmpty())
            throw new IllegalArgumentException(
                    "An area containing random segments must be transformed with its variants. Use whole-schematic rotation or edit the segment separately.");
        var mask = new BitSet();
        var blocks = new ListTag();
        var palette = source.paletteCopy();
        var protectedBlocks = new HashSet<BlockPos>();
        for (var p : source.blocks().keySet())
            if (source.hasBlockEntityData(p)) protectedBlocks.add(p);
        var sourcePatches = SchematicPatches.read(SchematicPatches.authoringConfig(source));
        for (var patch : sourcePatches) {
            protectedBlocks.add(SchematicPatches.position(patch.getAsJsonArray("pos")));
            for (var end : com.eynny.npships.authoring.patches.PatchDevices.targets(patch))
                if (source.contains(end) && source.blocks().containsKey(end))
                    protectedBlocks.add(end);
        }
        for (var loot : LootAssignments.read(source.configCopy())) protectedBlocks.add(loot.pos());
        var mapping = new HashMap<BlockPos, BlockPos>();
        var best = new HashMap<BlockPos, Double>();
        for (int y = 0; y < target.getY(); y++)
            for (int z = 0; z < target.getZ(); z++)
                for (int x = 0; x < target.getX(); x++) {
                    var p = new BlockPos(x, y, z);
                    var sampled = inverse.apply(Vec3.atCenterOf(p));
                    if (sampled == null) continue;
                    var q = BlockPos.containing(sampled);
                    if (!source.contains(q) || !sourceMask.get(index(q, from))) continue;
                    mask.set(index(p, target));
                    var entry = source.blockEntryCopy(q);
                    if (entry != null && (!protectedBlocks.contains(q) || continuous)) {
                        if (protectedBlocks.contains(q)) entry.remove("nbt");
                        blocks.add(at(entry, p));
                    }
                    if (protectedBlocks.contains(q)) {
                        var projected = forward.apply(Vec3.atCenterOf(q));
                        double d = projected.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < best.getOrDefault(q, Double.MAX_VALUE)) {
                            best.put(q, d);
                            mapping.put(q, p);
                        }
                    }
                }
        if (allowLoss) {
            // Keep one instance of each device. Forward placement recovers cells missed by
            // inverse sampling; collisions search only the immediate neighborhood, never teleport.
            var reserved = new BitSet();
            for (var p : protectedBlocks.stream().sorted().toList()) {
                if (!source.contains(p) || !sourceMask.get(index(p, from))) {
                    mapping.remove(p);
                    continue;
                }
                var projected = forward.apply(Vec3.atCenterOf(p));
                var preferred = mapping.getOrDefault(p, BlockPos.containing(projected));
                BlockPos chosen = null;
                double distance = Double.MAX_VALUE;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        for (int dx = -1; dx <= 1; dx++) {
                            var q = preferred.offset(dx, dy, dz);
                            if (!inside(q, target) || reserved.get(index(q, target))) continue;
                            double d = Vec3.atCenterOf(q).distanceToSqr(projected);
                            if (d < distance) {
                                chosen = q;
                                distance = d;
                            }
                        }
                if (chosen == null) mapping.remove(p);
                else {
                    mapping.put(p, chosen);
                    reserved.set(index(chosen, target));
                }
            }
            for (int i = blocks.size() - 1; i >= 0; i--) {
                var pos = blocks.getCompound(i).getList("pos", Tag.TAG_INT);
                if (reserved.get(
                        index(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), target)))
                    blocks.remove(i);
            }
        }
        for (var p : protectedBlocks)
            if (source.contains(p) && sourceMask.get(index(p, from))) {
                var next = mapping.get(p);
                if (next == null) {
                    if (allowLoss && allowConfiguredLoss) continue;
                    throw configuredLoss(
                            source, p, "no destination in the supported sampling neighborhood");
                }
                var entry = source.blockEntryCopy(p);
                if (entry != null) {
                    blocks.add(at(entry, next));
                    mask.set(index(next, target));
                }
            }
        if ((continuous || allowLoss) && (!deformation.identity() || !from.equals(target))) {
            var occupied = new BitSet();
            var airEntries = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
            airEntries.defaultReturnValue(-1);
            for (int i = 0; i < blocks.size(); i++) {
                var entry = blocks.getCompound(i);
                var pos = entry.getList("pos", Tag.TAG_INT);
                int cell = index(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), target);
                var name = palette.getCompound(entry.getInt("state")).getString("Name");
                if (name.equals("minecraft:air")
                        || name.equals("minecraft:cave_air")
                        || name.equals("minecraft:void_air")) airEntries.put(cell, i);
                else occupied.set(cell);
            }
            java.util.function.BiConsumer<Integer, BlockPos> place =
                    (cell, owner) -> {
                        if (occupied.get(cell)) return;
                        int airEntry = airEntries.remove(cell.intValue());
                        if (airEntry < 0 && blocks.size() >= SchematicDocument.MAX_BLOCKS)
                            throw new IllegalArgumentException(
                                    "Transformed area exceeds the block limit.");
                        occupied.set(cell);
                        mask.set(cell);
                        var copy = source.blockEntryCopy(owner);
                        // Extra geometry must not duplicate inventories, commands or device data.
                        if (protectedBlocks.contains(owner)) copy.remove("nbt");
                        var entry = at(copy, position(cell, target));
                        if (airEntry >= 0) blocks.set(airEntry, entry);
                        else blocks.add(entry);
                    };
            if (allowLoss)
                for (var p : source.blocks().keySet().stream().sorted().toList()) {
                    if (protectedBlocks.contains(p) || !sourceMask.get(index(p, from))) continue;
                    var name = source.blockName(p);
                    if (name.equals("minecraft:air")
                            || name.equals("minecraft:cave_air")
                            || name.equals("minecraft:void_air")) continue;
                    var q = BlockPos.containing(forward.apply(Vec3.atCenterOf(p)));
                    if (inside(q, target)) place.accept(index(q, target), p);
                }
            if (continuous)
                ContinuousSurface.raster(
                        source, sourceMask, target, protectedBlocks, forward, place);
        }
        if (blocks.size() > SchematicDocument.MAX_BLOCKS)
            throw new IllegalArgumentException("Transformed area exceeds the block limit.");
        // Check the finished sampling output, not just reservation success. Surface/filler
        // placement must not overwrite or duplicate a configured object's selected destination.
        if (!allowConfiguredLoss)
            verifyConfigured(source, sourceMask, target, protectedBlocks, mapping, blocks);
        rotatePalette(palette, deformation);
        var tag = source.metadataCopy();
        tag.put("size", StructureSegments.ints(target));
        tag.put("blocks", blocks);
        tag.put("palette", palette);
        tag.remove("palettes");
        var entities = new ListTag();
        boolean rotateGlue = exactGlueRotation(deformation, forward);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.remap(
                    tag,
                    source,
                    mapping,
                    rotateGlue
                            ? p -> {
                                var v =
                                        forward.apply(Vec3.atLowerCornerOf(p))
                                                .subtract(forward.apply(Vec3.ZERO));
                                return new BlockPos(
                                        (int) Math.round(v.x),
                                        (int) Math.round(v.y),
                                        (int) Math.round(v.z));
                            }
                            : null);
        for (var value : source.entitiesCopy()) {
            var e = ((CompoundTag) value).copy();
            var p = e.getList("pos", Tag.TAG_DOUBLE);
            var originalPos = new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2));
            var mappedPosition = forward.apply(originalPos);
            var data = e.getCompound("nbt");
            var result =
                    rotateGlue && SchematicGlue.supported(data.getString("id"))
                            ? SchematicGlue.transformVolume(data, originalPos, forward)
                            : mappedPosition;
            if (rotateGlue) result = rotateItemFrame(e, forward, result);
            var painting = rotateGlue ? rotatePainting(e, originalPos, forward) : null;
            if (painting != null) result = painting.position();
            if (result.x < 0
                    || result.y < 0
                    || result.z < 0
                    || result.x >= target.getX()
                    || result.y >= target.getY()
                    || result.z >= target.getZ())
                if (allowLoss) continue;
                else
                    throw new IllegalArgumentException(
                            "Transformation would move an entity outside the area. Enlarge the area or reduce deformation.");
            var pos = new ListTag();
            pos.add(DoubleTag.valueOf(result.x));
            pos.add(DoubleTag.valueOf(result.y));
            pos.add(DoubleTag.valueOf(result.z));
            e.put("pos", pos);
            e.put(
                    "blockPos",
                    StructureSegments.ints(
                            painting == null ? BlockPos.containing(result) : painting.anchor()));
            data.put(
                    "Rotation",
                    transformedRotation(
                            data.getList("Rotation", Tag.TAG_FLOAT),
                            originalPos,
                            mappedPosition,
                            forward));
            EntitySettings.transformPendingRotation(
                    data,
                    pending -> transformedRotation(pending, originalPos, mappedPosition, forward));
            entities.add(e);
        }
        tag.put("entities", entities);
        var config = source.hasConfig() ? source.configCopy() : new JsonObject();
        var patchList = new JsonArray();
        for (var patch : sourcePatches) {
            var old = SchematicPatches.position(patch.getAsJsonArray("pos"));
            var next = mapping.get(old);
            if (next == null) continue;
            if (allowLoss)
                for (var end : com.eynny.npships.authoring.patches.PatchDevices.targets(patch))
                    if (protectedBlocks.contains(end) && !mapping.containsKey(end))
                        com.eynny.npships.authoring.patches.PatchDevices.removeTarget(
                                patch, old, end);
            if (!old.equals(next)) PatchInstances.stabilize(patch, false);
            remap(patch, old, next, mapping);
            patch.add("pos", SchematicPatches.vector(next));
            patchList.add(patch);
        }
        SchematicPatches.set(
                config, patchList.asList().stream().map(JsonElement::getAsJsonObject).toList());
        var loot = LootAssignments.read(config);
        config.remove(LootAssignments.KEY);
        for (var e : loot) {
            var to = mapping.get(e.pos());
            if (to != null) config = LootAssignments.assign(config, to, e.table(), e.rolls());
        }
        for (String key : List.of("segments", "crew"))
            if (config.has(key))
                for (var e : config.getAsJsonArray(key)) {
                    var v = e.getAsJsonObject();
                    var o = SchematicPatches.position(v.getAsJsonArray("origin"));
                    var s = SchematicPatches.position(v.getAsJsonArray("size"));
                    double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE},
                            hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                    for (int i = 0; i < 8; i++) {
                        var p =
                                forward.apply(
                                        new Vec3(
                                                o.getX() + ((i & 1) == 0 ? 0 : s.getX()),
                                                o.getY() + ((i & 2) == 0 ? 0 : s.getY()),
                                                o.getZ() + ((i & 4) == 0 ? 0 : s.getZ())));
                        double[] a = {p.x, p.y, p.z};
                        for (int k = 0; k < 3; k++) {
                            lo[k] = Math.min(lo[k], a[k]);
                            hi[k] = Math.max(hi[k], a[k]);
                        }
                    }
                    var start =
                            new BlockPos(
                                    Math.max(0, (int) Math.floor(lo[0])),
                                    Math.max(0, (int) Math.floor(lo[1])),
                                    Math.max(0, (int) Math.floor(lo[2])));
                    var end =
                            new BlockPos(
                                    Math.min(target.getX(), (int) Math.ceil(hi[0])),
                                    Math.min(target.getY(), (int) Math.ceil(hi[1])),
                                    Math.min(target.getZ(), (int) Math.ceil(hi[2])));
                    v.add("origin", SchematicPatches.vector(start));
                    v.add(
                            "size",
                            SchematicPatches.vector(
                                    new BlockPos(
                                            Math.max(1, end.getX() - start.getX()),
                                            Math.max(1, end.getY() - start.getY()),
                                            Math.max(1, end.getZ() - start.getZ()))));
                }
        config = EntityPatches.prune(tag, config);
        SchematicPatches.embed(tag, config);
        var document = new SchematicDocument(tag, config);
        StructureCrew.validateBounds(document, config);
        return new Result(document, mask);
    }

    private static ListTag transformedRotation(
            ListTag old,
            Vec3 originalPosition,
            Vec3 mappedPosition,
            java.util.function.Function<Vec3, Vec3> forward) {
        double yaw = Math.toRadians(old.getFloat(0)), pitch = Math.toRadians(old.getFloat(1));
        var look =
                new Vec3(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                        Math.cos(yaw) * Math.cos(pitch));
        var direction =
                forward.apply(originalPosition.add(look.scale(.001)))
                        .subtract(mappedPosition)
                        .normalize();
        var result = new ListTag();
        result.add(FloatTag.valueOf((float) Math.toDegrees(Math.atan2(-direction.x, direction.z))));
        result.add(
                FloatTag.valueOf(
                        (float)
                                Math.toDegrees(
                                        Math.atan2(
                                                -direction.y,
                                                Math.hypot(direction.x, direction.z)))));
        return result;
    }

    private static void verifyConfigured(
            SchematicDocument source,
            BitSet sourceMask,
            BlockPos target,
            Set<BlockPos> protectedBlocks,
            Map<BlockPos, BlockPos> mapping,
            ListTag blocks) {
        var owners = new HashMap<BlockPos, BlockPos>();
        for (var p : protectedBlocks) {
            if (!source.contains(p) || !sourceMask.get(index(p, source.size()))) continue;
            var next = mapping.get(p);
            if (next == null || !inside(next, target))
                throw configuredLoss(source, p, "missing or out-of-bounds destination: " + next);
            var other = owners.putIfAbsent(next, p);
            if (other != null)
                throw configuredLoss(
                        source, p, "destination " + next + " also belongs to " + other);
        }
        if (owners.isEmpty()) return;
        var found = new HashSet<BlockPos>();
        for (var value : blocks) {
            var entry = (CompoundTag) value;
            var pos = entry.getList("pos", Tag.TAG_INT);
            var next = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            var owner = owners.get(next);
            if (owner == null) continue;
            var expected = source.blockEntryCopy(owner);
            if (!found.add(next) || expected == null || !entry.equals(at(expected, next)))
                throw configuredLoss(
                        source, owner, "duplicate or overwritten destination: " + next);
        }
        for (var entry : owners.entrySet())
            if (!found.contains(entry.getKey()))
                throw configuredLoss(
                        source, entry.getValue(), "missing output at " + entry.getKey());
    }

    private static IllegalArgumentException configuredLoss(
            SchematicDocument source, BlockPos pos, String reason) {
        return ValidationProblem.invalid(
                "deformation_configured_loss",
                "Transformation cannot preserve configured block "
                        + source.blockName(pos)
                        + " at source-local "
                        + pos
                        + ": "
                        + reason
                        + ". Reduce the deformation or adjust the area; no result was accepted.",
                source.blockName(pos),
                pos.toShortString());
    }

    private static boolean inside(BlockPos p, BlockPos size) {
        return p.getX() >= 0
                && p.getY() >= 0
                && p.getZ() >= 0
                && p.getX() < size.getX()
                && p.getY() < size.getY()
                && p.getZ() < size.getZ();
    }

    private static CompoundTag at(CompoundTag entry, BlockPos p) {
        entry.put("pos", StructureSegments.ints(p));
        if (entry.contains("nbt")) {
            var n = entry.getCompound("nbt");
            n.putInt("x", p.getX());
            n.putInt("y", p.getY());
            n.putInt("z", p.getZ());
        }
        return entry;
    }

    private record PaintingPlacement(Vec3 position, BlockPos anchor) {}

    private static PaintingPlacement rotatePainting(
            CompoundTag entry, Vec3 original, java.util.function.Function<Vec3, Vec3> forward) {
        var data = entry.getCompound("nbt");
        if (!data.getString("id").equals("minecraft:painting")) return null;
        if (!(data.get("facing") instanceof net.minecraft.nbt.ByteTag)
                || data.getByte("facing") < 0
                || data.getByte("facing") > 3)
            throw new IllegalArgumentException("Painting requires a valid native wall direction");
        var saved = entry.getList("blockPos", Tag.TAG_INT);
        if (saved.size() != 3)
            throw new IllegalArgumentException("Painting requires its native attachment cell");
        var anchor = new BlockPos(saved.getInt(0), saved.getInt(1), saved.getInt(2));
        var center = Vec3.atCenterOf(anchor);
        var old = Direction.from2DDataValue(data.getByte("facing"));
        var mapped = forward.apply(center);
        var normal = forward.apply(center.relative(old, 1)).subtract(mapped);
        var up = forward.apply(center.add(0, 1, 0)).subtract(mapped);
        var facing =
                Direction.fromDelta(
                        (int) Math.round(normal.x),
                        (int) Math.round(normal.y),
                        (int) Math.round(normal.z));
        if (facing == null
                || !facing.getAxis().isHorizontal()
                || up.distanceToSqr(new Vec3(0, 1, 0)) > 1e-12
                || normal.distanceToSqr(Vec3.atLowerCornerOf(facing.getNormal())) > 1e-12)
            throw new IllegalArgumentException(
                    "This rotation cannot represent an upright native painting");
        // Even native variants offset their center by half a cell. Recover parity from the
        // canonical source center, never from arbitrary foreign fields or an absent registry.
        var offset = original.subtract(center).relative(old, .46875);
        var tangent = Vec3.atLowerCornerOf(old.getCounterClockWise().getNormal());
        double width = offset.dot(tangent), height = offset.y;
        if ((!nearHalf(width))
                || (!nearHalf(height))
                || offset.subtract(tangent.scale(width)).subtract(0, height, 0).lengthSqr() > 1e-12)
            throw new IllegalArgumentException(
                    "Painting center is not a supported native attachment");
        var target = BlockPos.containing(mapped);
        var position =
                Vec3.atCenterOf(target)
                        .relative(facing, -.46875)
                        .relative(facing.getCounterClockWise(), width)
                        .add(0, height, 0);
        data.putByte("facing", (byte) facing.get2DDataValue());
        data.putInt("TileX", target.getX());
        data.putInt("TileY", target.getY());
        data.putInt("TileZ", target.getZ());
        return new PaintingPlacement(position, target);
    }

    private static boolean nearHalf(double value) {
        return Math.abs(value) < 1e-9 || Math.abs(value - .5) < 1e-9;
    }

    private static Vec3 rotateItemFrame(
            CompoundTag entry, java.util.function.Function<Vec3, Vec3> forward, Vec3 fallback) {
        var data = entry.getCompound("nbt");
        var id = data.getString("id");
        if ((!id.equals("minecraft:item_frame") && !id.equals("minecraft:glow_item_frame"))
                || !data.contains("Facing", Tag.TAG_BYTE)
                || data.getByte("Facing") < 0
                || data.getByte("Facing") > 5) return fallback;
        var anchor = entry.getList("blockPos", Tag.TAG_INT);
        if (anchor.size() != 3) return fallback;
        // Schematic blockPos owns the attachment cell (PreviewEntityData also resolves TileX/Y/Z
        // from it). Embedded Tile coordinates can still belong to the original captured world.
        var center =
                Vec3.atCenterOf(new BlockPos(anchor.getInt(0), anchor.getInt(1), anchor.getInt(2)));
        var mappedCenter = forward.apply(center);
        var normal =
                Vec3.atLowerCornerOf(Direction.from3DDataValue(data.getByte("Facing")).getNormal());
        var mappedNormal = forward.apply(center.add(normal)).subtract(mappedCenter);
        var direction =
                Direction.fromDelta(
                        (int) Math.round(mappedNormal.x),
                        (int) Math.round(mappedNormal.y),
                        (int) Math.round(mappedNormal.z));
        data.putByte("Facing", (byte) direction.get3DDataValue());
        // ItemFrame.calculateBoundingBox: all six faces use cell center - normal * 15/32.
        // Normalize editor-created anchors too; a bottom-center placement is not a frame center.
        return Vec3.atCenterOf(BlockPos.containing(mappedCenter)).relative(direction, -0.46875);
    }

    private static boolean exactGlueRotation(
            ShapeDeformation deformation, java.util.function.Function<Vec3, Vec3> forward) {
        var parameters = deformation.parameters();
        boolean rotated = false;
        for (int i = 0; i < 3; i++) {
            if (parameters[i] % 90 != 0) return false;
            rotated |= parameters[i] % 360 != 0;
            parameters[i] = 0;
        }
        if (!rotated || !deformation.parameters(parameters).identity()) return false;
        // The legacy fitted sampler can also scale. Admit only a unit axis permutation, so
        // this correction cannot approximate free rotations, Scale or nonlinear deformations.
        var origin = forward.apply(Vec3.ZERO);
        int axes = 0;
        for (var unit : List.of(new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1))) {
            var v = forward.apply(unit).subtract(origin);
            int axis = 0;
            for (int i = 0; i < 3; i++) {
                double component = i == 0 ? v.x : i == 1 ? v.y : v.z;
                if (Math.abs(Math.abs(component) - 1) < 1e-8) axis |= 1 << i;
                else if (Math.abs(component) > 1e-8) return false;
            }
            if (Integer.bitCount(axis) != 1 || (axes & axis) != 0) return false;
            axes |= axis;
        }
        return axes == 7;
    }

    /**
     * Use native registered transforms for exact XYZ quarters; retain legacy free-angle Y sampling.
     */
    private static void rotatePalette(ListTag palette, ShapeDeformation deformation) {
        var transforms = paletteTransforms(deformation.parameters());
        if (transforms.isEmpty()) return;
        for (int i = 0; i < palette.size(); i++) {
            var state =
                    NbtUtils.readBlockState(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                            palette.getCompound(i));
            for (var transform : transforms)
                // Keep the old Y contract, including native/modded rotate overrides. X/Z use
                // Create's registered transformers, TransformableBlock and supported native cases.
                state =
                        transform.rotationAxis == Direction.Axis.Y
                                ? state.rotate(transform.rotation)
                                : transform.apply(state);
            palette.set(i, NbtUtils.writeBlockState(state));
        }
    }

    private static List<StructureTransform> paletteTransforms(double[] angles) {
        var y = paletteRotation(angles[1]);
        if (angles[0] % 90 != 0 || angles[1] % 90 != 0 || angles[2] % 90 != 0)
            return y == Rotation.NONE ? List.of() : List.of(paletteTransform(Direction.Axis.Y, y));
        var up = Direction.UP;
        var north = Direction.NORTH;
        // JOML rotationXYZ is Rx * Ry * Rz: vectors see Z, then Y, then X.
        // Obtain the final frame through native direction transforms, without another matrix.
        for (int axis = 2; axis >= 0; axis--) {
            var rotation = paletteRotation(angles[axis]);
            if (rotation == Rotation.NONE) continue;
            var step = paletteTransform(Direction.Axis.values()[axis], rotation);
            up = step.rotateFacing(up);
            north = step.rotateFacing(north);
        }
        // Equivalent yaw then one tilt avoids unsupported intermediate states (for example,
        // sideways slabs) when the final quarter-rotated state is actually representable.
        var tiltRotation =
                switch (up) {
                    case UP -> Rotation.NONE;
                    case DOWN -> Rotation.CLOCKWISE_180;
                    case NORTH, EAST -> Rotation.CLOCKWISE_90;
                    case SOUTH, WEST -> Rotation.COUNTERCLOCKWISE_90;
                };
        var tilt =
                tiltRotation == Rotation.NONE
                        ? null
                        : paletteTransform(
                                up.getAxis() == Direction.Axis.X
                                        ? Direction.Axis.Z
                                        : Direction.Axis.X,
                                tiltRotation);
        for (var yaw : Rotation.values()) {
            var facing = yaw.rotate(Direction.NORTH);
            if ((tilt == null ? facing : tilt.rotateFacing(facing)) != north) continue;
            var result = new ArrayList<StructureTransform>(2);
            if (yaw != Rotation.NONE) result.add(paletteTransform(Direction.Axis.Y, yaw));
            if (tilt != null) result.add(tilt);
            return result;
        }
        throw new IllegalStateException("Quarter rotation has no native orientation frame");
    }

    private static Rotation paletteRotation(double degrees) {
        return switch (Math.floorMod((int) Math.round(-degrees / 90), 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static StructureTransform paletteTransform(Direction.Axis axis, Rotation rotation) {
        return new StructureTransform(BlockPos.ZERO, axis, rotation, Mirror.NONE);
    }

    private static Vec3 project(
            Vec3 point, BlockPos from, BlockPos to, ShapeDeformation deformation) {
        return deformation.forward(
                new Vec3(
                        point.x * to.getX() / from.getX(),
                        point.y * to.getY() / from.getY(),
                        point.z * to.getZ() / from.getZ()),
                to);
    }

    private static void remap(
            JsonElement value, BlockPos owner, BlockPos next, Map<BlockPos, BlockPos> mapping) {
        if (com.eynny.npships.authoring.patches.ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var obj = value.getAsJsonObject();
            if (obj.has("offset")) {
                var old = owner.offset(SchematicPatches.position(obj.getAsJsonArray("offset")));
                obj.add(
                        "offset",
                        SchematicPatches.vector(mapping.getOrDefault(old, old).subtract(next)));
            }
            for (var e : obj.entrySet())
                if (!e.getKey().equals("offset")) remap(e.getValue(), owner, next, mapping);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) remap(e, owner, next, mapping);
    }
}
