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
