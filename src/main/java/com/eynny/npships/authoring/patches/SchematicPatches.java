package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.authoring.SchematicSafety;
import com.eynny.npships.authoring.document.SchematicCaptureAspects;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * Versioned patches use block-local offsets, so moving a whole segment preserves its internal
 * graph.
 */
public final class SchematicPatches {
    public static final String UNPATCHED = "npships:unpatched";
    public static final String EMBEDDED = "npships:patch";

    private SchematicPatches() {}

    public static List<JsonObject> read(JsonObject config) {
        try {
            return readData(config);
        } catch (NullPointerException
                | ClassCastException
                | IllegalStateException
                | UnsupportedOperationException error) {
            throw new IllegalArgumentException(
                    "Malformed patches payload: " + error.getMessage(), error);
        }
    }

    private static List<JsonObject> readData(JsonObject config) {
        if (config == null || !config.has("patches")) return List.of();
        var root = config.get("patches");
        var object = root.getAsJsonObject();
        if (!object.has("version")
                || !object.get("version").isJsonPrimitive()
                || !object.get("version").getAsJsonPrimitive().isNumber()
                || object.get("version").getAsBigDecimal().intValueExact() != 2)
            throw new IllegalArgumentException("Unsupported patch envelope version.");
        var list = object.getAsJsonArray("entries");
        if (list == null || list.size() > 1024)
            throw new IllegalArgumentException("patches.entries limit is 1024.");
        var out = new ArrayList<JsonObject>();
        for (var value : list) {
            var e = value.getAsJsonObject();
            BlockPos p = position(e.getAsJsonArray("pos"));
            if (!e.has("kind")
                    || !e.get("kind").isJsonPrimitive()
                    || !e.get("kind").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Patch kind must be a string.");
            if (!e.has("block")
                    || !e.get("block").isJsonPrimitive()
                    || !e.get("block").getAsJsonPrimitive().isString()
                    || net.minecraft.resources.ResourceLocation.tryParse(
                                    e.get("block").getAsString())
                            == null)
                throw new IllegalArgumentException("Patch block must be a resource ID.");
            String kind = e.get("kind").getAsString();
            if (!ExternalPatches.external(e)
                    && !PatchSourceOperationsDispatch.all().stream()
                            .anyMatch(code -> code.ownsKind(kind)))
                throw new IllegalArgumentException("Unsupported patch kind: " + kind);
            if (ExternalPatches.external(e)) ExternalPatches.envelope(e);
            for (var code : PatchSourceOperationsDispatch.all()) code.envelope(e);
            out.add(e.deepCopy());
        }
        PatchInstances.validateList(out, false, object.get("version").getAsInt());
        return out;
    }

    public static BlockPos position(JsonArray a) {
        if (a == null || a.size() != 3) throw new IllegalArgumentException("Expected [x,y,z].");
        try {
            return new BlockPos(
                    com.eynny.npships.authoring.validation.JsonNumbers.integer(
                            a.get(0), "patch coordinate"),
                    com.eynny.npships.authoring.validation.JsonNumbers.integer(
                            a.get(1), "patch coordinate"),
                    com.eynny.npships.authoring.validation.JsonNumbers.integer(
                            a.get(2), "patch coordinate"));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Patch coordinates must be integers.", e);
        }
    }

    /** Retain provider/root extension fields when an existing owner replaces only the entries. */
    public static JsonObject rootCopy(JsonObject config) {
        if (config == null || !config.has("patches")) return new JsonObject();
        var root = config.get("patches");
        if (!root.isJsonObject()
                || !root.getAsJsonObject().has("version")
                || com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                root.getAsJsonObject().get("version"), "patch envelope")
                        != 2)
            throw new IllegalArgumentException("Unsupported patch envelope version");
        return root.getAsJsonObject().deepCopy();
    }

    /** Preserve root extensions; write the single supported instance-aware patch format. */
    public static void set(JsonObject config, List<JsonObject> entries) {
        var root = rootCopy(config);
        int version = PatchInstances.storedVersion(root, entries, false);
        set(config, entries, root, version);
    }

    public static void set(JsonObject config, List<JsonObject> entries, int version) {
        set(config, entries, rootCopy(config), version);
    }

    private static void set(
            JsonObject config, List<JsonObject> entries, JsonObject root, int version) {
        var stored = PatchInstances.forWrite(root, entries, false, version);
        PatchInstances.validateList(stored, false, version);
        root.addProperty("version", version);
        var list = new JsonArray();
        stored.forEach(e -> list.add(e.deepCopy()));
        root.add("entries", list);
        config.add("patches", root);
    }

    /** Explicit JSON schema wins over each embedded-only block wrapper. */
    public static int schema(SchematicDocument doc, BlockPos pos) {
        int configured = doc.patchSchema(false);
        if (configured != 0) return configured;
        var block = doc.blockEntryCopy(pos);
        return schema(block);
    }

    public static int schema(CompoundTag block) {
        if (block == null || !block.contains(EMBEDDED)) return 2;
        embeddedRoot(block);
        return 2;
    }

    public static boolean identified(CompoundTag tag) {
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND))
            if (((CompoundTag) value).contains(EMBEDDED) && schema((CompoundTag) value) == 2)
                return true;
        return false;
    }

    /** Read current identified records without rewriting payload data. */
    public static List<JsonObject> readIdentified(JsonObject config) {
        var entries = read(config);
        if (entries.isEmpty()) return entries;
        int version = config.getAsJsonObject("patches").get("version").getAsInt();
        return entries.stream().map(p -> PatchInstances.materialize(p, false, version)).toList();
    }

    public static JsonObject authoringConfig(SchematicDocument doc) {
        var config = doc.configCopy();
        if (config == null) config = new JsonObject();
        var identified = new ArrayList<JsonObject>();
        if (config.has("patches")) identified.addAll(readIdentified(config));
        else
            for (var patch : doc.templatePatches())
                identified.add(
                        PatchInstances.materialize(
                                patch, false, schema(doc, position(patch.getAsJsonArray("pos")))));
        if (!identified.isEmpty()) set(config, identified);
        return config;
    }

    /** Current instance identity is independent of a retained layer's anchor. */
    public static JsonObject authoringConfig(SchematicDocument doc, BlockPos origin) {
        return authoringConfig(doc);
    }

    public static JsonArray vector(BlockPos p) {
        var a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    public static SchematicDocument capture(
            ServerLevel level, BlockPos origin, SchematicDocument source) throws Exception {
        return capture(level, origin, source, java.util.Set.of());
    }

    public static SchematicDocument capture(
            ServerLevel level,
            BlockPos origin,
            SchematicDocument source,
            java.util.Set<BlockPos> copiedControllers)
            throws Exception {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("World capture requires the server thread");
        try (var nativeReads =
                com.eynny.npships.integration.NativeBlockAccess.capture(level, origin)) {
            SchematicSafety.validateLive(level, origin, source, copiedControllers);
            return captureCurrent(level, origin, source);
        }
    }

    private static SchematicDocument captureCurrent(
            ServerLevel level, BlockPos origin, SchematicDocument source) throws Exception {
        try (var capture =
                SchematicExtensions.beginCapture(
                        level,
                        origin,
                        source,
                        () -> SchematicCaptureAspects.discover(level, origin, source))) {
            var config = source.hasConfig() ? source.configCopy() : new JsonObject();
            var entries = new JsonArray();
            var palette = source.paletteCopy();
            var controllerEntries = new java.util.HashMap<BlockPos, CompoundTag>();
            var nativeCapture = new PatchBlockCaptures();
            for (var block : source.blocks().entrySet()) {
                BlockPos p = block.getKey();
                var data = source.blockEntityData(p);
                String id = palette.getCompound(block.getValue()).getString("Name");
                var capturedEntry =
                        nativeCapture.entry(level, origin, source, p, id, data, capture::replaces);
                if (capturedEntry != null) controllerEntries.put(p, capturedEntry);
                if (nativeCapture.skipRemaining(source, p, id, data)) continue;
                JsonObject patch =
                        nativeCapture.capture(
                                level, origin, source, p, id, data, null, capture::replaces);
                if (patch != null) {
                    if (entries.size() >= 1024)
                        throw new IllegalArgumentException("Export exceeds 1024 devices.");
                    patch.add("pos", vector(p));
                    patch.addProperty("block", id);
                    entries.add(patch);
                }
            }
            var savedPatches =
                    source.hasConfig() && source.configCopy().has("patches")
                            ? read(source.configCopy())
                            : source.templatePatches();
            for (var external : savedPatches) {
                if (!ExternalPatches.external(external)) continue;
                var pos = position(external.getAsJsonArray("pos"));
                if (entries.size() >= 1024)
                    throw new IllegalArgumentException("Export exceeds 1024 devices.");
                entries.add(external);
            }
            nativeCapture.complete(level, source, entries);
            if (!entries.isEmpty() || capture.hasReplacements()) {
                set(config, entries.asList().stream().map(JsonElement::getAsJsonObject).toList());
            }
            var captured =
                    controllerEntries.isEmpty()
                            ? source.withConfig(config)
                            : source.withBlocks(controllerEntries, palette, config);
            return capture.complete(captured);
        }
    }

    /**
     * Embed into detached block entries before composition; neither vanilla nor Create serializes
     * this into BEs.
     */
    public static void embed(CompoundTag tag, JsonObject config) {
        try (var embedding = beginEmbed(tag, config)) {
            while (!embedding.step(() -> true)) {}
        }
    }

    public static Embedding beginEmbed(CompoundTag tag, JsonObject config) {
        return new Embedding(tag, config);
    }

    /** Detached block sweeps retain only authored target positions, not a complete source map. */
    public static final class Embedding implements AutoCloseable {
        private final CompoundTag tag;
        private final JsonObject config;
        private final ListTag blocks;
        private final Map<BlockPos, List<JsonObject>> grouped = new LinkedHashMap<>();
        private final Map<BlockPos, CompoundTag> targets = new HashMap<>();
        private List<JsonObject> patches;
        private Iterator<Map.Entry<BlockPos, List<JsonObject>>> groups;
        private List<JsonObject> currentPatches;
        private CompoundTag currentBlock;
        private int phase, index, patchIndex, codeIndex;
        private long progress;
        private boolean closed;

        private Embedding(CompoundTag tag, JsonObject config) {
            this.tag = Objects.requireNonNull(tag);
            this.config = config;
            blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        }

        public boolean step(java.util.function.BooleanSupplier canContinue) {
            if (closed) throw new IllegalStateException("Patch embedding is closed");
            while (phase != 5 && canContinue.getAsBoolean()) {
                switch (phase) {
                    case 0 -> {
                        EntityPatches.embed(tag, config);
                        patches = read(config);
                        phase =
                                patches.isEmpty() && (config == null || !config.has("patches"))
                                        ? 5
                                        : 1;
                    }
                    case 1 -> {
                        if (index < patches.size()) {
                            var patch = patches.get(index++);
                            grouped.computeIfAbsent(
                                            position(patch.getAsJsonArray("pos")),
                                            ignored -> new ArrayList<>())
                                    .add(patch);
                        } else {
                            index = 0;
                            phase = 2;
                        }
                    }
                    case 2 -> {
                        if (index < blocks.size()) {
                            var block = blocks.getCompound(index++);
                            BlockPos position = grouped.isEmpty() ? null : blockPosition(block);
                            if (position != null && grouped.containsKey(position))
                                targets.putIfAbsent(position, block);
                            else if (config != null && config.has("patches"))
                                block.remove(EMBEDDED);
                        } else {
                            groups = grouped.entrySet().iterator();
                            phase = 3;
                        }
                    }
                    case 3 -> {
                        if (!groups.hasNext()) {
                            phase = 5;
                            break;
                        }
                        var entry = groups.next();
                        currentBlock = targets.get(entry.getKey());
                        if (currentBlock == null)
                            throw new IllegalArgumentException(
                                    "Patch target is absent: " + entry.getKey());
                        currentPatches = entry.getValue();
                        if (currentPatches.stream().anyMatch(p -> !ExternalPatches.external(p)))
                            currentBlock.remove(UNPATCHED);
                        writeEmbedded(
                                currentBlock,
                                currentPatches,
                                config.getAsJsonObject("patches").get("version").getAsInt());
                        patchIndex = codeIndex = 0;
                        phase = 4;
                    }
                    case 4 -> {
                        var codes = PatchSourceOperationsDispatch.all();
                        if (patchIndex >= currentPatches.size()) {
                            phase = 3;
                            break;
                        }
                        if (codeIndex < codes.size())
                            codes.get(codeIndex++)
                                    .embed(currentPatches.get(patchIndex), currentBlock);
                        else {
                            codeIndex = 0;
                            patchIndex++;
                        }
                    }
                    default -> throw new IllegalStateException("Invalid patch embedding phase");
                }
                progress++;
            }
            return phase == 5;
        }

        public long progress() {
            return progress;
        }

        @Override
        public void close() {
            closed = true;
            grouped.clear();
            targets.clear();
            patches = currentPatches = null;
            currentBlock = null;
            groups = null;
        }
    }

    public static BlockPos blockPosition(CompoundTag block) {
        var p = block.getList("pos", Tag.TAG_INT);
        return new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
    }

    public static List<JsonObject> fromTemplate(CompoundTag tag) {
        var list = new ArrayList<JsonObject>();
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            if (!block.contains(EMBEDDED)) continue;
            for (var patch : embeddedEntries(block)) {
                patch.add("pos", vector(blockPosition(block)));
                list.add(patch);
            }
        }
        return list;
    }

    public static void validate(CompoundTag tag) {
        validate(tag, true);
    }

    public static void validate(CompoundTag tag, boolean complete) {
        validate(tag, complete, PatchAuthoring.Kind.VALIDATE);
    }

    public static void validate(CompoundTag tag, boolean complete, PatchAuthoring.Kind operation) {
        SchematicSafety.validate(tag);
        var doc = new SchematicDocument(tag, null);
        validatePatches(doc, complete, operation);
    }

    /** Geometry was already validated; patch-only editor edits retain its immutable entries. */
    public static void validatePatches(SchematicDocument doc, boolean complete) {
        validatePatches(doc, complete, PatchAuthoring.Kind.VALIDATE);
    }

    public static void validatePatches(
            SchematicDocument doc, boolean complete, PatchAuthoring.Kind operation) {
        doc = doc.patchValidationDocument();
        try {
            validatePatchData(doc, complete);
            EntityPatches.validate(doc, false);
            PatchInstances.validateDocument(doc);
            SchematicExtensions.validate(doc, false, operation);
        } catch (NullPointerException
                | ClassCastException
                | IllegalStateException
                | UnsupportedOperationException error) {
            throw new IllegalArgumentException(
                    "Malformed embedded patch payload: " + error.getMessage(), error);
        }
    }

    private static void validatePatchData(SchematicDocument doc, boolean complete) {
        var palette = doc.paletteCopy();
        var ropeEnds = new HashSet<BlockPos>();
        var all = doc.templatePatches();
        for (var patch : all) {
            if (!ExternalPatches.external(patch)
                    && !PatchSourceOperationsDispatch.all().stream()
                            .anyMatch(code -> code.ownsKind(patch.get("kind").getAsString())))
                throw new IllegalArgumentException("Unknown embedded patch kind.");
            var p = position(patch.getAsJsonArray("pos"));
            var index = doc.blocks().get(p);
            if (index == null
                    || !palette.getCompound(index)
                            .getString("Name")
                            .equals(patch.get("block").getAsString()))
                throw new IllegalArgumentException("Patch no longer matches block at " + p);
            if (ExternalPatches.external(patch)) {
                ExternalPatches.validate(doc, patch, palette);
                continue;
            }
            for (var code : PatchSourceOperationsDispatch.all())
                code.validate(doc, patch, complete, ropeEnds);
            PatchDevices.validateLinks(patch, doc);
        }
        for (var code : PatchSourceOperationsDispatch.all()) code.references(doc, all);
    }

    /**
     * Runtime counterpart of validatePatches, with a budget boundary between bindings/providers.
     */
    static final class RuntimeValidation implements AutoCloseable {
        private final SchematicDocument doc;
        private final boolean complete;
        private final List<JsonObject> entries;
        private final ListTag palette;
        private final Set<BlockPos> ropeEnds = new HashSet<>();
        private final java.util.Iterator<PatchSourceOperations> references;
        private int index, tail;
        private final EntityPatches.RuntimeValidation entityValidation;
        private final SchematicExtensions.RuntimeValidation authorValidation;

        RuntimeValidation(
                SchematicDocument doc,
                boolean complete,
                List<JsonObject> entries,
                ListTag palette,
                CompoundTag ownedSource) {
            this(doc, complete, entries, palette, ownedSource, bytes -> {});
        }

        RuntimeValidation(
                SchematicDocument doc,
                boolean complete,
                List<JsonObject> entries,
                ListTag palette,
                CompoundTag ownedSource,
                java.util.function.LongConsumer reserve) {
            this.doc = doc.patchValidationDocument();
            this.complete = complete;
            this.entries = entries;
            this.palette = palette;
            references = PatchSourceOperationsDispatch.all().iterator();
            authorValidation =
                    SchematicExtensions.beginValidation(
                            this.doc,
                            false,
                            PatchAuthoring.Kind.VALIDATE,
                            com.eynny.npships.api.patches.PatchPolicy.defaults(),
                            reserve);
            entityValidation =
                    new EntityPatches.RuntimeValidation(
                            this.doc,
                            ownedSource,
                            entries,
                            EntityPatches.entries(this.doc),
                            false,
                            reserve);
        }

        boolean step() {
            if (index < entries.size()) {
                var patch = entries.get(index++);
                if (!ExternalPatches.external(patch)
                        && PatchSourceOperationsDispatch.all().stream()
                                .noneMatch(code -> code.ownsKind(patch.get("kind").getAsString())))
                    throw new IllegalArgumentException("Unknown embedded patch kind.");
                var p = position(patch.getAsJsonArray("pos"));
                var stateIndex = doc.blocks().get(p);
                if (stateIndex == null
                        || !palette.getCompound(stateIndex)
                                .getString("Name")
                                .equals(patch.get("block").getAsString()))
                    throw new IllegalArgumentException("Patch no longer matches block at " + p);
                if (ExternalPatches.external(patch)) ExternalPatches.validate(doc, patch, palette);
                else {
                    for (var code : PatchSourceOperationsDispatch.all())
                        code.validate(doc, patch, complete, ropeEnds);
                    PatchDevices.validateLinks(patch, doc);
                }
                return false;
            }
            if (references.hasNext()) {
                references.next().references(doc, entries);
                return false;
            }
            switch (tail) {
                case 0 -> {
                    if (!entityValidation.step()) return false;
                }
                case 1 -> {}
                case 2 -> {
                    int[] portions = {1};
                    if (!authorValidation.step(() -> portions[0]-- > 0)) return false;
                }
                default -> {
                    return true;
                }
            }
            tail++;
            return false;
        }

        /** Canonical records for providers actually validated, retained by the prepared owner. */
        java.util.Map<net.minecraft.resources.ResourceLocation, java.util.List<PatchAuthoring.Data>>
                preparedAuthorData() {
            if (tail < 3) throw new IllegalStateException("Runtime patch validation incomplete");
            return authorValidation.preparedData();
        }

        public void close() {
            authorValidation.close();
            entityValidation.close();
        }
    }

    /**
     * Captured peers are resolved before the per-block native-data guard, without a full pass per
     * tick.
     */
    static final class CaptureValidation {
        private final CompoundTag source;
        private final SchematicDocument doc;
        private final List<JsonObject> patches;
        private final Set<BlockPos> paired = new HashSet<>();
        private int patchIndex, blockIndex;

        CaptureValidation(CompoundTag source, SchematicDocument doc, List<JsonObject> patches) {
            this.source = source;
            this.doc = doc;
            this.patches = patches;
        }

        boolean step() {
            if (patchIndex < patches.size()) {
                var patch = patches.get(patchIndex++);
                for (var provider : PatchSourceOperationsDispatch.all())
                    paired.addAll(provider.captureTargets(patch));
                return false;
            }
            var blocks = source.getList("blocks", Tag.TAG_COMPOUND);
            if (blockIndex == blocks.size()) return true;
            var block = blocks.getCompound(blockIndex++);
            var at = blockPosition(block);
            var entry =
                    new PatchSourceOperations.Capture(
                            doc,
                            block,
                            at,
                            doc.blockName(at),
                            block.getCompound("nbt"),
                            block.contains(EMBEDDED) ? embeddedBuiltin(block) : null,
                            paired);
            for (var provider : PatchSourceOperationsDispatch.captureGuards())
                provider.requireCapture(entry);
            return false;
        }
    }

    public static List<JsonObject> embeddedEntries(CompoundTag block) {
        if (!block.contains(EMBEDDED)) return List.of();
        var value = embeddedRoot(block);
        var entries = new ArrayList<JsonObject>();
        for (var entry : value.getAsJsonArray("entries"))
            entries.add(entry.getAsJsonObject().deepCopy());
        if (entries.isEmpty() || entries.size() > 1024)
            throw new IllegalArgumentException("Invalid embedded patch list size");
        PatchInstances.validateList(entries, false, 2);
        return List.copyOf(entries);
    }

    public static void writeEmbedded(CompoundTag block, List<JsonObject> entries) {
        writeEmbedded(block, entries, PatchInstances.version(entries, false));
    }

    public static void writeEmbedded(
            CompoundTag block, List<JsonObject> entries, int schemaVersion) {
        if (schemaVersion != 2)
            throw new IllegalArgumentException("Unsupported embedded patch version");
        if (entries.isEmpty()) {
            block.remove(EMBEDDED);
            return;
        }

        // Replace only owned envelope fields. Unknown wrapper metadata belongs to this target,
        // independently of provider payloads and the source/config authoritative entry list.
        var wrapper = embeddedRoot(block);
        wrapper.addProperty("version", 2);
        var list = new JsonArray();
        entries.forEach(p -> list.add(PatchInstances.materialize(p, false, 2)));
        wrapper.add("entries", list);
        block.putByteArray(
                EMBEDDED, wrapper.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonObject embeddedRoot(CompoundTag block) {
        if (!block.contains(EMBEDDED)) return new JsonObject();
        var value =
                JsonStructure.parse(
                                new String(
                                        block.getByteArray(EMBEDDED),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
        if (value.has("kind")
                || !value.has("version")
                || com.eynny.npships.authoring.validation.JsonNumbers.integer(
                                value.get("version"), "embedded patch envelope")
                        != 2)
            throw new IllegalArgumentException("Unsupported embedded patch envelope");
        return value;
    }

    /** Retained single-entry helper refuses ambiguity instead of silently dropping siblings. */
    public static JsonObject embedded(CompoundTag block) {
        var entries = embeddedEntries(block);
        if (entries.size() != 1) throw new IllegalArgumentException("Expected one embedded patch");
        return entries.getFirst();
    }

    /** B1 keeps the old specialized built-in slot while allowing external siblings beside it. */
    public static JsonObject embeddedBuiltin(CompoundTag block) {
        var entries =
                embeddedEntries(block).stream().filter(p -> !ExternalPatches.external(p)).toList();
        if (entries.size() > 1)
            throw new IllegalArgumentException(
                    "Only one native device record is supported at this target");
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public static JsonObject move(JsonObject config, BlockPos from, BlockPos to, boolean retain) {
        if (config == null || from == null || read(config).isEmpty()) return config;
        var updated = config.deepCopy();
        int sourceSchema = config.getAsJsonObject("patches").get("version").getAsInt();
        var entries = new JsonArray();
        for (var patch : read(config)) {
            BlockPos old = position(patch.getAsJsonArray("pos"));
            boolean moved = old.equals(from);
            if (moved && retain && !from.equals(to))
                patch = PatchInstances.materialize(patch, false, sourceSchema);
            if (moved && !retain) continue;
            BlockPos next = moved ? to : old;
            if (retain) moveOffsets(patch, old, next, from, to);
            else PatchDevices.removeTarget(patch, old, from);
            patch.add("pos", vector(next));
            entries.add(patch);
        }
        set(updated, entries.asList().stream().map(JsonElement::getAsJsonObject).toList());
        return updated;
    }

    private static void moveOffsets(
            JsonElement value, BlockPos oldOwner, BlockPos newOwner, BlockPos from, BlockPos to) {
        if (ExternalPatches.external(value)) return;
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("offset")) {
                BlockPos target = oldOwner.offset(position(object.getAsJsonArray("offset")));
                object.add(
                        "offset", vector((target.equals(from) ? to : target).subtract(newOwner)));
            }
            for (var e : object.entrySet())
                if (!e.getKey().equals("offset"))
                    moveOffsets(e.getValue(), oldOwner, newOwner, from, to);
        } else if (value.isJsonArray())
            for (var e : value.getAsJsonArray()) moveOffsets(e, oldOwner, newOwner, from, to);
    }

    /**
     * Existing imported CC IDs without captured disk data must not silently reuse the source
     * computer.
     */
    public static void requireCapture(CompoundTag tag) {
        requireCapture(tag, new SchematicDocument(tag, null));
    }

    static void requireCapture(CompoundTag tag, SchematicDocument doc) {
        var code = PatchSourceOperationsDispatch.all();
        var paired = new HashSet<BlockPos>();
        for (var patch : fromTemplate(tag))
            for (var provider : code) paired.addAll(provider.captureTargets(patch));
        for (var value : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var captured = block.contains(EMBEDDED) ? embeddedBuiltin(block) : null;
            var at = blockPosition(block);
            var entry =
                    new PatchSourceOperations.Capture(
                            doc,
                            block,
                            at,
                            doc.blockName(at),
                            block.getCompound("nbt"),
                            captured,
                            paired);
            for (var provider : PatchSourceOperationsDispatch.captureGuards())
                provider.requireCapture(entry);
        }
    }
}
