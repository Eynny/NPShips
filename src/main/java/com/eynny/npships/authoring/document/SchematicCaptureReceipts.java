package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring.CaptureAspect;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureField;
import com.eynny.npships.api.patches.PatchAuthoring.Data;
import com.eynny.npships.api.patches.PatchAuthoring.Kind;
import com.eynny.npships.api.patches.PatchAuthoring.Operation;
import com.eynny.npships.api.patches.PatchIdentity;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchRegistration;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Internal required-restoration ownership attached only by the authorized capture owner. */
public final class SchematicCaptureReceipts {
    private static final String KEY = "capture_ownership";
    private static final Set<PatchStage> RESTORATION =
            Set.of(
                    PatchStage.BEFORE_ASSEMBLY,
                    PatchStage.AFTER_PLACEMENT,
                    PatchStage.BEFORE_NATIVE_ASSEMBLY,
                    PatchStage.BEFORE_BODY_PLACEMENT,
                    PatchStage.AFTER_BODY_PLACEMENT);

    private SchematicCaptureReceipts() {}

    /** The exact accepted author record represents this one granted native source aspect. */
    public record Receipt(Data representation, CaptureAspect aspect) {
        public Receipt {
            Objects.requireNonNull(representation);
            Objects.requireNonNull(aspect);
        }
    }

    private record Entry(
            PatchIdentity identity, String name, CompoundTag raw, List<Ownership> receipts) {}

    private record Ownership(CompoundTag raw, List<CaptureField> fields) {}

    /** Adds required receipts after all capture proposals have been accepted together. */
    public static SchematicDocument attach(SchematicDocument doc, List<Receipt> receipts) {
        Objects.requireNonNull(receipts);
        if (receipts.isEmpty()) return doc;
        var entries = entries(doc);
        var replacements = new HashMap<UUID, List<CompoundTag>>();
        for (var receipt : receipts) {
            var data = receipt.representation();
            var entry = requireEntry(entries, data.identity(), data.name());
            requireRuntime(data.identity().type(), PatchPolicy.defaults());
            if (!receipt.aspect().delegable())
                throw invalid("Capture aspect does not permit replacement");
            var raw = new CompoundTag();
            raw.putInt("schema", 1);
            raw.putString("default_owner", receipt.aspect().owner().toString());
            raw.putString("aspect", receipt.aspect().aspect().toString());
            raw.put("fields", fields(receipt.aspect().fields()));
            replacements
                    .computeIfAbsent(
                            data.identity().instanceId(),
                            ignored -> {
                                var list = new ArrayList<CompoundTag>();
                                entry.receipts().forEach(value -> list.add(value.raw()));
                                return list;
                            })
                    .add(raw);
        }
        var result = replace(doc, entries, replacements);
        validate(result, false, PatchPolicy.defaults());
        return result;
    }

    /**
     * Moves host receipts with accepted provider identities, independently of opaque payloads. The
     * precallback candidate distinguishes retained originals from cross-document copies.
     */
    public static SchematicDocument transfer(
            SchematicDocument before,
            SchematicDocument candidate,
            SchematicDocument result,
            Operation operation,
            Map<UUID, UUID> originals) {
        if (!present(before) && !present(candidate) && !present(result)) return result;
        var previous = entries(before);
        var retained = entries(candidate);
        var accepted = entries(result);
        if (previous.values().stream().noneMatch(entry -> !entry.receipts().isEmpty())
                && retained.values().stream().noneMatch(entry -> !entry.receipts().isEmpty()))
            return result;
        var replacements = new HashMap<UUID, List<CompoundTag>>();
        // Target-document owners are not part of the source snapshot's coordinate transform.
        for (var entry : retained.values()) {
            var source = previous.get(entry.identity().instanceId());
            boolean sourceOwner = source != null && source.identity().equals(entry.identity());
            if (sourceOwner && operation.kind() != Kind.COPY) continue;
            preserve(result, accepted, replacements, entry, null);
        }
        for (var entry : previous.values()) {
            if (entry.receipts().isEmpty()) continue;
            var identity = Objects.requireNonNull(operation.mapping().identity(entry.identity()));
            if (!identity.type().equals(entry.identity().type()))
                throw invalid("Capture receipt provider identity cannot change");
            if (!Objects.equals(
                    originals.getOrDefault(identity.instanceId(), identity.instanceId()),
                    entry.identity().instanceId()))
                throw invalid("Capture receipt occurrence has no exact source identity");
            preserve(result, accepted, replacements, entry, new Mapped(identity, operation));
        }
        var transferred = replace(result, accepted, replacements);
        validate(transferred, false, PatchPolicy.defaults());
        return transferred;
    }

    private record Mapped(PatchIdentity identity, Operation operation) {}

    private static void preserve(
            SchematicDocument result,
            Map<UUID, Entry> accepted,
            Map<UUID, List<CompoundTag>> replacements,
            Entry entry,
            Mapped mapped) {
        var projected = new ArrayList<CompoundTag>();
        for (var ownership : entry.receipts()) {
            var fields = new LinkedHashSet<CaptureField>();
            var stored = ownership.raw().getList("fields", Tag.TAG_COMPOUND);
            var output = new ListTag();
            for (int index = 0; index < ownership.fields().size(); index++) {
                var field = ownership.fields().get(index);
                var positions =
                        mapped == null
                                ? List.of(field.position())
                                : mapped.operation().mapping().cells(field.position());
                for (var position : positions)
                    if (result.blocks().containsKey(position)) {
                        var next = new CaptureField(position, field.key());
                        if (!fields.add(next)) throw invalid("Ambiguous capture field mapping");
                        var value = shallow(stored.getCompound(index));
                        value.put("pos", position(position));
                        output.add(value);
                    }
            }
            if (!ownership.fields().isEmpty() && fields.isEmpty()) continue;
            var raw = shallow(ownership.raw());
            raw.put("fields", output);
            projected.add(raw);
        }
        var identity = mapped == null ? entry.identity() : mapped.identity();
        var next = accepted.get(identity.instanceId());
        if (!projected.isEmpty()) requireEntry(accepted, identity, entry.name());
        if (next != null && next.identity().equals(identity)) {
            var old = replacements.putIfAbsent(identity.instanceId(), List.copyOf(projected));
            if (old != null && !old.equals(projected))
                throw invalid("Capture ownership maps ambiguously to one author instance");
        }
    }

    /**
     * Structural validation is world-free; runtime additionally requires actual restoration code.
     */
    public static void validate(SchematicDocument doc, boolean runtime, PatchPolicy policy) {
        if (!present(doc)) return;
        validateEntries(doc, entries(doc), runtime, policy);
    }

    private static void validateEntries(
            SchematicDocument doc, Map<UUID, Entry> entries, boolean runtime, PatchPolicy policy) {
        var owned = new HashSet<CaptureField>();
        var checked = new HashSet<ResourceLocation>();
        for (var entry : entries.values()) {
            if (entry.receipts().isEmpty()) continue;
            if (runtime && checked.add(entry.identity().type()))
                requireRuntime(entry.identity().type(), policy);
            var aspects = new HashSet<String>();
            for (var receipt : entry.receipts()) {
                String aspect =
                        receipt.raw().getString("default_owner")
                                + "/"
                                + receipt.raw().getString("aspect")
                                + "/"
                                + receipt.fields();
                if (!aspects.add(aspect)) throw invalid("Duplicate capture ownership receipt");
                for (var field : receipt.fields()) {
                    if (!doc.contains(field.position())
                            || !doc.blocks().containsKey(field.position()))
                        throw invalid("Capture ownership target is missing: " + field.position());
                    if (!owned.add(field))
                        throw invalid("Conflicting capture ownership field: " + field);
                    if (doc.extensionBlockEntityReadOnly(field.position()).contains(field.key()))
                        throw invalid("Delegated native source field was not removed: " + field);
                }
            }
        }
    }

    /**
     * A native author transform may serialize fresh BE defaults. Preserve deliberate source-field
     * absence only in that host-generated candidate, before providers propose their own changes.
     * Validate the source first; this must not conceal fields reintroduced by an author edit.
     */
    static Map<BlockPos, Set<String>> omittedFields(SchematicDocument doc) {
        if (!present(doc)) return Map.of();
        var entries = entries(doc);
        validateEntries(doc, entries, false, PatchPolicy.defaults());
        var result = new LinkedHashMap<BlockPos, Set<String>>();
        for (var entry : entries.values())
            for (var receipt : entry.receipts())
                for (var field : receipt.fields())
                    result.computeIfAbsent(field.position(), ignored -> new LinkedHashSet<>())
                            .add(field.key());
        result.replaceAll((position, fields) -> Collections.unmodifiableSet(fields));
        return Collections.unmodifiableMap(result);
    }

    /** Detached identities which the common executor must bind before acquiring any handlers. */
    public static Set<PatchIdentity> requiredIdentities(SchematicDocument doc) {
        if (!present(doc)) return Set.of();
        var result = new LinkedHashSet<PatchIdentity>();
        entries(doc).values().stream()
                .filter(entry -> !entry.receipts().isEmpty())
                .forEach(entry -> result.add(entry.identity()));
        return Collections.unmodifiableSet(result);
    }

    /** Required native mapping footprint; document-scoped owners have an empty set. */
    public static Map<PatchIdentity, Set<BlockPos>> requiredPositions(SchematicDocument doc) {
        if (!present(doc)) return Map.of();
        var result = new LinkedHashMap<PatchIdentity, Set<BlockPos>>();
        for (var entry : entries(doc).values()) {
            if (entry.receipts().isEmpty()) continue;
            var positions = new LinkedHashSet<BlockPos>();
            entry.receipts()
                    .forEach(
                            receipt ->
                                    receipt.fields()
                                            .forEach(field -> positions.add(field.position())));
            result.put(entry.identity(), Collections.unmodifiableSet(positions));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Detached required-footprint reader; no payload copies or provider code. */
    public static Read beginRead(SchematicDocument doc, java.util.function.LongConsumer reserve) {
        return new Read(doc, reserve);
    }

    public static final class Read implements AutoCloseable {
        private SchematicDocument doc;
        private final java.util.function.LongConsumer reserve;
        private ListTag rawEntries, rawReceipts, rawFields;
        private List<Entry> entries = new ArrayList<>();
        private Set<UUID> ids = new HashSet<>();
        private List<Ownership> receipts;
        private List<CaptureField> fields;
        private Set<CaptureField> distinct;
        private Map<PatchIdentity, Set<BlockPos>> positions = new LinkedHashMap<>();
        private Set<BlockPos> currentPositions;
        private CompoundTag raw, ownership;
        private PatchIdentity identity;
        private int phase, index, receiptIndex, fieldIndex;
        private long progress;
        private boolean closed;
        private Validation validation;

        private Read(SchematicDocument doc, java.util.function.LongConsumer reserve) {
            this.doc = Objects.requireNonNull(doc);
            this.reserve = Objects.requireNonNull(reserve);
        }

        public boolean step(java.util.function.BooleanSupplier allowed) {
            requireOpen();
            while (phase != 6 && allowed.getAsBoolean()) {
                progress++;
                if (phase == 0) {
                    if (!doc.hasMetadata(SchematicExtensions.KEY)) {
                        phase = 6;
                        continue;
                    }
                    rawEntries =
                            doc.metadataReadOnly(SchematicExtensions.KEY)
                                    .getList("entries", Tag.TAG_COMPOUND);
                    phase = 1;
                } else if (phase == 1) {
                    if (index == rawEntries.size()) {
                        phase = 6;
                        continue;
                    }
                    if (rawEntries.getCompound(index++).contains(KEY)) {
                        var envelope = doc.metadataReadOnly(SchematicExtensions.KEY);
                        if (!envelope.contains("schema", Tag.TAG_INT)
                                || envelope.getInt("schema") != 1
                                || !envelope.contains("entries", Tag.TAG_LIST))
                            throw invalid("Malformed author envelope for capture ownership");
                        rawEntries = compounds(envelope, "entries");
                        index = 0;
                        phase = 2;
                    }
                } else if (phase == 2) {
                    if (index == rawEntries.size()) {
                        phase = 6;
                        continue;
                    }
                    raw = rawEntries.getCompound(index);
                    if (!raw.contains("type", Tag.TAG_STRING)
                            || !raw.hasUUID("instance")
                            || !raw.contains("name", Tag.TAG_STRING)
                            || raw.getString("name").isEmpty()
                            || raw.getString("name").length() > 256
                            || !raw.contains("version", Tag.TAG_INT)
                            || raw.getInt("version") < 1
                            || !raw.contains("payload", Tag.TAG_COMPOUND)
                            || raw.contains("group") && !raw.hasUUID("group")
                            || raw.contains("occurrence") && !raw.hasUUID("occurrence"))
                        throw invalid("Malformed author record for capture ownership");
                    identity =
                            new PatchIdentity(
                                    resource(raw, "type"),
                                    raw.getUUID("instance"),
                                    raw.hasUUID("group") ? raw.getUUID("group") : null,
                                    raw.hasUUID("occurrence") ? raw.getUUID("occurrence") : null);
                    rawReceipts = raw.contains(KEY) ? compounds(raw, KEY) : new ListTag();
                    receipts = new ArrayList<>();
                    currentPositions = new LinkedHashSet<>();
                    receiptIndex = 0;
                    phase = 3;
                } else if (phase == 3) {
                    if (receiptIndex == rawReceipts.size()) {
                        if (!ids.add(identity.instanceId()))
                            throw invalid(
                                    "Duplicate capture author instance: " + identity.instanceId());
                        reserve.accept(96);
                        entries.add(
                                new Entry(
                                        identity,
                                        raw.getString("name"),
                                        raw,
                                        Collections.unmodifiableList(receipts)));
                        if (!receipts.isEmpty())
                            positions.put(identity, Collections.unmodifiableSet(currentPositions));
                        index++;
                        phase = 2;
                        continue;
                    }
                    ownership = rawReceipts.getCompound(receiptIndex);
                    if (!ownership.contains("schema", Tag.TAG_INT)
                            || ownership.getInt("schema") != 1)
                        throw invalid("Unsupported capture ownership schema");
                    resource(ownership, "default_owner");
                    resource(ownership, "aspect");
                    rawFields = compounds(ownership, "fields");
                    fields = new ArrayList<>();
                    distinct = new HashSet<>();
                    fieldIndex = 0;
                    phase = 4;
                } else {
                    if (fieldIndex == rawFields.size()) {
                        reserve.accept(32);
                        receipts.add(
                                new Ownership(ownership, Collections.unmodifiableList(fields)));
                        receiptIndex++;
                        phase = 3;
                        continue;
                    }
                    var cell = rawFields.getCompound(fieldIndex++);
                    if (!cell.contains("pos", Tag.TAG_LIST)
                            || !cell.contains("key", Tag.TAG_STRING))
                        throw invalid("Malformed capture ownership field");
                    var position = cell.getList("pos", Tag.TAG_INT);
                    if (position.size() != 3 || ((ListTag) cell.get("pos")).size() != 3)
                        throw invalid("Capture ownership position needs three integers");
                    String key = cell.getString("key");
                    if (Set.of(
                                            "id",
                                            "x",
                                            "y",
                                            "z",
                                            "components",
                                            "NeoForgeData",
                                            "ForgeData",
                                            "ForgeCaps")
                                    .contains(key)
                            || key.startsWith("npships")
                            || key.startsWith("NPShips"))
                        throw invalid("Reserved native capture ownership field: " + key);
                    var field =
                            new CaptureField(
                                    new BlockPos(
                                            position.getInt(0),
                                            position.getInt(1),
                                            position.getInt(2)),
                                    key);
                    if (!distinct.add(field)) throw invalid("Duplicate capture ownership field");
                    reserve.accept(96);
                    fields.add(field);
                    currentPositions.add(field.position());
                }
            }
            return phase == 6;
        }

        public Map<PatchIdentity, Set<BlockPos>> requiredPositions() {
            requireOpen();
            if (phase != 6) throw new IllegalStateException("Capture receipt read incomplete");
            return Collections.unmodifiableMap(positions);
        }

        public boolean stepValidation(
                java.util.function.BooleanSupplier allowed, boolean runtime, PatchPolicy policy) {
            requireOpen();
            if (!step(allowed)) return false;
            if (validation == null)
                validation = new Validation(doc, entries, runtime, policy, reserve);
            long before = validation.progress;
            boolean done = validation.step(allowed);
            progress += validation.progress - before;
            return done;
        }

        public long progress() {
            return progress;
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("Capture receipt read closed");
        }

        public void close() {
            closed = true;
            doc = null;
            entries = null;
            ids = null;
            positions = null;
            currentPositions = null;
            receipts = null;
            fields = null;
            distinct = null;
            rawEntries = rawReceipts = rawFields = null;
            raw = ownership = null;
            validation = null;
        }
    }

    /** Canonical legacy receipt key, streamed without allocating a whole fields-list string. */
    private static final class Signature {
        final Ownership value;
        int part, character, hash;
        String text;

        Signature(Ownership value) {
            this.value = value;
        }

        String part(int at) {
            if (at == 0)
                return value.raw().getString("default_owner")
                        + "/"
                        + value.raw().getString("aspect")
                        + "/[";
            if (at <= value.fields().size())
                return (at == 1 ? "" : ", ") + value.fields().get(at - 1);
            return at == value.fields().size() + 1 ? "]" : null;
        }

        boolean hashOne() {
            var next = part(part++);
            if (next == null) return true;
            for (int i = 0; i < next.length(); i++) hash = 31 * hash + next.charAt(i);
            return false;
        }

        int next() {
            while (text == null || character == text.length()) {
                text = part(part++);
                character = 0;
                if (text == null) return -1;
            }
            return text.charAt(character++);
        }
    }

    private static final class Validation {
        private final SchematicDocument doc;
        private final List<Entry> entries;
        private final boolean runtime;
        private final PatchPolicy policy;
        private final java.util.function.LongConsumer reserve;
        private final Set<CaptureField> owned = new HashSet<>();
        private final Set<ResourceLocation> checked = new HashSet<>();
        private Map<Integer, List<Ownership>> aspects;
        private List<Ownership> collisions;
        private Signature signature, left, right;
        private RuntimeRequirement requirement;
        private int entryIndex, receiptIndex, fieldIndex, collisionIndex, phase;
        private long progress;

        private Validation(
                SchematicDocument doc,
                List<Entry> entries,
                boolean runtime,
                PatchPolicy policy,
                java.util.function.LongConsumer reserve) {
            this.doc = doc;
            this.entries = entries;
            this.runtime = runtime;
            this.policy = policy;
            this.reserve = reserve;
        }

        boolean step(java.util.function.BooleanSupplier allowed) {
            while (phase != 6) {
                if (requirement != null) {
                    long before = requirement.progress;
                    boolean done = requirement.step(allowed);
                    progress += requirement.progress - before;
                    if (!done) return false;
                    requirement = null;
                    continue;
                }
                if (!allowed.getAsBoolean()) return false;
                progress++;
                if (phase == 0) {
                    if (entryIndex == entries.size()) {
                        phase = 6;
                        continue;
                    }
                    var entry = entries.get(entryIndex);
                    if (entry.receipts().isEmpty()) {
                        entryIndex++;
                        continue;
                    }
                    aspects = new HashMap<>();
                    receiptIndex = 0;
                    phase = 1;
                    if (runtime && checked.add(entry.identity().type()))
                        requirement = new RuntimeRequirement(entry.identity().type(), policy);
                } else if (phase == 1) {
                    var receipts = entries.get(entryIndex).receipts();
                    if (receiptIndex == receipts.size()) {
                        entryIndex++;
                        phase = 0;
                        continue;
                    }
                    signature = new Signature(receipts.get(receiptIndex));
                    phase = 2;
                } else if (phase == 2) {
                    if (signature.hashOne()) {
                        collisions =
                                aspects.computeIfAbsent(
                                        signature.hash, ignored -> new ArrayList<>());
                        collisionIndex = 0;
                        phase = 3;
                    }
                } else if (phase == 3) {
                    if (collisionIndex == collisions.size()) {
                        reserve.accept(32);
                        collisions.add(signature.value);
                        fieldIndex = 0;
                        phase = 5;
                    } else {
                        left = new Signature(signature.value);
                        right = new Signature(collisions.get(collisionIndex++));
                        phase = 4;
                    }
                } else if (phase == 4) {
                    for (int i = 0; i < 1024; i++) {
                        int a = left.next(), b = right.next();
                        if (a != b) {
                            phase = 3;
                            break;
                        }
                        if (a == -1) throw invalid("Duplicate capture ownership receipt");
                    }
                } else {
                    var fields = signature.value.fields();
                    if (fieldIndex == fields.size()) {
                        receiptIndex++;
                        phase = 1;
                        continue;
                    }
                    var field = fields.get(fieldIndex++);
                    if (!doc.contains(field.position())
                            || !doc.blocks().containsKey(field.position()))
                        throw invalid("Capture ownership target is missing: " + field.position());
                    reserve.accept(32);
                    if (!owned.add(field))
                        throw invalid("Conflicting capture ownership field: " + field);
                    if (doc.extensionBlockEntityReadOnly(field.position()).contains(field.key()))
                        throw invalid("Delegated native source field was not removed: " + field);
                }
            }
            return true;
        }
    }

    private static final class RuntimeRequirement {
        private final ResourceLocation id;
        private final PatchPolicy policy;
        private final java.util.ArrayDeque<ResourceLocation> pending = new java.util.ArrayDeque<>();
        private final Set<ResourceLocation> checked = new HashSet<>();
        private java.util.Iterator<String> mods;
        private java.util.Iterator<ResourceLocation> dependencies;
        private int phase;
        private long progress;

        RuntimeRequirement(ResourceLocation id, PatchPolicy policy) {
            this.id = id;
            this.policy = policy;
        }

        boolean step(java.util.function.BooleanSupplier allowed) {
            while (phase != 2 && allowed.getAsBoolean()) {
                progress++;
                if (phase == 0) {
                    var definition = PatchTypes.provider(id);
                    var adapter = PatchTypes.adapter(id);
                    if (definition == null)
                        throw invalid("Required delegated capture provider is missing: " + id);
                    if (!policy.enabled(id))
                        throw invalid(
                                "Required delegated capture provider is disabled by SERVER policy: "
                                        + id);
                    if (adapter == null)
                        throw invalid(
                                "Required delegated capture provider has no runtime adapter: "
                                        + id);
                    if (definition.registration().disabledBehavior()
                            != PatchRegistration.DisabledBehavior.REFUSE)
                        throw invalid(
                                "Required delegated capture provider cannot use optional SKIP: "
                                        + id);
                    if (Collections.disjoint(adapter.stages(), RESTORATION))
                        throw invalid(
                                "Required delegated capture provider has no global restoration stage: "
                                        + id);
                    pending.add(id);
                    phase = 1;
                } else if (mods != null && mods.hasNext()) {
                    var mod = mods.next();
                    if (!ModList.get().isLoaded(mod))
                        throw invalid(
                                "Required delegated capture native mod is unavailable: " + mod);
                } else if (dependencies != null && dependencies.hasNext())
                    pending.addLast(dependencies.next());
                else if (pending.isEmpty()) phase = 2;
                else {
                    var current = pending.removeFirst();
                    if (!checked.add(current)) continue;
                    var provider = PatchTypes.provider(current);
                    if (provider == null || !policy.enabled(current))
                        throw invalid(
                                "Required delegated capture dependency is unavailable: " + current);
                    mods = provider.requiredMods().iterator();
                    dependencies = provider.registration().requires().iterator();
                }
            }
            return phase == 2;
        }
    }

    static void requireRuntime(ResourceLocation id, PatchPolicy policy) {
        var definition = PatchTypes.provider(id);
        var adapter = PatchTypes.adapter(id);
        if (definition == null)
            throw invalid("Required delegated capture provider is missing: " + id);
        if (!policy.enabled(id))
            throw invalid(
                    "Required delegated capture provider is disabled by SERVER policy: " + id);
        if (adapter == null)
            throw invalid("Required delegated capture provider has no runtime adapter: " + id);
        if (definition.registration().disabledBehavior()
                != PatchRegistration.DisabledBehavior.REFUSE)
            throw invalid("Required delegated capture provider cannot use optional SKIP: " + id);
        if (Collections.disjoint(adapter.stages(), RESTORATION))
            throw invalid(
                    "Required delegated capture provider has no global restoration stage: " + id);
        var pending = new ArrayList<ResourceLocation>();
        var checked = new HashSet<ResourceLocation>();
        pending.add(id);
        for (int i = 0; i < pending.size(); i++) {
            var current = pending.get(i);
            if (!checked.add(current)) continue;
            var provider = PatchTypes.provider(current);
            if (provider == null || !policy.enabled(current))
                throw invalid("Required delegated capture dependency is unavailable: " + current);
            for (var mod : provider.requiredMods())
                if (!ModList.get().isLoaded(mod))
                    throw invalid("Required delegated capture native mod is unavailable: " + mod);
            pending.addAll(provider.registration().requires());
        }
    }

    /** No payload copies or provider lookups in the ordinary author-data-only path. */
    private static boolean present(SchematicDocument doc) {
        if (!doc.hasMetadata(SchematicExtensions.KEY)) return false;
        var envelope = doc.metadataReadOnly(SchematicExtensions.KEY);
        for (var value : envelope.getList("entries", Tag.TAG_COMPOUND))
            if (((CompoundTag) value).contains(KEY)) return true;
        return false;
    }

    private static Map<UUID, Entry> entries(SchematicDocument doc) {
        var result = new LinkedHashMap<UUID, Entry>();
        if (!doc.hasMetadata(SchematicExtensions.KEY)) return result;
        var envelope = doc.metadataReadOnly(SchematicExtensions.KEY);
        if (!envelope.contains("schema", Tag.TAG_INT)
                || envelope.getInt("schema") != 1
                || !envelope.contains("entries", Tag.TAG_LIST))
            throw invalid("Malformed author envelope for capture ownership");
        var rawEntries = compounds(envelope, "entries");
        for (var value : rawEntries) {
            var raw = (CompoundTag) value;
            if (!raw.contains("type", Tag.TAG_STRING)
                    || !raw.hasUUID("instance")
                    || !raw.contains("name", Tag.TAG_STRING)
                    || raw.getString("name").isEmpty()
                    || raw.getString("name").length() > 256
                    || !raw.contains("version", Tag.TAG_INT)
                    || raw.getInt("version") < 1
                    || !raw.contains("payload", Tag.TAG_COMPOUND)
                    || raw.contains("group") && !raw.hasUUID("group")
                    || raw.contains("occurrence") && !raw.hasUUID("occurrence"))
                throw invalid("Malformed author record for capture ownership");
            var identity =
                    new PatchIdentity(
                            resource(raw, "type"),
                            raw.getUUID("instance"),
                            raw.hasUUID("group") ? raw.getUUID("group") : null,
                            raw.hasUUID("occurrence") ? raw.getUUID("occurrence") : null);
            var receipts = new ArrayList<Ownership>();
            if (raw.contains(KEY))
                for (var receipt : compounds(raw, KEY)) {
                    var ownership = (CompoundTag) receipt;
                    if (!ownership.contains("schema", Tag.TAG_INT)
                            || ownership.getInt("schema") != 1)
                        throw invalid("Unsupported capture ownership schema");
                    resource(ownership, "default_owner");
                    resource(ownership, "aspect");
                    var fields = new ArrayList<CaptureField>();
                    var distinct = new HashSet<CaptureField>();
                    for (var field : compounds(ownership, "fields")) {
                        var cell = (CompoundTag) field;
                        if (!cell.contains("pos", Tag.TAG_LIST)
                                || !cell.contains("key", Tag.TAG_STRING))
                            throw invalid("Malformed capture ownership field");
                        var position = cell.getList("pos", Tag.TAG_INT);
                        if (position.size() != 3 || ((ListTag) cell.get("pos")).size() != 3)
                            throw invalid("Capture ownership position needs three integers");
                        String key = cell.getString("key");
                        if (Set.of(
                                                "id",
                                                "x",
                                                "y",
                                                "z",
                                                "components",
                                                "NeoForgeData",
                                                "ForgeData",
                                                "ForgeCaps")
                                        .contains(key)
                                || key.startsWith("npships")
                                || key.startsWith("NPShips"))
                            throw invalid("Reserved native capture ownership field: " + key);
                        var parsed =
                                new CaptureField(
                                        new BlockPos(
                                                position.getInt(0),
                                                position.getInt(1),
                                                position.getInt(2)),
                                        key);
                        if (!distinct.add(parsed))
                            throw invalid("Duplicate capture ownership field");
                        fields.add(parsed);
                    }
                    receipts.add(new Ownership(ownership, List.copyOf(fields)));
                }
            if (result.put(
                            identity.instanceId(),
                            new Entry(identity, raw.getString("name"), raw, List.copyOf(receipts)))
                    != null)
                throw invalid("Duplicate capture author instance: " + identity.instanceId());
        }
        return result;
    }

    private static ListTag compounds(CompoundTag parent, String key) {
        if (!parent.contains(key, Tag.TAG_LIST))
            throw invalid("Missing capture ownership list " + key);
        var raw = (ListTag) parent.get(key);
        if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)
            throw invalid("Capture ownership list must contain compounds: " + key);
        return raw;
    }

    private static ResourceLocation resource(CompoundTag raw, String key) {
        if (!raw.contains(key, Tag.TAG_STRING) || raw.getString(key).length() > 256)
            throw invalid("Invalid capture ownership identifier: " + key);
        var id = ResourceLocation.tryParse(raw.getString(key));
        if (id == null) throw invalid("Invalid capture ownership identifier: " + key);
        return id;
    }

    private static Entry requireEntry(
            Map<UUID, Entry> entries, PatchIdentity identity, String name) {
        var entry = entries.get(identity.instanceId());
        if (entry == null || !entry.identity().equals(identity) || !entry.name().equals(name))
            throw invalid(
                    "Required delegated capture owner record is missing: " + identity + "/" + name);
        return entry;
    }

    private static ListTag fields(List<CaptureField> values) {
        var result = new ListTag();
        for (var value : values) {
            var raw = new CompoundTag();
            raw.put("pos", position(value.position()));
            raw.putString("key", value.key());
            result.add(raw);
        }
        return result;
    }

    private static ListTag position(BlockPos value) {
        var result = new ListTag();
        result.add(IntTag.valueOf(value.getX()));
        result.add(IntTag.valueOf(value.getY()));
        result.add(IntTag.valueOf(value.getZ()));
        return result;
    }

    private static SchematicDocument replace(
            SchematicDocument doc,
            Map<UUID, Entry> entries,
            Map<UUID, List<CompoundTag>> replacements) {
        if (replacements.isEmpty()) return doc;
        var envelope = shallow(doc.metadataReadOnly(SchematicExtensions.KEY));
        var list = new ListTag();
        for (var entry : entries.values()) {
            var values = replacements.get(entry.identity().instanceId());
            if (values == null) list.add(entry.raw());
            else {
                var raw = shallow(entry.raw());
                if (values.isEmpty()) raw.remove(KEY);
                else {
                    var receipts = new ListTag();
                    values.forEach(receipts::add);
                    raw.put(KEY, receipts);
                }
                list.add(raw);
            }
        }
        envelope.put("entries", list);
        return doc.withExtensionMetadata(envelope);
    }

    private static CompoundTag shallow(CompoundTag source) {
        var result = new CompoundTag();
        source.getAllKeys().forEach(key -> result.put(key, source.get(key)));
        return result;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail + "; original document is unchanged");
    }
}
