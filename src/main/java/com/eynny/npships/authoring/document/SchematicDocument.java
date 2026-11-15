package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.EntityPatches;
import com.eynny.npships.authoring.validation.JsonStructure;
import com.eynny.npships.ship.SchematicRarityMetadata;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validated authoring data. Loading it never constructs entities or places blocks. */
public final class SchematicDocument {
    boolean hasMetadata(String key) {
        return original.contains(key);
    }

    CompoundTag metadataCompound(String key) {
        return original.getCompound(key).copy();
    }

    /** Package-local immutable inspection; never returned across the extension boundary. */
    CompoundTag metadataReadOnly(String key) {
        return original.getCompound(key);
    }

    CompoundTag extensionBlockState(BlockPos position) {
        Integer index = blocks.get(position);
        if (index == null) {
            var air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            return air;
        }
        return palette.getCompound(index).copy();
    }

    /** Immutable package-local native data; never crosses a provider callback boundary. */
    CompoundTag extensionBlockEntityReadOnly(BlockPos position) {
        var entry = entries.get(position);
        return entry == null ? new CompoundTag() : entry.getCompound("nbt");
    }

    ListTag extensionEntitiesReadOnly() {
        return original.getList("entities", Tag.TAG_COMPOUND);
    }

    /**
     * Internal extension envelope replacement; immutable geometry and unrelated metadata share
     * ownership.
     */
    SchematicDocument withExtensionMetadata(CompoundTag envelope) {
        if (metadataReadOnly(SchematicExtensions.KEY).equals(envelope)) return this;
        return new SchematicDocument(this, envelope);
    }

    private static CompoundTag extensionShallow(CompoundTag source) {
        var result = new CompoundTag();
        for (var key : source.getAllKeys()) result.put(key, source.get(key));
        return result;
    }

    private SchematicDocument(SchematicDocument source, CompoundTag envelope) {
        patchValidation = source.patchValidation;
        original = new CompoundTag();
        for (var key : source.original.getAllKeys()) original.put(key, source.original.get(key));
        if (envelope.isEmpty()) original.remove(SchematicExtensions.KEY);
        else original.put(SchematicExtensions.KEY, envelope);
        if (original.contains(BlockAreaLayers.KEY, Tag.TAG_COMPOUND)) {
            var areas = extensionShallow(original.getCompound(BlockAreaLayers.KEY));
            var packed = extensionShallow(areas.getCompound("base"));
            boolean compact = packed.contains("metadata", Tag.TAG_COMPOUND);
            var metadata = compact ? extensionShallow(packed.getCompound("metadata")) : packed;
            if (envelope.isEmpty()) metadata.remove(SchematicExtensions.KEY);
            else metadata.put(SchematicExtensions.KEY, envelope);
            if (compact) packed.put("metadata", metadata);
            areas.put("base", packed);
            original.put(BlockAreaLayers.KEY, areas);
        }
        metadataFingerprint = metadataHash(original);
        config = source.config;
        size = source.size;
        blocks = source.blocks;
        entries = source.entries;
        palette = source.palette;
        blockFingerprint = source.blockFingerprint;
        locallyEdited = true;
        editParent = new java.lang.ref.WeakReference<>(source);
        localChanges = java.util.Set.of();
        embeddedPositions = source.embeddedPositions;
    }

    public static final int MAX_BLOCKS = 1_000_000;
    public static final int MAX_SIDE = 512;
    private volatile SchematicRarityMetadata rarityMetadata;

    /** Derived presentation data, excluded from equality/history/serialization. */
    public synchronized SchematicRarityMetadata rarityMetadata() {
        if (rarityMetadata == null) refreshRarityMetadata();
        return rarityMetadata;
    }

    /** Explicit Recalculate/Save reads the effective serialized root, not hidden layer sources. */
    public synchronized SchematicRarityMetadata refreshRarityMetadata() {
        rarityMetadata =
                SchematicRarityMetadata.inspect(
                        nbtCopy(),
                        StructureSegments.read(config),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup());
        return rarityMetadata;
    }

    private final CompoundTag original;
    private final JsonObject config;
    private final BlockPos size;
    private final PagedBlockMap<Integer> blocks;
    private final PagedBlockMap<CompoundTag> entries;
    private final ListTag palette;
    private final long blockFingerprint;
    private final int metadataFingerprint;
    private final boolean locallyEdited;
    private final java.lang.ref.WeakReference<SchematicDocument> editParent;
    private final java.util.Set<BlockPos> localChanges;
    private final java.util.Set<BlockPos> embeddedPositions;

    // Editor-only context; never serialized or retained by a world/catalogue.
    private java.util.function.UnaryOperator<SchematicDocument> patchValidation;

    public SchematicDocument withPatchValidation(
            java.util.function.UnaryOperator<SchematicDocument> projection) {
        if (patchValidation == projection) return this;
        var copy = new SchematicDocument(this, config);
        copy.patchValidation = projection;
        return copy;
    }

    boolean hasPatchValidation() {
        return patchValidation != null;
    }

    public SchematicDocument patchValidationDocument() {
        return patchValidation == null ? this : patchValidation.apply(this);
    }

    public SchematicDocument inheritPatchValidation(SchematicDocument source) {
        return withPatchValidation(source.patchValidation);
    }

    /**
     * Transfer an already private, admitted runtime NBT graph. The caller must stop mutating every
     * reachable mutable tag at entry. Public authoring constructors retain their defensive copies.
     * Runtime config is null, as in the existing PatchedTemplate source-document construction.
     */
    public static RuntimeRead beginOwnedRuntime(
            CompoundTag owned, java.util.function.LongConsumer reserve) {
        return new RuntimeRead(owned, reserve);
    }

    /** Publishes only fully validated/indexed immutable data; all work remains attempt-owned. */
    public static final class RuntimeRead implements AutoCloseable {
        private enum Phase {
            SAFETY,
            STRUCTURE,
            BLOCK,
            VALIDATORS,
            BLOCK_HASH,
            BLOCK_FINISH,
            METADATA,
            META_KEY_HASH,
            META_VALUE,
            META_VALUE_HASH,
            FINISH,
            DONE
        }

        private CompoundTag owned, currentEntry;
        private ListTag palette, sourceEntries;
        private BlockPos size, currentPosition;
        private PagedBlockMap.Builder<Integer> states;
        private PagedBlockMap.Builder<CompoundTag> entries;
        private java.util.Set<BlockPos> embedded;
        private final java.util.function.LongConsumer reserve;
        private com.eynny.npships.authoring.SchematicSafety.RuntimeValidation safety;
        private NbtHashCursor hash;
        private final java.util.List<com.eynny.npships.authoring.patches.PatchSourceOperations>
                validators =
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all();
        private java.util.Iterator<String> metadataKeys;
        private String metadataKey;
        private int entryIndex,
                currentState,
                validatorIndex,
                currentHash,
                metadataKeyHash,
                metadataFingerprint;
        private long blockFingerprint, progress;
        private Phase phase = Phase.SAFETY;
        private SchematicDocument result;
        private boolean closed, failed;

        private RuntimeRead(CompoundTag owned, java.util.function.LongConsumer reserve) {
            this.owned = Objects.requireNonNull(owned);
            this.reserve = Objects.requireNonNull(reserve);
        }

        /**
         * The allowance is checked before every node/edge, bounded hash portion, record or
         * callback.
         */
        public boolean step(java.util.function.BooleanSupplier allowed) {
            requireOpen();
            Objects.requireNonNull(allowed);
            try {
                while (phase != Phase.DONE) {
                    if (safety != null) {
                        long before = safety.progress();
                        boolean done = safety.step(allowed);
                        progress += safety.progress() - before;
                        if (!done) return false;
                        safety.close();
                        safety = null;
                        phase = Phase.STRUCTURE;
                    } else if (hash != null) {
                        long before = hash.progress();
                        boolean done = hash.step(allowed);
                        progress += hash.progress() - before;
                        if (!done) return false;
                        int value = hash.result();
                        hash.close();
                        hash = null;
                        if (phase == Phase.BLOCK_HASH) {
                            currentHash = value;
                            phase = Phase.BLOCK_FINISH;
                        } else if (phase == Phase.META_KEY_HASH) {
                            metadataKeyHash = value;
                            phase = Phase.META_VALUE;
                        } else {
                            metadataFingerprint += metadataKeyHash ^ value;
                            metadataKey = null;
                            phase = Phase.METADATA;
                        }
                    } else {
                        if (!allowed.getAsBoolean()) return false;
                        one();
                        progress++;
                    }
                }
                return true;
            } catch (RuntimeException | Error error) {
                failed = true;
                clear();
                throw error;
            }
        }

        private void one() {
            switch (phase) {
                case SAFETY -> {
                    reserve.accept(
                            com.eynny.npships.authoring.SchematicSafety.RuntimeValidation
                                    .WORKING_BYTES);
                    safety =
                            com.eynny.npships.authoring.SchematicSafety.beginRuntimeValidation(
                                    owned);
                }
                case STRUCTURE -> {
                    var dimensions = owned.getList("size", Tag.TAG_INT);
                    if (dimensions.size() != 3)
                        throw new IllegalArgumentException(
                                "NBT: size must contain three integers.");
                    size =
                            new BlockPos(
                                    dimensions.getInt(0),
                                    dimensions.getInt(1),
                                    dimensions.getInt(2));
                    if (size.getX() < 1
                            || size.getY() < 1
                            || size.getZ() < 1
                            || size.getX() > MAX_SIDE
                            || size.getY() > MAX_SIDE
                            || size.getZ() > MAX_SIDE)
                        throw new IllegalArgumentException(
                                "Preview dimensions must be within 1.."
                                        + MAX_SIDE
                                        + " on each axis.");
                    palette =
                            owned.contains("palette", Tag.TAG_LIST)
                                    ? owned.getList("palette", Tag.TAG_COMPOUND)
                                    : owned.getList("palettes", Tag.TAG_LIST).getList(0);
                    if (palette.isEmpty())
                        throw new IllegalArgumentException("NBT: missing block palette.");
                    sourceEntries = owned.getList("blocks", Tag.TAG_COMPOUND);
                    if (sourceEntries.size() > MAX_BLOCKS)
                        throw new IllegalArgumentException(
                                "Preview limit: " + MAX_BLOCKS + " block entries.");
                    reserve.accept(2 * PagedBlockMap.BUILDER_BYTES + 65L * 12 * Long.BYTES);
                    states = new PagedBlockMap.Builder<>();
                    entries = new PagedBlockMap.Builder<>();
                    embedded = new java.util.HashSet<>();
                    phase = Phase.BLOCK;
                }
                case BLOCK -> {
                    if (entryIndex == sourceEntries.size()) {
                        metadataKeys = owned.getAllKeys().iterator();
                        phase = Phase.METADATA;
                        return;
                    }
                    currentEntry = sourceEntries.getCompound(entryIndex);
                    var coordinates = currentEntry.getList("pos", Tag.TAG_INT);
                    if (coordinates.size() != 3 || !currentEntry.contains("state", Tag.TAG_INT))
                        throw new IllegalArgumentException(
                                "NBT: invalid block entry " + entryIndex);
                    // Admission precedes retained position/index/set capacity growth.
                    reserve.accept(16L * Long.BYTES);
                    currentPosition =
                            new BlockPos(
                                    coordinates.getInt(0),
                                    coordinates.getInt(1),
                                    coordinates.getInt(2));
                    currentState = currentEntry.getInt("state");
                    if (currentPosition.getX() < 0
                            || currentPosition.getY() < 0
                            || currentPosition.getZ() < 0
                            || currentPosition.getX() >= size.getX()
                            || currentPosition.getY() >= size.getY()
                            || currentPosition.getZ() >= size.getZ()
                            || currentState < 0
                            || currentState >= palette.size())
                        throw new IllegalArgumentException(
                                "NBT: block outside bounds or invalid palette index at "
                                        + currentPosition);
                    if (states.get(currentPosition) != null)
                        throw new IllegalArgumentException(
                                "NBT: duplicate block at " + currentPosition);
                    validatorIndex = 0;
                    phase = Phase.VALIDATORS;
                }
                case VALIDATORS -> {
                    if (validatorIndex < validators.size())
                        validators
                                .get(validatorIndex++)
                                .validateNativeBlock(
                                        palette.getCompound(currentState).getString("Name"),
                                        currentEntry.getCompound("nbt"));
                    else {
                        hash = new NbtHashCursor(currentEntry);
                        phase = Phase.BLOCK_HASH;
                    }
                }
                case BLOCK_FINISH -> {
                    states.put(currentPosition, currentState);
                    entries.put(currentPosition, currentEntry);
                    blockFingerprint += fingerprint(currentPosition, currentHash);
                    if (currentEntry.contains(
                            com.eynny.npships.authoring.patches.SchematicPatches.EMBEDDED))
                        embedded.add(currentPosition);
                    currentEntry = null;
                    currentPosition = null;
                    entryIndex++;
                    phase = Phase.BLOCK;
                }
                case METADATA -> {
                    if (!metadataKeys.hasNext()) {
                        phase = Phase.FINISH;
                        return;
                    }
                    metadataKey = metadataKeys.next();
                    if (metadataKey.equals("blocks")
                            || metadataKey.equals("palette")
                            || metadataKey.equals("palettes")) return;
                    hash = new NbtHashCursor(net.minecraft.nbt.StringTag.valueOf(metadataKey));
                    phase = Phase.META_KEY_HASH;
                }
                case META_VALUE -> {
                    hash = new NbtHashCursor(owned.get(metadataKey));
                    phase = Phase.META_VALUE_HASH;
                }
                case FINISH -> {
                    result = new SchematicDocument(this);
                    phase = Phase.DONE;
                }
                default ->
                        throw new IllegalStateException(
                                "Runtime document phase lost its continuation");
            }
        }

        public SchematicDocument result() {
            requireOpen();
            if (phase != Phase.DONE)
                throw new IllegalStateException("Runtime document is unfinished");
            return result;
        }

        public long progress() {
            return progress;
        }

        private void requireOpen() {
            if (closed || failed)
                throw new IllegalStateException("Runtime document is closed or failed");
        }

        private void clear() {
            if (safety != null) safety.close();
            if (hash != null) hash.close();
            safety = null;
            hash = null;
            owned = null;
            palette = null;
            sourceEntries = null;
            currentEntry = null;
            currentPosition = null;
            metadataKeys = null;
            metadataKey = null;
            if (states != null) states.clear();
            if (entries != null) entries.clear();
            states = null;
            entries = null;
            embedded = null;
            result = null;
        }

        /** Drops cursor references only; the caller owns reservations and any returned document. */
        @Override
        public void close() {
            closed = true;
            clear();
        }
    }

    private SchematicDocument(RuntimeRead read) {
        original = read.owned;
        config = null;
        size = read.size;
        palette = read.palette;
        blocks = read.states.finish();
        entries = read.entries.finish();
        blockFingerprint = read.blockFingerprint;
        metadataFingerprint = read.metadataFingerprint;
        embeddedPositions = java.util.Collections.unmodifiableSet(read.embedded);
        locallyEdited = false;
        editParent = null;
        localChanges = java.util.Set.of();
    }

    public SchematicDocument(final CompoundTag original, final JsonObject config) {
        com.eynny.npships.authoring.SchematicSafety.requirePortableSubLevels(original);
        this.original = original.copy();
        this.config = JsonStructure.copy(config);
        var dimensions = original.getList("size", Tag.TAG_INT);
        if (dimensions.size() != 3)
            throw new IllegalArgumentException("NBT: size must contain three integers.");
        size = new BlockPos(dimensions.getInt(0), dimensions.getInt(1), dimensions.getInt(2));
        if (size.getX() < 1
                || size.getY() < 1
                || size.getZ() < 1
                || size.getX() > MAX_SIDE
                || size.getY() > MAX_SIDE
                || size.getZ() > MAX_SIDE) {
            throw new IllegalArgumentException(
                    "Preview dimensions must be within 1.." + MAX_SIDE + " on each axis.");
        }
        // Vanilla multi-palette templates preview their first palette. Preserve every palette in
        // original.
        palette =
                (original.contains("palette", Tag.TAG_LIST)
                                ? original.getList("palette", Tag.TAG_COMPOUND)
                                : original.getList("palettes", Tag.TAG_LIST).getList(0))
                        .copy();
        if (palette.isEmpty()) throw new IllegalArgumentException("NBT: missing block palette.");
        var entries = this.original.getList("blocks", Tag.TAG_COMPOUND);
        if (entries.size() > MAX_BLOCKS)
            throw new IllegalArgumentException("Preview limit: " + MAX_BLOCKS + " block entries.");
        var positions = new LinkedHashMap<BlockPos, Integer>();
        var indexedEntries = new LinkedHashMap<BlockPos, CompoundTag>();
        long fingerprint = 0;
        var embedded = new java.util.HashSet<BlockPos>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            var position = entry.getList("pos", Tag.TAG_INT);
            if (position.size() != 3 || !entry.contains("state", Tag.TAG_INT)) {
                throw new IllegalArgumentException("NBT: invalid block entry " + i);
            }
            var pos = new BlockPos(position.getInt(0), position.getInt(1), position.getInt(2));
            int state = entry.getInt("state");
            if (!contains(pos) || state < 0 || state >= palette.size()) {
                throw new IllegalArgumentException(
                        "NBT: block outside bounds or invalid palette index at " + pos);
            }
            if (positions.put(pos, state) != null)
                throw new IllegalArgumentException("NBT: duplicate block at " + pos);
            for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                code.validateNativeBlock(
                        palette.getCompound(state).getString("Name"), entry.getCompound("nbt"));
            var retained = entry;
            indexedEntries.put(pos, retained);
            fingerprint += fingerprint(pos, retained);
            if (retained.contains(com.eynny.npships.authoring.patches.SchematicPatches.EMBEDDED))
                embedded.add(pos);
        }
        blocks = new PagedBlockMap<>(positions);
        this.entries = new PagedBlockMap<>(indexedEntries);
        blockFingerprint = fingerprint;
        locallyEdited = false;
        editParent = null;
        localChanges = java.util.Set.of();
        metadataFingerprint = metadataHash(this.original);
        embeddedPositions = java.util.Collections.unmodifiableSet(embedded);
    }

    private SchematicDocument(
            SchematicDocument source,
            Map<BlockPos, CompoundTag> changes,
            ListTag palette,
            JsonObject config) {
        patchValidation = source.patchValidation;
        original = source.original;
        metadataFingerprint = source.metadataFingerprint;
        size = source.size;
        this.config = JsonStructure.copy(config);
        this.palette = palette.copy();
        var states = new java.util.HashMap<BlockPos, Integer>();
        var clean = new java.util.HashMap<BlockPos, CompoundTag>();
        long fingerprint = source.blockFingerprint;
        for (var e : changes.entrySet()) {
            var p = e.getKey().immutable();
            var value = e.getValue();
            if (!contains(p)) throw new IllegalArgumentException("Block outside schematic: " + p);
            if (value != null) {
                var coords = value.getList("pos", Tag.TAG_INT);
                if (coords.size() != 3
                        || coords.getInt(0) != p.getX()
                        || coords.getInt(1) != p.getY()
                        || coords.getInt(2) != p.getZ())
                    throw new IllegalArgumentException("Block entry position does not match " + p);
                if (!value.contains("state", Tag.TAG_INT)
                        || value.getInt("state") < 0
                        || value.getInt("state") >= palette.size())
                    throw new IllegalArgumentException("Invalid palette index at " + p);
            }
            var previous = source.entries.get(p);
            if (java.util.Objects.equals(previous, value)) continue;
            if (previous != null) fingerprint -= fingerprint(p, previous);
            if (value != null) fingerprint += fingerprint(p, value);
            clean.put(p, value == null ? null : value.copy());
            states.put(p, value == null ? null : value.getInt("state"));
        }
        entries = source.entries.updated(clean);
        blocks = source.blocks.updated(states);
        if (blocks.size() > MAX_BLOCKS)
            throw new IllegalArgumentException("Preview limit: " + MAX_BLOCKS + " block entries.");
        blockFingerprint = fingerprint;
        locallyEdited = true;
        editParent = new java.lang.ref.WeakReference<>(source);
        localChanges =
                java.util.Collections.unmodifiableSet(new java.util.HashSet<>(clean.keySet()));
        var embedded = new java.util.HashSet<>(source.embeddedPositions);
        for (var e : clean.entrySet()) {
            if (e.getValue() != null
                    && e.getValue()
                            .contains(
                                    com.eynny.npships.authoring.patches.SchematicPatches.EMBEDDED))
                embedded.add(e.getKey());
            else embedded.remove(e.getKey());
        }
        embeddedPositions = java.util.Collections.unmodifiableSet(embedded);
    }

    private static long fingerprint(BlockPos p, CompoundTag entry) {
        return fingerprint(p, entry.hashCode());
    }

    private static long fingerprint(BlockPos p, int entryHash) {
        long x = ((long) p.hashCode() << 32) ^ (entryHash & 0xffffffffL);
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static int metadataHash(CompoundTag tag) {
        int hash = 0;
        for (var key : tag.getAllKeys()) {
            if (!key.equals("blocks") && !key.equals("palette") && !key.equals("palettes"))
                hash += key.hashCode() ^ tag.get(key).hashCode();
        }
        return hash;
    }

    private SchematicDocument(SchematicDocument source, ListTag entities) {
        patchValidation = source.patchValidation;
        original = new CompoundTag();
        for (var key : source.original.getAllKeys()) original.put(key, source.original.get(key));
        original.put("entities", entities.copy());
        metadataFingerprint = metadataHash(original);
        config = source.config;
        size = source.size;
        blocks = source.blocks;
        entries = source.entries;
        palette = source.palette;
        blockFingerprint = source.blockFingerprint;
        locallyEdited = true;
        editParent = new java.lang.ref.WeakReference<>(source);
        localChanges = java.util.Set.of();
        embeddedPositions = source.embeddedPositions;
    }

    /**
     * Caller owns the input tags; this method defensively copies only changed entries. Null removes
     * a block.
     */
    public SchematicDocument withBlocks(
            Map<BlockPos, CompoundTag> changes, ListTag nextPalette, JsonObject nextConfig) {
        var next = new SchematicDocument(this, changes, nextPalette, nextConfig);
        return next.localChanges.isEmpty()
                        && palette.equals(nextPalette)
                        && java.util.Objects.equals(config, nextConfig)
                ? this
                : next;
    }

    /** Internal immutable capture inspection, never returned to addon code. */
    CompoundTag extensionBlockEntryReadOnly(BlockPos pos) {
        var entry = entries.get(pos);
        return entry == null ? new CompoundTag() : entry;
    }

    public CompoundTag blockEntryCopy(BlockPos pos) {
        var value = entries.get(pos);
        return value == null ? null : value.copy();
    }

    /** Immutable native-record metadata inspection without copying its block entity NBT. */
    public String blockMetadataString(BlockPos pos, String key) {
        var value = entries.get(pos);
        return value == null ? "" : value.getString(key);
    }

    public SchematicDocument withConfig(JsonObject nextConfig) {
        return java.util.Objects.equals(config, nextConfig)
                ? this
                : new SchematicDocument(this, JsonStructure.copy(nextConfig));
    }

    /** Metadata-only version. The supplied config is privately owned; all geometry is immutable. */
    private SchematicDocument(SchematicDocument source, JsonObject ownedConfig) {
        patchValidation = source.patchValidation;
        original = source.original;
        config = ownedConfig;
        size = source.size;
        blocks = source.blocks;
        entries = source.entries;
        palette = source.palette;
        blockFingerprint = source.blockFingerprint;
        metadataFingerprint = source.metadataFingerprint;
        locallyEdited = source.locallyEdited;
        editParent = new java.lang.ref.WeakReference<>(source);
        localChanges = java.util.Set.of();
        embeddedPositions = source.embeddedPositions;
        if (java.util.Objects.equals(
                source.config == null ? null : source.config.get("segments"),
                ownedConfig == null ? null : ownedConfig.get("segments")))
            rarityMetadata = source.rarityMetadata;
    }

    /** Distinct identity for undoable layer metadata; block data remains shared and immutable. */
    public SchematicDocument historySnapshot() {
        return new SchematicDocument(this, config);
    }

    /** Compare immutable entity tags without allocating a defensive copy for every block edit. */
    public boolean sameEntities(SchematicDocument other) {
        if (other == null) return false;
        var mine = original.getList("entities", Tag.TAG_COMPOUND);
        var theirs = other.original.getList("entities", Tag.TAG_COMPOUND);
        return mine == theirs || mine.equals(theirs);
    }

    public SchematicDocument withEntities(ListTag entities) {
        return original.getList("entities", Tag.TAG_COMPOUND).equals(entities)
                ? this
                : EntityPatches.afterEntities(this, new SchematicDocument(this, entities));
    }

    public java.util.List<JsonObject> templatePatches() {
        var list = new java.util.ArrayList<JsonObject>();
        for (var p : embeddedPositions) {
            for (var patch :
                    com.eynny.npships.authoring.patches.SchematicPatches.embeddedEntries(
                            entries.get(p))) {
                patch.add("pos", com.eynny.npships.authoring.patches.SchematicPatches.vector(p));
                list.add(patch);
            }
        }
        return list;
    }

    public byte[] entityPatchBytes() {
        return original.getByteArray(EntityPatches.EMBEDDED).clone();
    }

    public ListTag entitiesCopy() {
        return original.getList("entities", Tag.TAG_COMPOUND).copy();
    }

    public SchematicDocument localParent() {
        return editParent == null ? null : editParent.get();
    }

    public java.util.Set<BlockPos> localChanges() {
        return localChanges;
    }

    public boolean sameContents(SchematicDocument other) {
        if (this == other) return true;
        if (other == null
                || blockFingerprint != other.blockFingerprint
                || metadataFingerprint != other.metadataFingerprint
                || !size.equals(other.size)
                || !java.util.Objects.equals(config, other.config)) return false;
        if (original == other.original && entries == other.entries && palette.equals(other.palette))
            return true;
        // Comparing immutable entries does not allocate two complete copies of a large NBT.
        return entries.equals(other.entries) && metadataCopy().equals(other.metadataCopy());
    }

    /** A bounded diff for local render invalidation; null requests a complete rebuild. */
    public java.util.Set<BlockPos> changedBlocks(SchematicDocument other, int limit) {
        if (other == null || !size.equals(other.size)) return null;
        // Config/entity-only versions may outlive the bounded weak parent chain.
        // Shared immutable entries and palette prove that no block mesh changed.
        if (limit >= 0 && entries == other.entries && palette == other.palette)
            return java.util.Set.of();
        var changed = new java.util.HashSet<BlockPos>();
        var cursor = this;
        for (int i = 0; i < 64 && cursor != null; i++, cursor = cursor.localParent()) {
            if (cursor == other) return changed.size() <= limit ? changed : null;
            changed.addAll(cursor.localChanges);
            if (changed.size() > limit) return null;
        }
        changed.clear();
        for (var p : blocks.keySet()) {
            var a = blocks.get(p);
            var b = other.blocks.get(p);
            if (b == null
                    || !palette.getCompound(a).equals(other.palette.getCompound(b))
                    || !java.util.Objects.equals(entries.get(p), other.entries.get(p)))
                changed.add(p);
            if (changed.size() > limit) return null;
        }
        for (var p : other.blocks.keySet())
            if (!blocks.containsKey(p)) {
                changed.add(p);
                if (changed.size() > limit) return null;
            }
        return changed;
    }

    public static final long NBT_MEMORY_LIMIT = 512L * 1024 * 1024;

    public int entityCount() {
        return original.getList("entities", Tag.TAG_COMPOUND).size();
    }

    /** Compare one complete immutable entity record without copying the other entities. */
    public boolean sameEntityAt(SchematicDocument other, int index) {
        return other != null
                && index >= 0
                && index < entityCount()
                && index < other.entityCount()
                && original.getList("entities", Tag.TAG_COMPOUND)
                        .getCompound(index)
                        .equals(
                                other.original
                                        .getList("entities", Tag.TAG_COMPOUND)
                                        .getCompound(index));
    }

    /** Compare one immutable native block record and state without copying either palette. */
    public boolean sameBlockAt(SchematicDocument other, BlockPos position) {
        if (other == null) return false;
        var before = blocks.get(position);
        var after = other.blocks.get(position);
        return Objects.equals(entries.get(position), other.entries.get(position))
                && (before == null
                        ? after == null
                        : after != null
                                && palette.getCompound(before)
                                        .equals(other.palette.getCompound(after)));
    }

    public BlockPos size() {
        return size;
    }

    public Map<BlockPos, Integer> blocks() {
        return blocks;
    }

    public String blockName(BlockPos pos) {
        var index = blocks.get(pos);
        return index == null ? "minecraft:air" : palette.getCompound(index).getString("Name");
    }

    /** Read one immutable property without copying the entire palette. */
    public String blockProperty(BlockPos pos, String property) {
        var index = blocks.get(pos);
        return index == null
                ? ""
                : palette.getCompound(index).getCompound("Properties").getString(property);
    }

    public ListTag paletteCopy() {
        return palette.copy();
    }

    /** Template metadata without block entries, for small layers and config/entity edits. */
    public CompoundTag metadataCopy() {
        var tag = new CompoundTag();
        for (var key : original.getAllKeys())
            if (!key.equals("blocks") && !key.equals("palette") && !key.equals("palettes"))
                tag.put(key, original.get(key).copy());
        if (hasMultiplePalettes()) tag.put("palettes", original.get("palettes").copy());
        else tag.put("palette", palette.copy());
        return tag;
    }

    public CompoundTag extraCompoundCopy(String key) {
        return original.getCompound(key).copy();
    }

    /** Independently owned variant document without copying the parent or sibling templates. */
    public SchematicDocument variantDocument(String id) {
        return new SchematicDocument(
                original.getCompound(StructureSegments.VARIANTS).getCompound(id), null);
    }

    /** Incremental equivalent of nbtCopy, charged as a new mutable composition input. */
    public RuntimeCopy beginRuntimeCopy(
            com.eynny.npships.resources.PreparedTemplateResources resources) {
        return new RuntimeCopy(this, resources);
    }

    public static final class RuntimeCopy implements AutoCloseable {
        private SchematicDocument document;
        private final com.eynny.npships.resources.PreparedTemplateResources resources;
        private com.eynny.npships.resources.PreparedTemplateResources.Copy<CompoundTag> copy;
        private CompoundTag envelope, result;
        private ListTag blocks;
        private java.util.Iterator<String> keys;
        private java.util.Iterator<CompoundTag> entries;
        private int phase;
        private long progress;
        private boolean closed, failed;

        private RuntimeCopy(
                SchematicDocument document,
                com.eynny.npships.resources.PreparedTemplateResources resources) {
            this.document = document;
            this.resources = Objects.requireNonNull(resources);
        }

        public boolean step(java.util.function.BooleanSupplier allowed) {
            requireOpen();
            Objects.requireNonNull(allowed);
            try {
                while (phase != 4) {
                    if (copy != null) {
                        long before = copy.progress();
                        boolean done = copy.step(allowed);
                        progress += copy.progress() - before;
                        if (!done) return false;
                        result = copy.result();
                        copy.close();
                        copy = null;
                        envelope = null;
                        blocks = null;
                        keys = null;
                        entries = null;
                        document = null;
                        phase = 4;
                    } else {
                        if (!allowed.getAsBoolean()) return false;
                        one();
                        progress++;
                    }
                }
                return true;
            } catch (RuntimeException | Error error) {
                failed = true;
                clear();
                throw error;
            }
        }

        private void one() {
            if (phase == 0) {
                if (!document.locallyEdited) {
                    copy = resources.copyInputIncrementally(document.original);
                    return;
                }
                // This temporary reference envelope is charged conservatively until the owner's
                // resource token retires; it never copies or exposes immutable document payloads.
                resources.reserveCapacity(
                        Math.addExact(
                                Math.multiplyExact(
                                        (long) document.original.size() + 2, 2L * Long.BYTES),
                                com.eynny.npships.resources.ResourceMemoryBudget.arrayBytes(
                                        document.entries.size(), Long.BYTES)));
                envelope = new CompoundTag();
                blocks = new ListTag(document.entries.size());
                keys = document.original.getAllKeys().iterator();
                phase = 1;
            } else if (phase == 1) {
                if (keys.hasNext()) {
                    var key = keys.next();
                    if (!key.equals("blocks") && !key.equals("palette") && !key.equals("palettes"))
                        envelope.put(key, document.original.get(key));
                } else {
                    if (document.hasMultiplePalettes())
                        envelope.put("palettes", document.original.get("palettes"));
                    else envelope.put("palette", document.palette);
                    entries = document.entries.values().iterator();
                    phase = 2;
                }
            } else if (phase == 2) {
                if (entries.hasNext()) blocks.add(entries.next());
                else {
                    envelope.put("blocks", blocks);
                    phase = 3;
                }
            } else {
                copy = resources.copyInputIncrementally(envelope);
            }
        }

        public CompoundTag result() {
            requireOpen();
            if (phase != 4) throw new IllegalStateException("Runtime document copy is unfinished");
            return result;
        }

        public long progress() {
            return progress;
        }

        private void requireOpen() {
            if (closed || failed)
                throw new IllegalStateException("Runtime document copy is closed or failed");
        }

        private void clear() {
            if (copy != null) copy.close();
            copy = null;
            document = null;
            envelope = null;
            result = null;
            blocks = null;
            keys = null;
            entries = null;
        }

        @Override
        public void close() {
            closed = true;
            clear();
        }
    }

    public CompoundTag nbtCopy() {
        if (!locallyEdited) return original.copy();
        var tag = metadataCopy();
        var list = new ListTag();
        for (var entry : entries.values()) list.add(entry.copy());
        tag.put("blocks", list);
        return tag;
    }

    public JsonObject configCopy() {
        return config == null ? null : config.deepCopy();
    }

    /** Scalar schema lookup does not copy the complete immutable author configuration. */
    public int patchSchema(boolean entity) {
        String key = entity ? EntityPatches.KEY : "patches";
        if (config == null || !config.has(key) || !config.get(key).isJsonObject()) return 0;
        return com.eynny.npships.authoring.validation.JsonNumbers.integer(
                config.getAsJsonObject(key).get("version"), key);
    }

    public boolean hasConfig() {
        return config != null;
    }

    public CompoundTag blockEntityData(final BlockPos pos) {
        var entry = entries.get(pos);
        var nbt = entry == null ? null : entry.getCompound("nbt");
        return nbt == null ? new CompoundTag() : nbt.copy();
    }

    /** Reads a native scalar without copying palette, BE payload or exposing owned mutable NBT. */
    public boolean blockEntityBoolean(BlockPos pos, String compound, String key) {
        var entry = entries.get(pos);
        if (entry == null || !(entry.get("nbt") instanceof CompoundTag data)) return false;
        if (!compound.isEmpty()) {
            if (!(data.get(compound) instanceof CompoundTag nested)) return false;
            data = nested;
        }
        return data.getBoolean(key);
    }

    public boolean hasBlockEntityData(BlockPos pos) {
        var e = entries.get(pos);
        return e != null && e.contains("nbt", Tag.TAG_COMPOUND);
    }

    public String savedLootTable(BlockPos pos) {
        var data = entries.get(pos);
        return data == null ? "" : data.getCompound("nbt").getString("LootTable");
    }

    public boolean hasMultiplePalettes() {
        return original.getList("palettes", Tag.TAG_LIST).size() > 1;
    }

    public boolean contains(final BlockPos pos) {
        return pos.getX() >= 0
                && pos.getY() >= 0
                && pos.getZ() >= 0
                && pos.getX() < size.getX()
                && pos.getY() < size.getY()
                && pos.getZ() < size.getZ();
    }
}
