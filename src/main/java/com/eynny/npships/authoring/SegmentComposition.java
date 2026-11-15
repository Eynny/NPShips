package com.eynny.npships.authoring;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.StructureSegments.*;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Existing composition semantics with attempt-owned cursors over source/variant geometry. */
public final class SegmentComposition implements AutoCloseable {
    private enum Phase {
        LOOT_INPUT,
        BASE,
        VALIDATE_SEGMENT,
        VALIDATE_VARIANT,
        VALIDATE_ROOTS,
        VALIDATE_IDENTITIES,
        VARIANT_DOCUMENT,
        VALIDATE_LOOT,
        ROOT_FIELDS,
        ROOT_COPY,
        PALETTE_INDEX,
        BLOCK_INDEX,
        SELECT,
        PART_COPY,
        OCCURRENCE_ROOTS,
        OCCURRENCE_PATCHES,
        OCCURRENCE_COMMIT,
        OCCURRENCE_BLOCKS,
        PART_PATCHES,
        PALETTE_MERGE,
        REMOVE_BLOCKS,
        ADD_BLOCKS,
        KEEP_ENTITIES,
        ADD_ENTITIES,
        KEEP_LOOT,
        ADD_LOOT,
        OUTPUT_BLOCKS,
        PRUNE_ROOTS,
        PRUNE_PATCHES,
        PRUNE_COMMIT,
        RESULT_DOCUMENT,
        AUTHOR_BASE,
        AUTHOR_PARTS,
        AUTHOR_EXPORT,
        DONE
    }

    private CompoundTag source, output, part, shallow;
    private final List<Segment> segments;
    private final List<LootAssignments.Entry> baseLoot;
    private final Plan plan;
    private final PreparedTemplateResources resources;
    private Phase phase = Phase.LOOT_INPUT;
    private SchematicDocument.RuntimeRead document;
    private SchematicDocument.RuntimeCopy export;
    private PreparedTemplateResources.Copy<CompoundTag> copy;
    private SchematicDocument baseDocument, candidate, variantDocument;
    private Map<String, SchematicDocument> checked = new HashMap<>();
    private Map<CompoundTag, Integer> paletteIndices = new LinkedHashMap<>();
    private LinkedHashMap<BlockPos, CompoundTag> blocks = new LinkedHashMap<>();
    private Set<String> applied = new HashSet<>();
    private Map<UUID, UUID> remap = new HashMap<>();
    private Set<UUID> surviving = new HashSet<>();
    private List<JsonObject> entityPatches = new ArrayList<>(), partPatches, remappedPatches;
    private List<LootAssignments.Entry> loot = new ArrayList<>(), nextLoot;
    private ListTag palette, entities, combined, outputBlocks;
    private Iterator<String> fields;
    private Iterator<BlockPos> removeBlocks;
    private Iterator<CompoundTag> outputEntries;
    private Segment segment;
    private Variant variant;
    private int segmentIndex, variantIndex, index, sourceSchema;
    private int[] paletteRemap;
    private long progress, scratch;
    private boolean extensionData, closed, transferred;

    public SegmentComposition(
            CompoundTag source,
            List<Segment> segments,
            List<LootAssignments.Entry> baseLoot,
            Plan plan,
            PreparedTemplateResources resources) {
        this.source = Objects.requireNonNull(source);
        this.segments = Objects.requireNonNull(segments);
        this.baseLoot = Objects.requireNonNull(baseLoot);
        this.plan = Objects.requireNonNull(plan);
        this.resources = Objects.requireNonNull(resources);
    }

    private void reserve(long bytes) {
        long next = Math.addExact(scratch, bytes);
        resources.reserveCapacity(bytes);
        scratch = next;
    }

    private void startDocument(CompoundTag tag) {
        document = SchematicDocument.beginOwnedRuntime(tag, this::reserve);
    }

    private boolean documentStep(BooleanSupplier canContinue) {
        long before = document.progress();
        boolean done = document.step(canContinue);
        progress += document.progress() - before;
        return done;
    }

    private boolean copyStep(BooleanSupplier canContinue) {
        long before = copy.progress();
        boolean done = copy.step(canContinue);
        progress += copy.progress() - before;
        return done;
    }

    public boolean step(BooleanSupplier canContinue) {
        if (closed) throw new IllegalStateException("Segment composition is closed");
        Objects.requireNonNull(canContinue);
        try {
            while (phase != Phase.DONE) {
                if (phase == Phase.BASE && document != null) {
                    if (!documentStep(canContinue)) return false;
                    baseDocument = document.result();
                    document.close();
                    document = null;
                    if (!source.contains("palette", Tag.TAG_LIST))
                        throw new IllegalArgumentException(
                                "Segments currently require a single block palette.");
                    phase = Phase.VALIDATE_SEGMENT;
                    progress++;
                    continue;
                }
                if (phase == Phase.VARIANT_DOCUMENT && document != null) {
                    if (!documentStep(canContinue)) return false;
                    variantDocument = document.result();
                    document.close();
                    document = null;
                    checked.put(variant.id(), variantDocument);
                    validateVariantSize();
                    index = 0;
                    phase = Phase.VALIDATE_LOOT;
                    progress++;
                    continue;
                }
                if (phase == Phase.ROOT_COPY) {
                    if (!copyStep(canContinue)) return false;
                    output = copy.result();
                    copy.close();
                    copy = null;
                    shallow = null;
                    if (plan.choices().isEmpty()) {
                        phase = Phase.DONE;
                        progress++;
                        continue;
                    }
                    palette = output.getList("palette", Tag.TAG_COMPOUND);
                    entities = output.getList("entities", Tag.TAG_COMPOUND);
                    entityPatches.addAll(EntityPatches.embedded(source));
                    extensionData = source.contains(SchematicExtensions.KEY);
                    index = 0;
                    phase = Phase.PALETTE_INDEX;
                    progress++;
                    continue;
                }
                if (phase == Phase.PART_COPY) {
                    if (!copyStep(canContinue)) return false;
                    part = copy.result();
                    copy.close();
                    copy = null;
                    extensionData |= part.contains(SchematicExtensions.KEY);
                    partPatches = EntityPatches.embedded(part);
                    if (segment.occurrence() != null) {
                        var config = EntityPatches.embeddedConfig(part);
                        sourceSchema =
                                config.has(EntityPatches.KEY)
                                        ? config.getAsJsonObject(EntityPatches.KEY)
                                                .get("version")
                                                .getAsInt()
                                        : 2;
                        remappedPatches = new ArrayList<>();
                        remap.clear();
                        index = 0;
                        phase = Phase.OCCURRENCE_ROOTS;
                    } else {
                        index = 0;
                        phase = Phase.PART_PATCHES;
                    }
                    progress++;
                    continue;
                }
                if (phase == Phase.RESULT_DOCUMENT && document != null) {
                    if (!documentStep(canContinue)) return false;
                    candidate = document.result();
                    document.close();
                    document = null;
                    phase = extensionData ? Phase.AUTHOR_BASE : Phase.DONE;
                    progress++;
                    continue;
                }
                if (phase == Phase.AUTHOR_EXPORT) {
                    long before = export.progress();
                    boolean done = export.step(canContinue);
                    progress += export.progress() - before;
                    if (!done) return false;
                    output = export.result();
                    export.close();
                    export = null;
                    phase = Phase.DONE;
                    progress++;
                    continue;
                }
                if (!canContinue.getAsBoolean()) return false;
                switch (phase) {
                    case LOOT_INPUT -> {
                        if (index < baseLoot.size()) {
                            reserve(2L * Long.BYTES);
                            loot.add(baseLoot.get(index++));
                        } else {
                            index = 0;
                            if (segments.isEmpty()) beginRoot();
                            else {
                                startDocument(source);
                                phase = Phase.BASE;
                            }
                        }
                    }
                    case VALIDATE_SEGMENT -> {
                        if (segmentIndex == segments.size()) {
                            beginRoot();
                            break;
                        }
                        segment = segments.get(segmentIndex);
                        if (!baseDocument.contains(segment.origin())
                                || !baseDocument.contains(
                                        segment.origin().offset(segment.size()).offset(-1, -1, -1)))
                            throw new IllegalArgumentException(
                                    "Segment outside ship: " + segment.id());
                        variantIndex = 0;
                        phase = Phase.VALIDATE_VARIANT;
                    }
                    case VALIDATE_VARIANT -> {
                        if (variantIndex == segment.pool().size()) {
                            segmentIndex++;
                            phase = Phase.VALIDATE_SEGMENT;
                            break;
                        }
                        variant = segment.pool().get(variantIndex);
                        var variants = source.getCompound(StructureSegments.VARIANTS);
                        if (!variants.contains(variant.id(), Tag.TAG_COMPOUND))
                            throw new IllegalArgumentException(
                                    "Missing embedded variant: " + variant.name());
                        part = variants.getCompound(variant.id());
                        index = 0;
                        phase =
                                segment.occurrence() == null
                                        ? Phase.VALIDATE_ROOTS
                                        : Phase.VARIANT_DOCUMENT;
                        if (phase == Phase.VARIANT_DOCUMENT) beginVariantDocument();
                    }
                    case VALIDATE_ROOTS -> {
                        var list = part.getList("entities", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            if (EntityIdentity.read(list.getCompound(index++).getCompound("nbt"))
                                    != null)
                                throw new IllegalArgumentException(
                                        "Patched variant requires stable entity_occurrence UUID for segment "
                                                + segment.id());
                        } else {
                            if (part.contains(SchematicExtensions.KEY))
                                throw new IllegalArgumentException(
                                        "Patched variant requires stable entity_occurrence UUID for segment "
                                                + segment.id());
                            index = 0;
                            phase = Phase.VALIDATE_IDENTITIES;
                        }
                    }
                    case VALIDATE_IDENTITIES -> {
                        var list = part.getList("blocks", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var value = list.getCompound(index++);
                            if (value.contains(SchematicPatches.EMBEDDED)
                                    && SchematicPatches.schema(value) == 2)
                                throw new IllegalArgumentException(
                                        "Patched variant requires stable entity_occurrence UUID for segment "
                                                + segment.id());
                        } else beginVariantDocument();
                    }
                    case VALIDATE_LOOT -> {
                        if (index < variant.loot().size()) {
                            if (!variantDocument
                                    .blocks()
                                    .containsKey(variant.loot().get(index++).pos()))
                                throw new IllegalArgumentException(
                                        "Variant loot outside blocks: " + variant.name());
                        } else {
                            variantIndex++;
                            phase = Phase.VALIDATE_VARIANT;
                        }
                    }
                    case ROOT_FIELDS -> {
                        if (fields.hasNext()) {
                            var key = fields.next();
                            if (!StructureSegments.VARIANTS.equals(key))
                                shallow.put(key, source.get(key));
                        } else {
                            copy = resources.copyInputIncrementally(shallow);
                            phase = Phase.ROOT_COPY;
                        }
                    }
                    case PALETTE_INDEX -> {
                        if (index < palette.size()) {
                            reserve(4L * Long.BYTES);
                            paletteIndices.putIfAbsent(palette.getCompound(index), index);
                            index++;
                        } else {
                            index = 0;
                            phase = Phase.BLOCK_INDEX;
                        }
                    }
                    case BLOCK_INDEX -> {
                        var list = output.getList("blocks", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var value = list.getCompound(index++);
                            reserve(8L * Long.BYTES);
                            blocks.put(SchematicPatches.blockPosition(value), value);
                        } else {
                            segmentIndex = 0;
                            phase = Phase.SELECT;
                        }
                    }
                    case SELECT -> {
                        if (segmentIndex == segments.size()) {
                            if (!applied.equals(plan.choices().keySet()))
                                throw new IllegalArgumentException(
                                        "Plan refers to missing segment.");
                            reserve(ResourceMemoryBudget.arrayBytes(blocks.size(), Long.BYTES));
                            outputBlocks = new ListTag(blocks.size());
                            outputEntries = blocks.values().iterator();
                            phase = Phase.OUTPUT_BLOCKS;
                            break;
                        }
                        segment = segments.get(segmentIndex++);
                        var chosen = plan.choices().get(segment.id());
                        if (chosen == null) break;
                        variant =
                                segment.pool().stream()
                                        .filter(v -> v.id().equals(chosen))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "Plan refers to missing variant: "
                                                                        + chosen));
                        applied.add(segment.id());
                        copy =
                                resources.copyInputIncrementally(
                                        source.getCompound(StructureSegments.VARIANTS)
                                                .getCompound(chosen));
                        phase = Phase.PART_COPY;
                    }
                    case OCCURRENCE_ROOTS -> {
                        var list = part.getList("entities", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var data = list.getCompound(index++).getCompound("nbt");
                            var id = EntityIdentity.read(data);
                            if (id != null) {
                                var mapped = EntityIdentity.composed(segment.occurrence(), id);
                                reserve(4L * Long.BYTES);
                                remap.put(id, mapped);
                                EntityIdentity.put(data, mapped);
                            }
                        } else {
                            index = 0;
                            phase = Phase.OCCURRENCE_PATCHES;
                        }
                    }
                    case OCCURRENCE_PATCHES -> {
                        if (index < partPatches.size()) {
                            var entry = partPatches.get(index++);
                            var mapped = remap.get(EntityPatches.token(entry));
                            if (mapped == null)
                                throw new IllegalArgumentException(
                                        "Missing variant entity patch target");
                            var next =
                                    PatchInstances.compose(
                                            entry, true, segment.occurrence(), sourceSchema);
                            next.addProperty("entity", mapped.toString());
                            remappedPatches.add(next);
                        } else phase = Phase.OCCURRENCE_COMMIT;
                    }
                    case OCCURRENCE_COMMIT -> {
                        if (!partPatches.isEmpty()) {
                            var config = EntityPatches.embeddedConfig(part);
                            EntityPatches.set(config, remappedPatches);
                            EntityPatches.embed(part, config);
                        }
                        index = 0;
                        phase = Phase.OCCURRENCE_BLOCKS;
                    }
                    case OCCURRENCE_BLOCKS -> {
                        var list = part.getList("blocks", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var value = list.getCompound(index++);
                            var patches = SchematicPatches.embeddedEntries(value);
                            if (SchematicPatches.schema(value) == 2)
                                SchematicPatches.writeEmbedded(
                                        value,
                                        patches.stream()
                                                .map(
                                                        p ->
                                                                PatchInstances.compose(
                                                                        p,
                                                                        false,
                                                                        segment.occurrence()))
                                                .toList());
                        } else {
                            partPatches = EntityPatches.embedded(part);
                            index = 0;
                            phase = Phase.PART_PATCHES;
                        }
                    }
                    case PART_PATCHES -> {
                        if (index < partPatches.size()) entityPatches.add(partPatches.get(index++));
                        else {
                            int count = part.getList("palette", Tag.TAG_COMPOUND).size();
                            reserve(ResourceMemoryBudget.arrayBytes(count, Integer.BYTES));
                            paletteRemap = new int[count];
                            index = 0;
                            phase = Phase.PALETTE_MERGE;
                        }
                    }
                    case PALETTE_MERGE -> {
                        var list = part.getList("palette", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var state = list.getCompound(index);
                            Integer mapped = paletteIndices.get(state);
                            if (mapped == null) {
                                mapped = palette.size();
                                reserve(6L * Long.BYTES);
                                palette.add(state);
                                paletteIndices.put(state, mapped);
                            }
                            paletteRemap[index++] = mapped;
                        } else {
                            removeBlocks = blocks.keySet().iterator();
                            phase = Phase.REMOVE_BLOCKS;
                        }
                    }
                    case REMOVE_BLOCKS -> {
                        if (removeBlocks.hasNext()) {
                            if (segment.contains(removeBlocks.next())) removeBlocks.remove();
                        } else {
                            index = 0;
                            phase = Phase.ADD_BLOCKS;
                        }
                    }
                    case ADD_BLOCKS -> {
                        var list = part.getList("blocks", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var value = list.getCompound(index++);
                            var at = SchematicPatches.blockPosition(value).offset(segment.origin());
                            value.put("pos", StructureSegments.ints(at));
                            value.putInt("state", paletteRemap[value.getInt("state")]);
                            if (value.contains("nbt", Tag.TAG_COMPOUND)) {
                                var data = value.getCompound("nbt");
                                data.putInt("x", at.getX());
                                data.putInt("y", at.getY());
                                data.putInt("z", at.getZ());
                            }
                            String key = com.eynny.npships.authoring.io.SubLevelImport.GROUP;
                            if (value.contains(key))
                                value.putString(
                                        key,
                                        UUID.nameUUIDFromBytes(
                                                        (segment.id() + ":" + value.getString(key))
                                                                .getBytes(StandardCharsets.UTF_8))
                                                .toString());
                            reserve(8L * Long.BYTES);
                            blocks.put(at, value);
                        } else {
                            int capacity =
                                    Math.addExact(
                                            entities.size(),
                                            part.getList("entities", Tag.TAG_COMPOUND).size());
                            reserve(ResourceMemoryBudget.arrayBytes(capacity, Long.BYTES));
                            combined = new ListTag(capacity);
                            index = 0;
                            phase = Phase.KEEP_ENTITIES;
                        }
                    }
                    case KEEP_ENTITIES -> {
                        if (index < entities.size()) {
                            var value = entities.getCompound(index++);
                            if (!inside(value, segment)) combined.add(value);
                        } else {
                            index = 0;
                            phase = Phase.ADD_ENTITIES;
                        }
                    }
                    case ADD_ENTITIES -> {
                        var list = part.getList("entities", Tag.TAG_COMPOUND);
                        if (index < list.size()) {
                            var value = list.getCompound(index++);
                            translateOwned(value, segment.origin());
                            combined.add(value);
                        } else {
                            entities = combined;
                            combined = null;
                            nextLoot = new ArrayList<>();
                            index = 0;
                            phase = Phase.KEEP_LOOT;
                        }
                    }
                    case KEEP_LOOT -> {
                        if (index < loot.size()) {
                            var entry = loot.get(index++);
                            if (!segment.contains(entry.pos())) {
                                reserve(2L * Long.BYTES);
                                nextLoot.add(entry);
                            }
                        } else {
                            index = 0;
                            phase = Phase.ADD_LOOT;
                        }
                    }
                    case ADD_LOOT -> {
                        if (index < variant.loot().size()) {
                            var entry = variant.loot().get(index++);
                            reserve(4L * Long.BYTES);
                            nextLoot.add(
                                    new LootAssignments.Entry(
                                            entry.pos().offset(segment.origin()),
                                            entry.table(),
                                            entry.rolls()));
                        } else {
                            loot = nextLoot;
                            nextLoot = null;
                            part = null;
                            phase = Phase.SELECT;
                        }
                    }
                    case OUTPUT_BLOCKS -> {
                        if (outputEntries.hasNext()) outputBlocks.add(outputEntries.next());
                        else {
                            output.put("blocks", outputBlocks);
                            output.put("entities", entities);
                            output.remove(StructureSegments.VARIANTS);
                            if (!entityPatches.isEmpty()
                                    || output.contains(EntityPatches.EMBEDDED)) {
                                index = 0;
                                phase = Phase.PRUNE_ROOTS;
                            } else {
                                startDocument(output);
                                phase = Phase.RESULT_DOCUMENT;
                            }
                        }
                    }
                    case PRUNE_ROOTS -> {
                        if (index < entities.size()) {
                            var id =
                                    EntityIdentity.read(
                                            entities.getCompound(index++).getCompound("nbt"));
                            if (id != null) {
                                reserve(3L * Long.BYTES);
                                surviving.add(id);
                            }
                        } else {
                            // Preserve validation of the complete composed list before pruning.
                            var config = EntityPatches.embeddedConfig(source);
                            EntityPatches.set(config, entityPatches);
                            remappedPatches = new ArrayList<>();
                            index = 0;
                            phase = Phase.PRUNE_PATCHES;
                        }
                    }
                    case PRUNE_PATCHES -> {
                        if (index < entityPatches.size()) {
                            var entry = entityPatches.get(index++);
                            if (surviving.contains(EntityPatches.token(entry)))
                                remappedPatches.add(entry);
                        } else phase = Phase.PRUNE_COMMIT;
                    }
                    case PRUNE_COMMIT -> {
                        var config = EntityPatches.embeddedConfig(source);
                        EntityPatches.set(config, remappedPatches);
                        EntityPatches.embed(output, config);
                        startDocument(output);
                        phase = Phase.RESULT_DOCUMENT;
                    }
                    case AUTHOR_BASE -> {
                        if (source.contains(SchematicExtensions.KEY)) {
                            var operation =
                                    SchematicExtensions.operation(
                                            PatchAuthoring.Kind.SEGMENT,
                                            p ->
                                                    segments.stream()
                                                                    .anyMatch(
                                                                            s ->
                                                                                    plan.choices()
                                                                                                    .containsKey(
                                                                                                            s
                                                                                                                    .id())
                                                                                            && s
                                                                                                    .contains(
                                                                                                            p))
                                                            ? List.of()
                                                            : List.of(p),
                                            p ->
                                                    segments.stream()
                                                                    .anyMatch(
                                                                            s ->
                                                                                    plan.choices()
                                                                                                    .containsKey(
                                                                                                            s
                                                                                                                    .id())
                                                                                            && s
                                                                                                    .contains(
                                                                                                            p.x,
                                                                                                            p.y,
                                                                                                            p.z))
                                                            ? List.of()
                                                            : List.of(p),
                                            java.util.function.UnaryOperator.identity(),
                                            Optional::of);
                            candidate =
                                    SchematicExtensions.apply(baseDocument, candidate, operation);
                        }
                        segmentIndex = 0;
                        phase = Phase.AUTHOR_PARTS;
                    }
                    case AUTHOR_PARTS -> {
                        if (segmentIndex == segments.size()) {
                            export = candidate.beginRuntimeCopy(resources);
                            phase = Phase.AUTHOR_EXPORT;
                            break;
                        }
                        var selectedSegment = segments.get(segmentIndex++);
                        var selected = plan.choices().get(selectedSegment.id());
                        if (selected == null
                                || !source.getCompound(StructureSegments.VARIANTS)
                                        .getCompound(selected)
                                        .contains(SchematicExtensions.KEY)) break;
                        var original = checked.get(selected);
                        var at = selectedSegment.origin();
                        var occurrence = selectedSegment.occurrence();
                        if (occurrence == null)
                            throw new IllegalArgumentException(
                                    "Provider data requires stable entity_occurrence for segment "
                                            + selectedSegment.id());
                        var operation =
                                SchematicExtensions.operation(
                                        PatchAuthoring.Kind.SEGMENT,
                                        p ->
                                                original.contains(p)
                                                        ? List.of(p.offset(at))
                                                        : List.of(),
                                        p ->
                                                com.eynny.npships.authoring.document.SchematicEdits
                                                                .inside(original.size(), p)
                                                        ? List.of(
                                                                p.add(
                                                                        at.getX(), at.getY(),
                                                                        at.getZ()))
                                                        : List.of(),
                                        id -> PatchInstances.compose(id, occurrence),
                                        id -> Optional.of(EntityIdentity.composed(occurrence, id)));
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
                    default ->
                            throw new IllegalStateException(
                                    "Invalid segment composition phase: " + phase);
                }
                progress++;
            }
            return true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void beginRoot() {
        part = null;
        if (plan.choices().isEmpty()) {
            copy = resources.copyInputIncrementally(source);
            phase = Phase.ROOT_COPY;
        } else {
            shallow = new CompoundTag();
            fields = source.getAllKeys().iterator();
            phase = Phase.ROOT_FIELDS;
        }
    }

    private void beginVariantDocument() {
        variantDocument = checked.get(variant.id());
        if (variantDocument == null) {
            startDocument(part);
            phase = Phase.VARIANT_DOCUMENT;
        } else {
            validateVariantSize();
            index = 0;
            phase = Phase.VALIDATE_LOOT;
        }
    }

    private void validateVariantSize() {
        if (!variantDocument.size().equals(segment.size())
                || !part.contains("palette", Tag.TAG_LIST))
            throw new IllegalArgumentException(
                    "Variant dimensions/palette do not match segment: " + variant.name());
    }

    private static boolean inside(CompoundTag entry, Segment region) {
        var position = entry.getList("pos", Tag.TAG_DOUBLE);
        return position.size() == 3
                && region.contains(
                        position.getDouble(0), position.getDouble(1), position.getDouble(2));
    }

    private static void translateOwned(CompoundTag entry, BlockPos delta) {
        var p = entry.getList("pos", Tag.TAG_DOUBLE);
        var bp = entry.getList("blockPos", Tag.TAG_INT);
        var next = new ListTag(3);
        next.add(DoubleTag.valueOf(p.getDouble(0) + delta.getX()));
        next.add(DoubleTag.valueOf(p.getDouble(1) + delta.getY()));
        next.add(DoubleTag.valueOf(p.getDouble(2) + delta.getZ()));
        entry.put("pos", next);
        if (bp.size() == 3)
            entry.put(
                    "blockPos",
                    StructureSegments.ints(
                            new BlockPos(bp.getInt(0), bp.getInt(1), bp.getInt(2)).offset(delta)));
    }

    public long progress() {
        return progress;
    }

    public Composition result() {
        if (closed || phase != Phase.DONE)
            throw new IllegalStateException("Segment composition is incomplete");
        if (!transferred)
            resources.reserveCapacity(ResourceMemoryBudget.arrayBytes(loot.size(), Long.BYTES));
        transferred = true;
        return new Composition(output, Collections.unmodifiableList(loot));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (document != null) document.close();
        if (copy != null) copy.close();
        if (export != null) export.close();
        document = null;
        copy = null;
        export = null;
        baseDocument = candidate = variantDocument = null;
        checked = null;
        paletteIndices = null;
        blocks = null;
        remap = null;
        surviving = null;
        applied = null;
        entityPatches = partPatches = remappedPatches = null;
        source = output = part = shallow = null;
        palette = entities = combined = outputBlocks = null;
        fields = null;
        removeBlocks = null;
        outputEntries = null;
        loot = nextLoot = null;
        resources.release(scratch);
        scratch = 0;
    }
}
