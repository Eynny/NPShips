package com.eynny.npships.authoring;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.authoring.patches.EntityIdentity;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.patches.PatchInstances;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed, non-overlapping segment volumes. Templates live in the same NBT as the base ship. */
public final class StructureSegments {
    public static SchematicDocument rename(SchematicDocument source, String oldId, String newId) {
        var config = source.configCopy();
        boolean found = false;
        if (config != null && config.has("segments"))
            for (var entry : config.getAsJsonArray("segments")) {
                var segment = entry.getAsJsonObject();
                if (segment.get("id").getAsString().equals(oldId)) {
                    segment.addProperty("id", newId);
                    found = true;
                    break;
                }
            }
        if (!found) throw new IllegalArgumentException("Segment not found: " + oldId);
        var tag = source.nbtCopy();
        validate(tag, read(config));
        return new SchematicDocument(tag, config);
    }

    public static final String VARIANTS = "npships:variants";

    public record Variant(String id, String name, int weight, List<LootAssignments.Entry> loot) {}

    public record Segment(
            String id,
            BlockPos origin,
            BlockPos size,
            boolean enabled,
            List<Variant> pool,
            java.util.UUID occurrence) {
        public Segment(
                String id, BlockPos origin, BlockPos size, boolean enabled, List<Variant> pool) {
            this(id, origin, size, enabled, pool, null);
        }

        public boolean contains(BlockPos p) {
            return contains(p.getX(), p.getY(), p.getZ());
        }

        public boolean contains(double x, double y, double z) {
            return x >= origin.getX()
                    && y >= origin.getY()
                    && z >= origin.getZ()
                    && x < (long) origin.getX() + size.getX()
                    && y < (long) origin.getY() + size.getY()
                    && z < (long) origin.getZ() + size.getZ();
        }
    }

    public record Plan(Map<String, String> choices) {
        public Plan {
            choices = Map.copyOf(choices);
        }
    }

    public record Composition(CompoundTag nbt, List<LootAssignments.Entry> loot) {}

    private StructureSegments() {}

    public static List<Segment> read(JsonObject json) {
        if (json == null || !json.has("segments")) return List.of();
        if (!json.get("segments").isJsonArray())
            throw new IllegalArgumentException("segments must be an array.");
        var result = new ArrayList<Segment>();
        var names = new HashSet<String>();
        var occurrences = new HashSet<java.util.UUID>();
        for (var value : json.getAsJsonArray("segments")) {
            var s = value.getAsJsonObject();
            String id = name(s, "id");
            if (!names.add(id)) throw new IllegalArgumentException("Duplicate segment: " + id);
            BlockPos origin = vector(s, "origin", false), size = vector(s, "size", true);
            boolean enabled = !s.has("enabled") || s.get("enabled").getAsBoolean();
            var pool = new ArrayList<Variant>();
            var variants = new HashSet<String>();
            if (s.has("pool")) {
                var entries = s.getAsJsonArray("pool");
                if (entries.isEmpty() || entries.size() > 64)
                    throw new IllegalArgumentException(
                            "A pool must have 1..64 variants; remove pool to use base.");
                for (var item : entries) {
                    var v = item.getAsJsonObject();
                    String variant = name(v, "variant");
                    if (!variants.add(variant))
                        throw new IllegalArgumentException("Duplicate variant in pool: " + variant);
                    int weight =
                            v.has("weight")
                                    ? com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                            v.get("weight"), "weight")
                                    : 1;
                    if (weight < 1 || weight > 1_000_000)
                        throw new IllegalArgumentException("Variant weight must be 1..1000000.");
                    pool.add(
                            new Variant(
                                    variant,
                                    v.has("name") ? name(v, "name") : variant,
                                    weight,
                                    LootAssignments.read(v)));
                }
            }
            java.util.UUID occurrence = null;
            if (s.has(EntityPatches.OCCURRENCE)) {
                String text = s.get(EntityPatches.OCCURRENCE).getAsString();
                occurrence = java.util.UUID.fromString(text);
                if (!occurrence.toString().equals(text) || !occurrences.add(occurrence))
                    throw new IllegalArgumentException(
                            "Invalid/duplicate entity segment occurrence");
            }
            var segment = new Segment(id, origin, size, enabled, List.copyOf(pool), occurrence);
            for (var other : result)
                if (overlap(segment, other))
                    throw new IllegalArgumentException(
                            "Segments overlap: " + id + " / " + other.id());
            result.add(segment);
            if (result.size() > 64)
                throw new IllegalArgumentException("Maximum 64 segments per schematic.");
        }
        return List.copyOf(result);
    }

    private static String name(JsonObject json, String key) {
        String value = json.get(key).getAsString();
        if (value.isBlank()
                || value.length() > 80
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "Invalid " + key + ": use 1..80 printable characters.");
        return value;
    }

    private static BlockPos vector(JsonObject json, String key, boolean positive) {
        var a = json.getAsJsonArray(key);
        if (a.size() != 3) throw new IllegalArgumentException(key + " needs three integers.");
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            v[i] = com.eynny.npships.authoring.validation.JsonNumbers.integer(a.get(i), key);
            if (v[i] < (positive ? 1 : 0) || v[i] > SchematicDocument.MAX_SIDE)
                throw new IllegalArgumentException("Invalid " + key + " coordinate.");
        }
        return new BlockPos(v[0], v[1], v[2]);
    }

    private static boolean overlap(Segment a, Segment b) {
        return a.origin.getX() < b.origin.getX() + b.size.getX()
                && b.origin.getX() < a.origin.getX() + a.size.getX()
                && a.origin.getY() < b.origin.getY() + b.size.getY()
                && b.origin.getY() < a.origin.getY() + a.size.getY()
                && a.origin.getZ() < b.origin.getZ() + b.size.getZ()
                && b.origin.getZ() < a.origin.getZ() + a.size.getZ();
    }

    public static Plan choose(List<Segment> segments, RandomSource random) {
        var choices = new LinkedHashMap<String, String>();
        for (var segment : segments) {
            if (!segment.enabled() || segment.pool().isEmpty()) continue;
            int total = segment.pool().stream().mapToInt(Variant::weight).sum();
            int roll = random.nextInt(total);
            for (var variant : segment.pool()) {
                roll -= variant.weight();
                if (roll < 0) {
                    choices.put(segment.id(), variant.id());
                    break;
                }
            }
        }
        return new Plan(choices);
    }

    /** A variant is validated where it will be placed, including links to the unchanged base. */
    public static SchematicDocument variantValidationDocument(
            SchematicDocument parent, Segment segment, String variant, SchematicDocument edited) {
        var tag = parent.nbtCopy();
        SchematicPatches.embed(tag, parent.configCopy());
        var part = AssemblyPoints.segmentTemplate(edited.nbtCopy());
        SchematicPatches.embed(part, edited.configCopy());
        var variants = tag.getCompound(VARIANTS);
        variants.put(variant, part);
        tag.put(VARIANTS, variants);
        var composed =
                compose(tag, List.of(segment), List.of(), new Plan(Map.of(segment.id(), variant)));
        return new SchematicDocument(composed.nbt(), null);
    }

    /** Each occurrence is checked independently; no random plan or exponential combination scan. */
    public static void visitValidationVariants(
            SchematicDocument parent, java.util.function.Consumer<CompoundTag> validator) {
        visitValidationVariants(parent, read(parent.configCopy()), validator);
    }

    /** Runtime configuration stores parsed segments separately from patch-authoring JSON. */
    public static void visitValidationVariants(
            SchematicDocument parent,
            List<Segment> segments,
            java.util.function.Consumer<CompoundTag> validator) {
        var tag = parent.nbtCopy();
        var variants = tag.getCompound(VARIANTS);
        var used = new HashSet<String>();
        for (var segment : segments) {
            for (var variant : segment.pool()) {
                var part = AssemblyPoints.segmentDocument(variants.getCompound(variant.id()), null);
                validator.accept(
                        variantValidationDocument(parent, segment, variant.id(), part).nbtCopy());
                used.add(variant.id());
            }
        }
        // Orphaned templates have no placement context and remain standalone, strictly checked.
        for (var id : variants.getAllKeys())
            if (!used.contains(id))
                validator.accept(AssemblyPoints.segmentTemplate(variants.getCompound(id)));
    }

    public static void validate(CompoundTag base, List<Segment> segments) {
        if (segments.isEmpty()) return;
        var document = new SchematicDocument(base, null);
        if (!base.contains("palette", Tag.TAG_LIST))
            throw new IllegalArgumentException(
                    "Segments currently require a single block palette.");
        var variants = base.getCompound(VARIANTS);
        var checkedVariants = new java.util.HashMap<String, SchematicDocument>();
        for (var segment : segments) {
            if (!document.contains(segment.origin())
                    || !document.contains(
                            segment.origin().offset(segment.size()).offset(-1, -1, -1)))
                throw new IllegalArgumentException("Segment outside ship: " + segment.id());
            for (var variant : segment.pool()) {
                if (!variants.contains(variant.id(), Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException(
                            "Missing embedded variant: " + variant.name());
                var template = variants.getCompound(variant.id());
                if (segment.occurrence() == null)
                    for (var entity : template.getList("entities", Tag.TAG_COMPOUND))
                        if (EntityIdentity.read(((CompoundTag) entity).getCompound("nbt")) != null)
                            throw new IllegalArgumentException(
                                    "Patched variant requires stable entity_occurrence UUID for"
                                            + " segment "
                                            + segment.id());
                if (segment.occurrence() == null
                        && (com.eynny.npships.authoring.patches.SchematicPatches.identified(
                                        template)
                                || template.contains(SchematicExtensions.KEY)))
                    throw new IllegalArgumentException(
                            "Patched variant requires stable entity_occurrence UUID for segment "
                                    + segment.id());
                var part =
                        checkedVariants.computeIfAbsent(
                                variant.id(),
                                ignored -> AssemblyPoints.segmentDocument(template, null));
                if (!part.size().equals(segment.size())
                        || !template.contains("palette", Tag.TAG_LIST))
                    throw new IllegalArgumentException(
                            "Variant dimensions/palette do not match segment: " + variant.name());
                for (var loot : variant.loot())
                    if (!part.blocks().containsKey(loot.pos()))
                        throw new IllegalArgumentException(
                                "Variant loot outside blocks: " + variant.name());
            }
        }
    }

    public static Composition compose(
            CompoundTag source,
            List<Segment> segments,
            List<LootAssignments.Entry> baseLoot,
            Plan plan) {
        validate(source, segments);
        if (plan.choices().isEmpty()) return new Composition(source.copy(), List.copyOf(baseLoot));
        // Runtime output discards stored variants; copy only the root fields it retains.
        var result = new CompoundTag();
        for (var key : source.getAllKeys())
            if (!VARIANTS.equals(key)) result.put(key, source.get(key).copy());
        var palette = result.getList("palette", Tag.TAG_COMPOUND);
        var indices = new LinkedHashMap<CompoundTag, Integer>();
        for (int i = 0; i < palette.size(); i++) indices.putIfAbsent(palette.getCompound(i), i);
        var blocks = new LinkedHashMap<BlockPos, CompoundTag>();
        for (var b : result.getList("blocks", Tag.TAG_COMPOUND))
            blocks.put(blockPos((CompoundTag) b), (CompoundTag) b);
        var entities = result.getList("entities", Tag.TAG_COMPOUND);
        var entityPatches = new ArrayList<>(EntityPatches.embedded(source));
        var loot = new ArrayList<>(baseLoot);
        var applied = new HashSet<String>();
        boolean extensionData = source.contains(SchematicExtensions.KEY);
        for (var segment : segments) {
            var chosen = plan.choices().get(segment.id());
            if (chosen == null) continue;
            var variant =
                    segment.pool().stream()
                            .filter(v -> v.id().equals(chosen))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Plan refers to missing variant: " + chosen));
            applied.add(segment.id());
            var part =
                    AssemblyPoints.segmentTemplate(
                            source.getCompound(VARIANTS).getCompound(chosen));
            extensionData |= part.contains(SchematicExtensions.KEY);
            if (segment.occurrence() != null)
                EntityPatches.composeOccurrence(part, segment.occurrence());
            entityPatches.addAll(EntityPatches.embedded(part));
            var partPalette = part.getList("palette", Tag.TAG_COMPOUND);
            int[] remap = new int[partPalette.size()];
            for (int i = 0; i < remap.length; i++) {
                var state = partPalette.getCompound(i);
                Integer index = indices.get(state);
                if (index == null) {
                    index = palette.size();
                    palette.add(state.copy());
                    indices.put(state.copy(), index);
                }
                remap[i] = index;
            }
            blocks.keySet().removeIf(segment::contains);
            for (var value : part.getList("blocks", Tag.TAG_COMPOUND)) {
                var b = ((CompoundTag) value).copy();
                var pos = blockPos(b).offset(segment.origin());
                b.put("pos", ints(pos));
                b.putInt("state", remap[b.getInt("state")]);
                if (b.contains("nbt", Tag.TAG_COMPOUND)) {
                    var data = b.getCompound("nbt");
                    data.putInt("x", pos.getX());
                    data.putInt("y", pos.getY());
                    data.putInt("z", pos.getZ());
                }
                String physicalKey = com.eynny.npships.authoring.io.SubLevelImport.GROUP;
                if (b.contains(physicalKey))
                    b.putString(
                            physicalKey,
                            java.util
                                    .UUID
                                    .nameUUIDFromBytes(
                                            (segment.id() + ":" + b.getString(physicalKey))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))
                                    .toString());
                blocks.put(pos, b);
            }
            var combined = new ListTag();
            for (var entity : entities)
                if (!entityInside((CompoundTag) entity, segment)) combined.add(entity.copy());
            for (var entity : part.getList("entities", Tag.TAG_COMPOUND))
                combined.add(translateEntity((CompoundTag) entity, segment.origin()));
            entities = combined;
            loot.removeIf(entry -> segment.contains(entry.pos()));
            for (var entry : variant.loot())
                loot.add(
                        new LootAssignments.Entry(
                                entry.pos().offset(segment.origin()),
                                entry.table(),
                                entry.rolls()));
        }
        if (!applied.equals(plan.choices().keySet()))
            throw new IllegalArgumentException("Plan refers to missing segment.");
        var all = new ListTag();
        blocks.values().forEach(all::add);
        result.put("blocks", all);
        result.put("entities", entities);
        if (!entityPatches.isEmpty() || result.contains(EntityPatches.EMBEDDED)) {
            var c = EntityPatches.embeddedConfig(source);
            EntityPatches.set(c, entityPatches);
            EntityPatches.prune(result, c);
        }
        result.remove(VARIANTS); // Runtime template needs only the selected geometry.
        new SchematicDocument(result, null);
        if (extensionData) result = authorComposition(source, result, segments, plan);
        return new Composition(result, List.copyOf(loot));
    }

    /**
     * Pure provider proposals follow the existing native occurrence and offset, never a guessed
     * graph.
     */
    static CompoundTag authorComposition(
            CompoundTag source, CompoundTag result, List<Segment> segments, Plan plan) {
        var candidate = new SchematicDocument(result, null);
        if (source.contains(SchematicExtensions.KEY)) {
            var original = new SchematicDocument(source, null);
            var operation =
                    SchematicExtensions.operation(
                            PatchAuthoring.Kind.SEGMENT,
                            p ->
                                    segments.stream()
                                                    .anyMatch(
                                                            s ->
                                                                    plan.choices()
                                                                                    .containsKey(
                                                                                            s.id())
                                                                            && s.contains(p))
                                            ? List.of()
                                            : List.of(p),
                            p ->
                                    segments.stream()
                                                    .anyMatch(
                                                            s ->
                                                                    plan.choices()
                                                                                    .containsKey(
                                                                                            s.id())
                                                                            && s.contains(
                                                                                    p.x, p.y, p.z))
                                            ? List.of()
                                            : List.of(p),
                            java.util.function.UnaryOperator.identity(),
                            java.util.Optional::of);
            candidate = SchematicExtensions.apply(original, candidate, operation);
        }
        for (var segment : segments) {
            var selected = plan.choices().get(segment.id());
            if (selected == null) continue;
            var part = source.getCompound(VARIANTS).getCompound(selected);
            if (!part.contains(SchematicExtensions.KEY)) continue;
            var original = AssemblyPoints.segmentDocument(part, null);
            if (segment.occurrence() == null)
                throw new IllegalArgumentException(
                        "Provider data requires stable entity_occurrence for segment "
                                + segment.id());
            var origin = segment.origin();
            var occurrence = segment.occurrence();
            var operation =
                    SchematicExtensions.operation(
                            PatchAuthoring.Kind.SEGMENT,
                            p -> original.contains(p) ? List.of(p.offset(origin)) : List.of(),
                            p ->
                                    com.eynny.npships.authoring.document.SchematicEdits.inside(
                                                    original.size(), p)
                                            ? List.of(
                                                    p.add(
                                                            origin.getX(),
                                                            origin.getY(),
                                                            origin.getZ()))
                                            : List.of(),
                            id -> PatchInstances.compose(id, occurrence),
                            id -> java.util.Optional.of(EntityIdentity.composed(occurrence, id)));
            candidate =
                    SchematicExtensions.apply(
                            original,
                            candidate,
                            new PatchAuthoring.Operation(
                                    PatchAuthoring.Kind.SEGMENT,
                                    operation.mapping(),
                                    BlockPos.ZERO,
                                    original.size(),
                                    occurrence));
        }
        return candidate.nbtCopy();
    }

    /** Crop a saved whole ship OR a separately saved part; missing blocks mean air. */
    public static CompoundTag crop(SchematicDocument source, BlockPos origin, BlockPos size) {
        var region = new Segment("crop", origin, size, false, List.of());
        if (!source.contains(origin) || !source.contains(origin.offset(size).offset(-1, -1, -1)))
            throw new IllegalArgumentException("Import region is outside the source schematic.");
        var result = source.nbtCopy();
        if (!result.contains("palette", Tag.TAG_LIST))
            throw new IllegalArgumentException("Segment import requires a single palette.");
        var blocks = new ListTag();
        for (var value : result.getList("blocks", Tag.TAG_COMPOUND)) {
            var b = (CompoundTag) value;
            var p = blockPos(b);
            if (!region.contains(p)) continue;
            b = b.copy();
            var local = p.subtract(origin);
            b.put("pos", ints(local));
            if (b.contains("nbt", Tag.TAG_COMPOUND)) {
                var nbt = b.getCompound("nbt");
                nbt.putInt("x", local.getX());
                nbt.putInt("y", local.getY());
                nbt.putInt("z", local.getZ());
            }
            blocks.add(b);
        }
        var entities = new ListTag();
        for (var value : result.getList("entities", Tag.TAG_COMPOUND))
            if (entityInside((CompoundTag) value, region))
                entities.add(
                        translateEntity(
                                (CompoundTag) value,
                                new BlockPos(-origin.getX(), -origin.getY(), -origin.getZ())));
        result.put("blocks", blocks);
        result.put("entities", entities);
        result.put("size", ints(size));
        result.remove(VARIANTS);
        result = AssemblyPoints.segmentTemplate(result);
        EntityPatches.prune(result, null);
        if (SchematicExtensions.present(source))
            return SchematicExtensions.apply(
                            source,
                            new SchematicDocument(result, null),
                            SchematicExtensions.clip(
                                    PatchAuthoring.Kind.CROP, origin, size, origin.multiply(-1)))
                    .nbtCopy();
        return result;
    }

    public static List<LootAssignments.Entry> cropLoot(
            JsonObject config, BlockPos origin, BlockPos size) {
        var region = new Segment("crop", origin, size, false, List.of());
        return LootAssignments.read(config).stream()
                .filter(e -> region.contains(e.pos()))
                .map(e -> new LootAssignments.Entry(e.pos().subtract(origin), e.table(), e.rolls()))
                .toList();
    }

    private static boolean entityInside(CompoundTag e, Segment region) {
        var p = e.getList("pos", Tag.TAG_DOUBLE);
        return p.size() == 3 && region.contains(p.getDouble(0), p.getDouble(1), p.getDouble(2));
    }

    public static CompoundTag translateEntity(CompoundTag entry, BlockPos delta) {
        var copy = entry.copy();
        var p = copy.getList("pos", Tag.TAG_DOUBLE);
        var bp = copy.getList("blockPos", Tag.TAG_INT);
        var translated = new ListTag();
        translated.add(DoubleTag.valueOf(p.getDouble(0) + delta.getX()));
        translated.add(DoubleTag.valueOf(p.getDouble(1) + delta.getY()));
        translated.add(DoubleTag.valueOf(p.getDouble(2) + delta.getZ()));
        copy.put("pos", translated);
        if (bp.size() == 3)
            copy.put(
                    "blockPos",
                    ints(new BlockPos(bp.getInt(0), bp.getInt(1), bp.getInt(2)).offset(delta)));
        return copy;
    }

    private static BlockPos blockPos(CompoundTag b) {
        var p = b.getList("pos", Tag.TAG_INT);
        return new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
    }

    public static ListTag ints(BlockPos pos) {
        var list = new ListTag();
        list.add(IntTag.valueOf(pos.getX()));
        list.add(IntTag.valueOf(pos.getY()));
        list.add(IntTag.valueOf(pos.getZ()));
        return list;
    }
}
