package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchIdentity;
import com.eynny.npships.api.patches.PatchPreparedInput;
import com.eynny.npships.api.patches.PatchPreparedInput.Block;
import com.eynny.npships.api.patches.PatchPreparedInput.Claim;
import com.eynny.npships.api.patches.PatchPreparedInput.Root;
import com.eynny.npships.api.patches.PatchPreparedInput.Target;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceMemoryBudget;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Lazy one-template prepared graph/claim/ephemeral-record ownership; no world access. */
final class PreparedPatchData implements AutoCloseable {
    private static final Set<String> BLOCK_RESERVED =
            Set.of(
                    "id",
                    "x",
                    "y",
                    "z",
                    "keepPacked",
                    "components",
                    "NeoForgeData",
                    "ForgeData",
                    "ForgeCaps");
    private static final Set<String> ROOT_RESERVED =
            Set.of(
                    "id",
                    "UUID",
                    "UUIDMost",
                    "UUIDLeast",
                    "Pos",
                    "Motion",
                    "Rotation",
                    "Passengers",
                    "RootVehicle",
                    "Vehicle",
                    "Dimension",
                    "Leash",
                    "LeashData",
                    "TileX",
                    "TileY",
                    "TileZ",
                    "Facing",
                    "Direction",
                    "AttachFace",
                    "NeoForgeData",
                    "ForgeData",
                    "ForgeCaps",
                    "components");

    private record Field(Target target, String key) {}

    private record Owned(PatchIdentity identity, ResourceLocation nativeType) {}

    private record Stored(CompoundTag value, long bytes) {}

    private record Change(
            Field field,
            @Nullable ResourceLocation record,
            @Nullable Tag replacement,
            long bytes) {}

    private CompoundTag source, prepared;
    private long indexBytes;
    private final PreparedTemplateResources resources;
    private final Thread thread = Thread.currentThread();
    private Map<Field, Owned> claims;
    private Map<Target, CompoundTag> preparedTargets;
    private Map<Target, Set<PatchIdentity>> targetOwners;
    private Map<PatchIdentity, Map<ResourceLocation, Stored>> records;
    private UUID revision = UUID.randomUUID();
    private boolean declarationsSealed, preparedFinished, closed, applying;

    PreparedPatchData(CompoundTag sourceReadOnly, PreparedTemplateResources resources) {
        source = Objects.requireNonNull(sourceReadOnly);
        this.resources =
                Objects.requireNonNull(
                        resources,
                        "Prepared-write capability requires the existing server-owned reservation token");
    }

    private void reserveIndex(long bytes) {
        resources.reserveCapacity(bytes);
        indexBytes = Math.addExact(indexBytes, bytes);
    }

    private void releaseIndexes() {
        resources.release(indexBytes);
        indexBytes = 0;
    }

    private void requireOpen() {
        if (closed || Thread.currentThread() != thread)
            throw new IllegalStateException("Prepared input owner closed or wrong thread");
    }

    private static void fieldAllowed(Target target, String field) {
        if (field.isEmpty()
                || field.startsWith("npships")
                || field.startsWith("NPShips")
                || (target instanceof Block ? BLOCK_RESERVED : ROOT_RESERVED).contains(field))
            throw new IllegalArgumentException("Reserved native/NPShips prepared field: " + field);
    }

    /**
     * Called for each enabled document binding before any handler is acquired. roots is the mapping
     * owner's frozen enrollment, not a token guessed from arbitrary provider payload.
     */
    void declare(
            PatchIdentity identity, Set<BlockPos> footprint, Set<UUID> roots, List<Claim> values) {
        try (var cursor = beginDeclaration(identity, footprint, roots, values)) {
            while (!cursor.step(() -> true)) {}
        }
    }

    Declaration beginDeclaration(
            PatchIdentity identity, Set<BlockPos> footprint, Set<UUID> roots, List<Claim> values) {
        requireOpen();
        if (declarationsSealed)
            throw new IllegalStateException("Prepared field claims already frozen");
        return new Declaration(identity, footprint, roots, values);
    }

    final class Declaration implements AutoCloseable {
        private final PatchIdentity identity;
        private final Set<BlockPos> footprint;
        private final Set<UUID> roots;
        private java.util.Iterator<Claim> input;
        private java.util.Iterator<String> fields;
        private Claim current;
        private Map<Field, Owned> proposed = new LinkedHashMap<>();
        private java.util.Iterator<Map.Entry<Field, Owned>> commit;
        private long progress;
        private boolean done, released;

        Declaration(
                PatchIdentity identity,
                Set<BlockPos> footprint,
                Set<UUID> roots,
                List<Claim> values) {
            this.identity = identity;
            this.footprint = footprint;
            this.roots = roots;
            input = values.iterator();
        }

        boolean step(java.util.function.BooleanSupplier allowed) {
            requireOpen();
            if (released) throw new IllegalStateException("Declaration cursor closed");
            while (!done && allowed.getAsBoolean()) {
                if (commit != null) {
                    if (commit.hasNext()) {
                        var entry = commit.next();
                        if (claims == null) claims = new LinkedHashMap<>();
                        claims.put(entry.getKey(), entry.getValue());
                    } else done = true;
                } else if (fields != null && fields.hasNext()) {
                    var key = fields.next();
                    var target = current.target();
                    fieldAllowed(target, key);
                    var field = new Field(target, key);
                    var owner = new Owned(identity, current.nativeType());
                    if (claims != null && claims.containsKey(field) || proposed.containsKey(field))
                        throw new IllegalArgumentException(
                                "Competing or duplicate prepared field claim: "
                                        + target
                                        + "/"
                                        + key);
                    reserveIndex(12L * Long.BYTES);
                    proposed.put(field, owner);
                } else if (input.hasNext()) {
                    current = input.next();
                    var target = current.target();
                    if (target instanceof Block block && !footprint.contains(block.position())
                            || target instanceof Root root && !roots.contains(root.token()))
                        throw new IllegalArgumentException(
                                "Prepared claim outside binding enrollment: " + target);
                    fields = current.fields().iterator();
                } else commit = proposed.entrySet().iterator();
                progress++;
            }
            return done;
        }

        long progress() {
            return progress;
        }

        public void close() {
            released = true;
            input = null;
            fields = null;
            current = null;
            proposed = null;
            commit = null;
        }
    }

    private TargetIndex declarationIndex, attachmentIndex;
    private java.util.Iterator<Map.Entry<Field, Owned>> declarationChecks, attachmentChecks;
    private CompoundTag attaching;
    private long sealedChecks, sealedIndex;

    long sealProgress() {
        return sealedChecks + (declarationIndex == null ? sealedIndex : declarationIndex.progress);
    }

    void sealDeclarations() {
        while (!sealDeclarationsStep(() -> true)) {}
    }

    boolean sealDeclarationsStep(java.util.function.BooleanSupplier allowed) {
        requireOpen();
        if (declarationsSealed) return true;
        if (declarationIndex == null) declarationIndex = new TargetIndex(source);
        if (!declarationIndex.step(allowed)) return false;
        if (declarationChecks == null) {
            declarationChecks =
                    claims == null
                            ? java.util.Collections.emptyIterator()
                            : claims.entrySet().iterator();
            if (claims != null) targetOwners = new HashMap<>();
        }
        while (allowed.getAsBoolean()) {
            if (!declarationChecks.hasNext()) {
                declarationsSealed = true;
                sealedIndex = declarationIndex.progress;
                source = null;
                declarationIndex = null;
                declarationChecks = null;
                return true;
            }
            sealedChecks++;
            var claim = declarationChecks.next();
            var data = declarationIndex.found.get(claim.getKey().target());
            if (data == null
                    || !claim.getValue().nativeType().toString().equals(data.getString("id")))
                throw new IllegalArgumentException(
                        "Prepared claim target/type unavailable: " + claim.getKey().target());
            reserveIndex(8L * Long.BYTES);
            targetOwners
                    .computeIfAbsent(claim.getKey().target(), ignored -> new java.util.HashSet<>())
                    .add(claim.getValue().identity());
        }
        return false;
    }

    /** Attach only the private prepared graph, after validating one target/claim at a time. */
    void attach(CompoundTag ownedPrepared) {
        while (!attachStep(ownedPrepared, () -> true)) {}
    }

    boolean attachStep(CompoundTag ownedPrepared, java.util.function.BooleanSupplier allowed) {
        requireOpen();
        if (prepared == ownedPrepared && !preparedFinished) return true;
        if (!declarationsSealed || preparedFinished || prepared != null)
            throw new IllegalStateException("Prepared graph transition is not available");
        if (attaching == null) {
            attaching = Objects.requireNonNull(ownedPrepared);
            attachmentIndex = new TargetIndex(attaching);
        }
        if (attaching != ownedPrepared)
            throw new IllegalStateException("Prepared graph changed during attachment");
        if (!attachmentIndex.step(allowed)) return false;
        if (attachmentChecks == null)
            attachmentChecks =
                    claims == null
                            ? java.util.Collections.emptyIterator()
                            : claims.entrySet().iterator();
        while (allowed.getAsBoolean()) {
            if (!attachmentChecks.hasNext()) {
                prepared = attaching;
                preparedTargets = attachmentIndex.found;
                attaching = null;
                attachmentIndex = null;
                attachmentChecks = null;
                return true;
            }
            var claim = attachmentChecks.next();
            var data = attachmentIndex.found.get(claim.getKey().target());
            if (data == null
                    || !data.getString("id").equals(claim.getValue().nativeType().toString()))
                throw new IllegalArgumentException(
                        "Prepared target changed type before declared edit");
        }
        return false;
    }

    private final class TargetIndex {
        final Map<Target, CompoundTag> found = new HashMap<>();
        final java.util.Set<BlockPos> wantedBlocks = new java.util.HashSet<>();
        final java.util.Set<UUID> wantedRoots = new java.util.HashSet<>();
        final CompoundTag graph;
        final java.util.Iterator<Field> fields =
                claims == null ? java.util.Collections.emptyIterator() : claims.keySet().iterator();
        int phase, index;
        long progress;

        TargetIndex(CompoundTag graph) {
            this.graph = graph;
        }

        boolean step(java.util.function.BooleanSupplier allowed) {
            while (phase < 3 && allowed.getAsBoolean()) {
                progress++;
                if (phase == 0) {
                    if (!fields.hasNext()) {
                        phase++;
                        continue;
                    }
                    var target = fields.next().target();
                    reserveIndex(8L * Long.BYTES);
                    if (target instanceof Block block) wantedBlocks.add(block.position());
                    else if (target instanceof Root root) wantedRoots.add(root.token());
                } else {
                    boolean blocks = phase == 1;
                    var list = graph.getList(blocks ? "blocks" : "entities", Tag.TAG_COMPOUND);
                    if ((blocks ? wantedBlocks.isEmpty() : wantedRoots.isEmpty())
                            || index == list.size()) {
                        phase++;
                        index = 0;
                        continue;
                    }
                    var entry = list.getCompound(index++);
                    CompoundTag nativeData;
                    Target target;
                    if (blocks) {
                        var position = SchematicPatches.blockPosition(entry);
                        if (!wantedBlocks.contains(position)
                                || !entry.contains("nbt", Tag.TAG_COMPOUND)) continue;
                        target = new Block(position);
                        nativeData = entry.getCompound("nbt");
                    } else {
                        nativeData = entry.getCompound("nbt");
                        var token = EntityIdentity.read(nativeData);
                        if (token == null || !wantedRoots.contains(token)) continue;
                        target = new Root(token);
                    }
                    reserveIndex(8L * Long.BYTES);
                    if (found.putIfAbsent(target, nativeData) != null)
                        throw new IllegalArgumentException("Ambiguous prepared target " + target);
                }
            }
            return phase == 3;
        }
    }

    private CompoundTag claimed(PatchIdentity identity, Target target, @Nullable String field) {
        requireOpen();
        if (prepared == null || preparedFinished)
            throw new IllegalStateException("Prepared native graph is not editable in this phase");
        if (claims == null) throw new IllegalArgumentException("No declared prepared field claims");
        if (field != null) {
            fieldAllowed(target, field);
            var owner = claims.get(new Field(target, field));
            if (owner == null || !owner.identity().equals(identity))
                throw new IllegalArgumentException(
                        "Prepared field is not owned by this binding instance");
        } else if (!targetOwners.getOrDefault(target, Set.of()).contains(identity))
            throw new IllegalArgumentException("Prepared target is not declared by this instance");
        return Objects.requireNonNull(preparedTargets.get(target), "Missing prepared target");
    }

    private static void recordName(PatchIdentity identity, ResourceLocation name) {
        if (!identity.type().getNamespace().equals(name.getNamespace()))
            throw new IllegalArgumentException(
                    "Ephemeral record name must use provider namespace: " + name);
    }

    /** Called by both prepared and final mapped capability wrappers; returns one reserved copy. */
    Optional<CompoundTag> record(PatchIdentity identity, ResourceLocation name) {
        requireOpen();
        recordName(identity, name);
        var own = records == null ? null : records.get(identity);
        var value = own == null ? null : own.get(name);
        return value == null ? Optional.empty() : Optional.of(copyNew(value.value()));
    }

    private <T extends Tag> T copyNew(T value) {
        // These values are already owned/admitted. Native copies share immutable StringTag/key
        // text; only newly copied mutable capacity is additional here. New writes use
        // reserveGrowth.
        long bytes = resources.reserveCopy(value);
        try {
            @SuppressWarnings("unchecked")
            var copy = (T) value.copy();
            return copy;
        } catch (RuntimeException | Error failure) {
            resources.release(bytes);
            throw failure;
        }
    }

    /** Each returned capability is closed in ExternalPatchHandlers' callback finally block. */
    PatchPreparedInput capability(PatchIdentity identity, Runnable guard) {
        requireOpen();
        return new PatchPreparedInput(
                new PatchPreparedInput.Access() {
                    private void require() {
                        guard.run();
                        requireOpen();
                        if (preparedFinished || prepared == null)
                            throw new IllegalStateException("PREPARE_FINALIZE input window ended");
                    }

                    public UUID revision() {
                        require();
                        return revision;
                    }

                    public ResourceLocation nativeType(Target target) {
                        require();
                        return ResourceLocation.parse(
                                claimed(identity, target, null).getString("id"));
                    }

                    public Tag field(Target target, String key) {
                        require();
                        var value = claimed(identity, target, key).get(key);
                        return value == null ? null : copyNew(value);
                    }

                    public Optional<CompoundTag> record(ResourceLocation name) {
                        require();
                        return PreparedPatchData.this.record(identity, name);
                    }

                    public PatchPreparedInput.Result apply(
                            UUID expected, Consumer<PatchPreparedInput.Changes> changes) {
                        require();
                        if (!revision.equals(expected))
                            return result(
                                    PatchPreparedInput.Status.STALE, "Prepared revision changed");
                        if (applying)
                            return result(
                                    PatchPreparedInput.Status.REFUSED,
                                    "Reentrant prepared proposal");
                        applying = true;
                        try (var staging = new Staging(identity, this::require)) {
                            changes.accept(staging);
                            staging.requireValid();
                            require();
                            if (!revision.equals(expected))
                                return result(
                                        PatchPreparedInput.Status.STALE, "Prepared owner changed");
                            if (staging.values.isEmpty())
                                return result(PatchPreparedInput.Status.UNCHANGED, "");
                            staging.commit();
                            revision = UUID.randomUUID();
                            return result(PatchPreparedInput.Status.ACCEPTED, "");
                        } catch (IllegalArgumentException | IllegalStateException refusal) {
                            return result(PatchPreparedInput.Status.REFUSED, concise(refusal));
                        } finally {
                            applying = false;
                        }
                    }
                });
    }

    private PatchPreparedInput.Result result(PatchPreparedInput.Status status, String text) {
        return new PatchPreparedInput.Result(status, revision, text);
    }

    private static String concise(Throwable failure) {
        var text = String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 384 ? text : text.substring(0, 384);
    }

    private final class Staging implements PatchPreparedInput.Changes, AutoCloseable {
        private PatchIdentity identity;
        private Runnable guard;
        private List<Change> values = new ArrayList<>();
        private Set<Object> distinct = new java.util.HashSet<>();
        private long payloadBytes, slots;
        private int capacity;
        private boolean committed, refused;

        Staging(PatchIdentity identity, Runnable guard) {
            this.identity = identity;
            this.guard = guard;
        }

        private void require() {
            if (guard == null || Thread.currentThread() != thread)
                throw new IllegalStateException("Prepared transaction callback ended");
            guard.run();
        }

        public void replace(Target target, String field, Tag expected, Tag replacement) {
            try {
                replaceField(target, field, expected, replacement);
            } catch (RuntimeException failure) {
                refused = true;
                throw failure;
            }
        }

        private void requireValid() {
            require();
            if (refused)
                throw new IllegalStateException("Prepared transaction already refused a change");
        }

        private void replaceField(Target target, String field, Tag expected, Tag replacement) {
            require();
            Objects.requireNonNull(target);
            Objects.requireNonNull(field);
            var nativeData = claimed(identity, target, field);
            var key = new Field(target, field);
            var current = nativeData.get(field);
            if (!Objects.equals(current, expected))
                throw new IllegalArgumentException("Stale prepared field value: " + key);
            if (current != null && replacement != null && current.getId() != replacement.getId())
                throw new IllegalArgumentException(
                        "Prepared replacement changes native NBT value type: " + key);
            if (!distinct.add(key))
                throw new IllegalArgumentException("Duplicate prepared field write: " + key);
            if (Objects.equals(expected, replacement)) return;
            stage(key, null, replacement);
        }

        public void record(ResourceLocation name, CompoundTag expected, CompoundTag replacement) {
            try {
                replaceRecord(name, expected, replacement);
            } catch (RuntimeException failure) {
                refused = true;
                throw failure;
            }
        }

        private void replaceRecord(
                ResourceLocation name, CompoundTag expected, CompoundTag replacement) {
            require();
            recordName(identity, Objects.requireNonNull(name));
            var own = records == null ? null : records.get(identity);
            var old = own == null ? null : own.get(name);
            if (!Objects.equals(old == null ? null : old.value(), expected))
                throw new IllegalArgumentException("Stale ephemeral prepared record: " + name);
            if (!distinct.add(name))
                throw new IllegalArgumentException("Duplicate prepared record: " + name);
            if (Objects.equals(expected, replacement)) return;
            stage(null, name, replacement);
        }

        private void stage(Field field, ResourceLocation name, Tag replacement) {
            // Admission precedes each host copy; foreign construction before this call is not ours.
            long bytes = replacement == null ? 0 : resources.reserveGrowth(replacement);
            Tag copy;
            try {
                copy = replacement == null ? null : replacement.copy();
            } catch (RuntimeException | Error failure) {
                resources.release(bytes);
                throw failure;
            }
            payloadBytes = Math.addExact(payloadBytes, bytes);
            if (values.size() == capacity) {
                int next = capacity == 0 ? 10 : Math.addExact(capacity, Math.max(1, capacity / 2));
                long nextSlots = ResourceMemoryBudget.arrayBytes(next, Long.BYTES);
                resources.reserveCapacity(nextSlots);
                long oldSlots = slots;
                try {
                    values.add(new Change(field, name, copy, bytes));
                    capacity = next;
                    slots = nextSlots;
                } catch (RuntimeException | Error failure) {
                    resources.release(nextSlots);
                    throw failure;
                }
                resources.release(oldSlots);
            } else values.add(new Change(field, name, copy, bytes));
        }

        void commit() {
            require();
            // Every fallible declaration/expected-value/copy/admission check precedes this point.
            // Native VM failures and arbitrary foreign side effects are not universal rollback.
            for (var change : values) {
                if (change.record() == null) {
                    var data = preparedTargets.get(change.field().target());
                    if (change.replacement() == null) data.remove(change.field().key());
                    else data.put(change.field().key(), change.replacement());
                } else {
                    if (records == null) records = new HashMap<>();
                    var own = records.computeIfAbsent(identity, ignored -> new HashMap<>());
                    var old =
                            change.replacement() == null
                                    ? own.remove(change.record())
                                    : own.put(
                                            change.record(),
                                            new Stored(
                                                    (CompoundTag) change.replacement(),
                                                    change.bytes()));
                    if (old != null) resources.release(old.bytes());
                    if (own.isEmpty()) records.remove(identity);
                }
            }
            committed = true;
        }

        public void close() {
            if (guard == null) return;
            // Dropping the staging list releases old/new overlap before its known slots are freed.
            guard = null;
            identity = null;
            values = null;
            distinct = null;
            resources.release(slots);
            if (!committed) resources.release(payloadBytes);
            slots = 0;
            payloadBytes = 0;
        }
    }

    /** Immediately after final native load; records alone remain for final mapped callbacks. */
    void preparedLoaded() {
        requireOpen();
        preparedFinished = true;
        prepared = null;
        preparedTargets = null;
        claims = null;
        targetOwners = null;
        releaseIndexes();
    }

    private java.util.Iterator<Map<ResourceLocation, Stored>> cleanupOwners;
    private java.util.Iterator<Stored> cleanupRecords;
    private boolean cleanupComplete;

    public void beginCleanup() {
        if (closed) return;
        closed = true;
        source = null;
        prepared = null;
        preparedTargets = null;
        claims = null;
        targetOwners = null;
        declarationIndex = null;
        attachmentIndex = null;
        declarationChecks = null;
        attachmentChecks = null;
        attaching = null;
        releaseIndexes();
        if (records != null) cleanupOwners = records.values().iterator();
    }

    public boolean stepCleanup(java.util.function.BooleanSupplier canContinue) {
        beginCleanup();
        while (!cleanupComplete && canContinue.getAsBoolean()) {
            if (cleanupRecords != null && cleanupRecords.hasNext()) {
                long bytes = cleanupRecords.next().bytes();
                cleanupRecords.remove();
                resources.release(bytes);
            } else if (cleanupOwners != null && cleanupOwners.hasNext()) {
                cleanupRecords = cleanupOwners.next().values().iterator();
                cleanupOwners.remove();
            } else {
                cleanupRecords = null;
                cleanupOwners = null;
                records = null;
                cleanupComplete = true;
            }
        }
        return cleanupComplete;
    }

    @Override
    public void close() {
        while (!stepCleanup(() -> true)) {}
    }
}
