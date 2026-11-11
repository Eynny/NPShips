package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring.BlockStateEdit;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureAspect;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureClaim;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureMode;
import com.eynny.npships.api.patches.PatchAuthoring.Context;
import com.eynny.npships.api.patches.PatchAuthoring.Data;
import com.eynny.npships.api.patches.PatchAuthoring.Document;
import com.eynny.npships.api.patches.PatchAuthoring.FieldEdit;
import com.eynny.npships.api.patches.PatchAuthoring.Kind;
import com.eynny.npships.api.patches.PatchAuthoring.Mapping;
import com.eynny.npships.api.patches.PatchAuthoring.Operation;
import com.eynny.npships.api.patches.PatchAuthoring.Proposal;
import com.eynny.npships.api.patches.PatchAuthoring.Source;
import com.eynny.npships.api.patches.PatchIdentity;
import com.eynny.npships.api.patches.PatchOrdering;
import com.eynny.npships.api.patches.PatchPolicy;
import com.eynny.npships.api.patches.PatchRegistration;
import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.patches.PayloadEvolution;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceMemoryBudget;
import com.eynny.npships.resources.ServerResourcePolicy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.*;
import java.util.function.*;

/**
 * Explicit detached author operations; passive document reconstruction never invokes addon code.
 */
public final class SchematicExtensions {
    public static final String KEY = "npships:author_data";
    private static final ThreadLocal<Integer> INTERMEDIATE = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> CALLBACK = ThreadLocal.withInitial(() -> false);

    private SchematicExtensions() {}

    public static boolean present(SchematicDocument doc) {
        if (!doc.hasMetadata(KEY)) return false;
        var envelope = doc.metadataReadOnly(KEY);
        return envelope.getInt("schema") != 1
                || !envelope.contains("entries", Tag.TAG_LIST)
                || !envelope.getList("entries", Tag.TAG_COMPOUND).isEmpty()
                || !((ListTag) envelope.get("entries")).isEmpty();
    }

    /**
     * Composite host operations suppress their temporary children, never independent variant roots.
     */
    public static <T> T intermediate(Supplier<T> action) {
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data operation");
        int old = INTERMEDIATE.get();
        INTERMEDIATE.set(old + 1);
        try {
            return action.get();
        } finally {
            if (old == 0) INTERMEDIATE.remove();
            else INTERMEDIATE.set(old);
        }
    }

    public static void refuse(SchematicDocument doc, String operation) {
        if (present(doc))
            throw new IllegalArgumentException(
                    "Author data "
                            + providerIds(doc)
                            + " cannot represent "
                            + operation
                            + "; original document is unchanged");
    }

    public static Set<ResourceLocation> providerIds(SchematicDocument doc) {
        var ids = new TreeSet<ResourceLocation>();
        for (var record : read(doc)) ids.add(record.identity().type());
        return Collections.unmodifiableSet(ids);
    }

    /** One detached provider's records; runtime callers must still guard their callback window. */
    public static List<Data> data(SchematicDocument doc, ResourceLocation type) {
        return read(doc, type, tag -> {});
    }

    /** The prepared owner reserves known mutable payload before handing a detached copy to code. */
    public static List<Data> data(
            SchematicDocument doc, ResourceLocation type, Consumer<Tag> beforeCopy) {
        return read(doc, type, beforeCopy);
    }

    /**
     * Opt-in canonical detached records for prepared input or an explicit accepted proposal. The
     * original envelope, versions, identities and unknown fields are never rewritten by this read.
     */
    public static List<Data> upgradedData(
            SchematicDocument doc, ResourceLocation type, Consumer<Tag> beforeCopy) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(beforeCopy);
        return upgradedData(
                doc, type, beforeCopy, beforeCopy, (tag, changed) -> beforeCopy.accept(tag));
    }

    /** Prepared owners distinguish temporary processor graphs from the final retained Data copy. */
    public static List<Data> upgradedData(
            SchematicDocument doc,
            ResourceLocation type,
            Consumer<Tag> beforeCopy,
            Consumer<Tag> beforeGrowth,
            BiConsumer<Tag, Boolean> beforeResult) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(beforeCopy);
        Objects.requireNonNull(beforeGrowth);
        Objects.requireNonNull(beforeResult);
        return read(doc, type, beforeCopy, beforeGrowth, beforeResult, true);
    }

    public static List<Data> read(SchematicDocument doc) {
        return read(doc, null, tag -> {});
    }

    private static List<Data> read(
            SchematicDocument doc, ResourceLocation selected, Consumer<Tag> beforeCopy) {
        return read(
                doc,
                selected,
                beforeCopy,
                beforeCopy,
                (tag, changed) -> beforeCopy.accept(tag),
                false);
    }

    private static List<Data> read(
            SchematicDocument doc,
            ResourceLocation selected,
            Consumer<Tag> beforeCopy,
            Consumer<Tag> beforeGrowth,
            BiConsumer<Tag, Boolean> beforeResult,
            boolean upgrade) {
        if (!present(doc)) return List.of();
        var envelope = doc.metadataReadOnly(KEY);
        if (!envelope.contains("schema", Tag.TAG_INT)
                || envelope.getInt("schema") != 1
                || !envelope.contains("entries", Tag.TAG_LIST))
            throw new IllegalArgumentException(
                    "Unsupported/malformed " + KEY + " envelope; original is unchanged");
        var entries = envelope.getList("entries", Tag.TAG_COMPOUND);
        if (entries.size() != ((ListTag) envelope.get("entries")).size())
            throw new IllegalArgumentException("Author data entries must be compounds");
        var result = new ArrayList<Data>(entries.size());
        var ids = new HashSet<UUID>();
        var groups = new HashMap<UUID, String>();
        for (int i = 0; i < entries.size(); i++) {
            var raw = entries.getCompound(i);
            if (!raw.contains("type", Tag.TAG_STRING)
                    || !raw.hasUUID("instance")
                    || !raw.contains("name", Tag.TAG_STRING)
                    || !raw.contains("version", Tag.TAG_INT)
                    || !raw.contains("payload", Tag.TAG_COMPOUND)
                    || raw.contains("group") && !raw.hasUUID("group")
                    || raw.contains("occurrence") && !raw.hasUUID("occurrence"))
                throw new IllegalArgumentException("Malformed author data entry " + i);
            var identity =
                    new PatchIdentity(
                            ResourceLocation.parse(raw.getString("type")),
                            raw.getUUID("instance"),
                            raw.hasUUID("group") ? raw.getUUID("group") : null,
                            raw.hasUUID("occurrence") ? raw.getUUID("occurrence") : null);
            if (!ids.add(identity.instanceId()))
                throw new IllegalArgumentException(
                        "Duplicate author instance " + identity.instanceId());
            if (identity.groupId() != null) {
                String owner = identity.type() + "/" + identity.occurrenceId();
                String old = groups.putIfAbsent(identity.groupId(), owner);
                if (old != null && !old.equals(owner))
                    throw new IllegalArgumentException(
                            "Ambiguous author group " + identity.groupId());
            }
            if (raw.getString("name").isEmpty()
                    || raw.getString("name").length() > 256
                    || raw.getInt("version") < 1)
                throw new IllegalArgumentException(
                        "Invalid author data name/version at " + identity.instanceId());
            if (selected == null || selected.equals(identity.type())) {
                String name = raw.getString("name");
                int version = raw.getInt("version");
                CompoundTag payload = raw.getCompound("payload");
                boolean changedVersion = false;
                if (upgrade) {
                    var schemas = PatchTypes.payloads(identity.type());
                    var schema = schemas == null ? null : schemas.authorData().get(name);
                    if (schema != null) {
                        payload =
                                PayloadEvolution.nbt(
                                        identity.type(),
                                        name,
                                        version,
                                        payload,
                                        beforeCopy::accept,
                                        beforeGrowth::accept);
                        changedVersion = version != schema.currentVersion();
                        version = schema.currentVersion();
                    }
                }
                // Data's defensive constructor creates a separately retained mutable graph.
                beforeResult.accept(payload, changedVersion);
                result.add(new Data(name, version, identity, payload));
            }
        }
        return List.copyOf(result);
    }

    private static SchematicDocument write(
            SchematicDocument candidate,
            SchematicDocument before,
            List<Data> values,
            Map<UUID, UUID> originals) {
        var envelope = shallow(candidate.metadataReadOnly(KEY));
        if (envelope.isEmpty() && values.isEmpty()) return candidate;
        var rawById = rawEntries(before);
        rawEntries(candidate).forEach(rawById::put);
        var list = new ListTag();
        for (var value : values) {
            var id = value.identity();
            var previous = rawById.get(originals.getOrDefault(id.instanceId(), id.instanceId()));
            var raw = previous == null ? new CompoundTag() : shallow(previous);
            raw.putString("type", id.type().toString());
            raw.putString("name", value.name());
            raw.putInt("version", value.version());
            raw.putUUID("instance", id.instanceId());
            if (id.groupId() == null) raw.remove("group");
            else raw.putUUID("group", id.groupId());
            if (id.occurrenceId() == null) raw.remove("occurrence");
            else raw.putUUID("occurrence", id.occurrenceId());
            raw.put("payload", value.payload());
            list.add(raw);
        }
        envelope.putInt("schema", 1);
        envelope.put("entries", list);
        return candidate.withExtensionMetadata(envelope);
    }

    /** Values come only from immutable host graphs; changed nodes receive detached replacements. */
    private static CompoundTag shallow(CompoundTag source) {
        var result = new CompoundTag();
        for (String key : source.getAllKeys()) result.put(key, source.get(key));
        return result;
    }

    private static Map<UUID, CompoundTag> rawEntries(SchematicDocument doc) {
        var result = new HashMap<UUID, CompoundTag>();
        if (present(doc))
            for (var tag : doc.metadataReadOnly(KEY).getList("entries", Tag.TAG_COMPOUND)) {
                var value = (CompoundTag) tag;
                if (value.hasUUID("instance")) result.put(value.getUUID("instance"), value);
            }
        return result;
    }

    public static SchematicDocument apply(
            SchematicDocument before, SchematicDocument candidate, Operation operation) {
        if (INTERMEDIATE.get() != 0 || !present(before) && !present(candidate)) return candidate;
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data operation");
        return run(before, candidate, operation, null, null, null, false, PatchPolicy.defaults());
    }

    /** Only an actual outer author transaction may defer opaque mirror fields. */
    static boolean canDeferMirror(SchematicDocument source) {
        return INTERMEDIATE.get() == 0 && present(source);
    }

    static SchematicDocument applyMirror(
            SchematicDocument before,
            SchematicDocument candidate,
            Operation operation,
            Set<BlockPos> deferred,
            int axes) {
        if (INTERMEDIATE.get() != 0) {
            if (!deferred.isEmpty())
                throw new IllegalArgumentException(
                        "Opaque mirror fields require an outer provider transaction");
            return candidate;
        }
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data operation");
        return run(
                before,
                candidate,
                operation,
                null,
                null,
                null,
                false,
                PatchPolicy.defaults(),
                deferred,
                axes);
    }

    /**
     * Callback-scoped indexed inspection for the current accepted owner. Merely opening a view does
     * not copy the document or decode provider payloads; selected data is copied only on request.
     * The caller retains editor/session revision and screen-lifetime ownership.
     */
    public static <T> T inspect(
            ResourceLocation provider,
            SchematicDocument accepted,
            Function<Context, T> inspection) {
        return inspect(provider, accepted, null, inspection);
    }

    /** Read-only host fan-out may share one detached immutable data snapshot of this document. */
    public static <T> T inspect(
            ResourceLocation provider,
            SchematicDocument accepted,
            List<Data> snapshot,
            Function<Context, T> inspection) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(accepted);
        Objects.requireNonNull(inspection);
        if (PatchTypes.provider(provider) == null)
            throw unavailable(provider, "provider is not registered");
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data inspection");
        try (var context =
                new Window(
                        provider,
                        accepted,
                        accepted,
                        identity(Kind.EDIT),
                        snapshot,
                        snapshot,
                        null,
                        null)) {
            return callback(() -> inspection.apply(context));
        }
    }

    /**
     * One provider's detached editor proposal, accepted through the same author-data validation as
     * capture/transformation. This returns a candidate only: history, stale editor revisions and
     * publication remain with the existing owner. No source/world mutation is permitted here.
     */
    public static SchematicDocument edit(
            ResourceLocation provider,
            SchematicDocument accepted,
            Function<Context, Proposal> edit) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(accepted);
        Objects.requireNonNull(edit);
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data edit");
        if (PatchTypes.authoring(provider) == null || !available(provider))
            throw unavailable(provider, "registered author-data capability is unavailable");
        requireLayerFrames(accepted);
        var data = read(accepted);
        var operation = identity(Kind.EDIT);
        var states = new SchematicStateProposals(accepted, accepted, operation);
        Proposal proposal;
        try (var context =
                new Window(provider, accepted, accepted, operation, data, data, null, null)) {
            proposal =
                    callback(
                            () -> {
                                states.claims(
                                        provider,
                                        context.records(),
                                        PatchTypes.authoring(provider).blockStateClaims(context));
                                return edit.apply(context);
                            });
            requireProposal(provider, context, proposal);
            states.states(provider, context.stateEdits);
            states.fields(provider, proposal.edits());
        }
        var next = new ArrayList<>(data);
        next.removeIf(value -> provider.equals(value.identity().type()));
        next.addAll(proposal.data());
        return accept(
                accepted,
                accepted,
                next,
                proposal.edits(),
                Map.of(),
                proposal.edits().isEmpty() && !states.edited(provider)
                        ? Set.of()
                        : Set.of(provider),
                null,
                PatchPolicy.defaults(),
                states,
                Set.of(),
                0,
                operation,
                null);
    }

    private static void requireProposal(
            ResourceLocation provider, Window context, Proposal proposal) {
        if (proposal == null || !context.revision().equals(proposal.revision()))
            throw unavailable(provider, "stale or foreign document revision in proposal");
        if (context.stateProposal != null && context.stateProposal != proposal)
            throw unavailable(
                    provider, "BlockState edits must remain bound to the returned proposal");
        for (var data : proposal.data())
            if (!provider.equals(data.identity().type()))
                throw unavailable(provider, "proposal writes another provider's data");
    }

    public static SchematicDocument inspectImport(
            CompoundTag original, SchematicDocument converted) {
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data import");
        if (PatchTypes.authoringProviders().isEmpty() && !present(converted)) return converted;
        return run(
                converted,
                converted,
                identity(Kind.IMPORT),
                null,
                null,
                original,
                true,
                PatchPolicy.defaults());
    }

    /** Existing internal capture entry point; claims need the earlier native-host session. */
    public static SchematicDocument capture(
            ServerLevel level, BlockPos origin, SchematicDocument doc) {
        try (var session = beginCapture(level, origin, doc, List::of)) {
            return session.complete(doc);
        }
    }

    /**
     * One authorized capture, policy and source lifetime. No second registry or capture scheduler.
     */
    public static CaptureSession beginCapture(
            ServerLevel level,
            BlockPos origin,
            SchematicDocument doc,
            Supplier<List<CaptureAspect>> offers) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Author capture requires the server thread");
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data capture");
        if (!SchematicCaptureReceipts.requiredIdentities(doc).isEmpty())
            throw new IllegalArgumentException(
                    "Recapture already owns required delegated data; capture a fresh native source instead");
        return new CaptureSession(level, origin, doc, offers);
    }

    public static final class CaptureSession implements AutoCloseable {
        private final PatchPolicy policy =
                com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy();
        private final Thread thread = Thread.currentThread();
        private LiveSource source;
        private SchematicDocument original;
        private ResourceMemoryBudget budget;
        private List<CaptureAspect> aspects = List.of();
        private final Map<UUID, CaptureAspect> byToken = new LinkedHashMap<>();
        private final Map<ResourceLocation, List<CaptureClaim>> claims = new LinkedHashMap<>();
        private final Set<UUID> replaced = new HashSet<>();
        private final Map<ResourceLocation, Set<BlockPos>> replacedPositions = new HashMap<>();
        private final Map<
                        com.eynny.npships.api.patches.PatchAuthoring.CaptureField, ResourceLocation>
                fieldOwners = new HashMap<>();
        private final Map<
                        ResourceLocation,
                        Set<com.eynny.npships.api.patches.PatchAuthoring.CaptureField>>
                requiredFields = new HashMap<>();
        private boolean complete;

        private CaptureSession(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument doc,
                Supplier<List<CaptureAspect>> offers) {
            original = doc;
            if (PatchTypes.authoringProviders().isEmpty()) return;
            source = new LiveSource(level, origin, doc.size());
            budget = ServerResourcePolicy.memory(level.getServer());
            try {
                aspects = List.copyOf(offers.get());
                for (var aspect : aspects) {
                    if (byToken.putIfAbsent(aspect.token(), aspect) != null)
                        throw new IllegalArgumentException("Duplicate host capture aspect");
                }
                var available = new TreeSet<ResourceLocation>();
                for (var id : PatchTypes.authoringProviders().keySet())
                    if (policy.enabled(id)
                            && SchematicExtensions.available(id, policy, new HashSet<>()))
                        available.add(id);
                var data = read(doc);
                var fields =
                        new HashSet<com.eynny.npships.api.patches.PatchAuthoring.CaptureField>();
                for (var id :
                        PatchOrdering.discoveryOrder(
                                PatchStage.AUTHOR_PREPARATION, available, policy)) {
                    try (var context =
                            new Window(
                                    id,
                                    doc,
                                    doc,
                                    identity(Kind.CAPTURE),
                                    data,
                                    data,
                                    source,
                                    null)) {
                        context.captureAspects = aspects;
                        context.capturePlanning = true;
                        var requested =
                                List.copyOf(
                                        callback(
                                                () ->
                                                        PatchTypes.authoring(id)
                                                                .captureClaims(context)));
                        var seen = new HashSet<UUID>();
                        for (var claim : requested) {
                            var aspect = byToken.get(claim.aspect());
                            if (aspect == null
                                    || !seen.add(claim.aspect())
                                    || !claim.owner().type().equals(id))
                                throw unavailable(
                                        id, "foreign/stale/duplicate capture ownership claim");
                            if (!policy.enabled(aspect.owner())
                                    || !SchematicExtensions.available(
                                            aspect.owner(), policy, new HashSet<>()))
                                throw unavailable(
                                        id,
                                        "default capture owner is disabled/unavailable: "
                                                + aspect.owner());
                            if (claim.mode() == CaptureMode.REPLACE) {
                                if (!aspect.delegable())
                                    throw unavailable(
                                            id,
                                            "capture aspect is not delegable: " + aspect.aspect());
                                SchematicCaptureReceipts.requireRuntime(id, policy);
                                if (!replaced.add(aspect.token()))
                                    throw unavailable(id, "conflicting exclusive capture owners");
                                for (var field : aspect.fields()) {
                                    if (!fields.add(field))
                                        throw unavailable(
                                                id, "overlapping exclusive native capture fields");
                                    fieldOwners.put(field, id);
                                    requiredFields
                                            .computeIfAbsent(id, ignored -> new HashSet<>())
                                            .add(field);
                                }
                                replacedPositions
                                        .computeIfAbsent(aspect.owner(), ignored -> new HashSet<>())
                                        .addAll(aspect.positions());
                            }
                        }
                        if (!requested.isEmpty()) claims.put(id, requested);
                    }
                }
                var activeTypes = new TreeSet<>(claims.keySet());
                if (!claims.isEmpty())
                    for (var aspect : aspects)
                        if (!replaced.contains(aspect.token())) activeTypes.add(aspect.owner());
                var active =
                        activeTypes.stream()
                                .map(
                                        id ->
                                                new PatchIdentity(
                                                        id,
                                                        UUID.nameUUIDFromBytes(
                                                                ("capture-owner/" + id)
                                                                        .getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .UTF_8)),
                                                        null,
                                                        null))
                                .toList();
                PatchOrdering.order(PatchStage.AUTHOR_PREPARATION, active, policy);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void require() {
            if (original == null || Thread.currentThread() != thread)
                throw new IllegalStateException("Capture ownership session ended or wrong thread");
        }

        public boolean hasReplacements() {
            require();
            return !replaced.isEmpty();
        }

        public boolean replaces(ResourceLocation owner, BlockPos position) {
            require();
            return replacedPositions.getOrDefault(owner, Set.of()).contains(position);
        }

        public SchematicDocument complete(SchematicDocument captured) {
            require();
            if (complete) throw new IllegalStateException("Capture already completed");
            complete = true;
            if (source == null) return captured;
            return run(
                    captured,
                    captured,
                    identity(Kind.CAPTURE),
                    source,
                    budget,
                    null,
                    true,
                    policy,
                    Set.of(),
                    0,
                    this);
        }

        private void validateProposal(ResourceLocation id, Proposal proposal) {
            var removed = new HashSet<com.eynny.npships.api.patches.PatchAuthoring.CaptureField>();
            for (var edit : proposal.edits()) {
                var key =
                        new com.eynny.npships.api.patches.PatchAuthoring.CaptureField(
                                edit.position(), edit.key());
                var owner = fieldOwners.get(key);
                if (owner == null) continue;
                if (!owner.equals(id) || edit.replacement() != null)
                    throw unavailable(
                            id,
                            "native capture field belongs to its exclusive owner and must be CAS-removed: "
                                    + key);
                removed.add(key);
            }
            for (var field : requiredFields.getOrDefault(id, Set.of()))
                if (!removed.contains(field))
                    throw unavailable(
                            id, "delegated native field has no owned CAS removal: " + field);
        }

        private SchematicDocument attach(SchematicDocument doc, List<Data> acceptedData) {
            var receipts = new ArrayList<SchematicCaptureReceipts.Receipt>();
            var values = new HashMap<PatchIdentity, Data>();
            for (var data : acceptedData) values.put(data.identity(), data);
            for (var group : claims.entrySet())
                for (var claim : group.getValue()) {
                    var data = values.get(claim.owner());
                    if (data == null || !data.name().equals(claim.record()))
                        throw unavailable(
                                group.getKey(),
                                "capture claim did not produce its exact named representation");
                    if (claim.mode() == CaptureMode.REPLACE)
                        receipts.add(
                                new SchematicCaptureReceipts.Receipt(
                                        data, byToken.get(claim.aspect())));
                }
            var result = SchematicCaptureReceipts.attach(doc, receipts);
            SchematicCaptureReceipts.validate(result, true, policy);
            return result;
        }

        public void close() {
            if (source != null) source.close();
            source = null;
            original = null;
            budget = null;
            aspects = List.of();
            byToken.clear();
            claims.clear();
            replaced.clear();
            replacedPositions.clear();
            fieldOwners.clear();
            requiredFields.clear();
        }
    }

    /**
     * No runtime factories, native world access, mutation proposals or recapture during discovery.
     */
    public static void validate(SchematicDocument doc, boolean runtime) {
        validate(doc, runtime, Kind.VALIDATE);
    }

    public static void validate(SchematicDocument doc, boolean runtime, Kind kind) {
        validate(
                doc,
                runtime,
                kind,
                runtime
                        ? com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy()
                        : PatchPolicy.defaults());
    }

    public static void validate(
            SchematicDocument doc, boolean runtime, Kind kind, PatchPolicy policy) {
        if (kind != Kind.VALIDATE && kind != Kind.SAVE && kind != Kind.EXPORT)
            throw new IllegalArgumentException("Not a read-only validation boundary: " + kind);
        if (INTERMEDIATE.get() != 0) return;
        requireLayerFrames(doc);
        SchematicCaptureReceipts.validate(doc, runtime, policy);
        if (!present(doc)) return;
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data validation");
        var records = read(doc);
        var validationTypes = new TreeSet<ResourceLocation>();
        for (var id : records.stream().map(v -> v.identity().type()).distinct().sorted().toList()) {
            var definition = PatchTypes.provider(id);
            if (!policy.enabled(id)) {
                if (runtime
                        && (definition == null
                                || definition.registration().disabledBehavior()
                                        != PatchRegistration.DisabledBehavior.SKIP))
                    throw unavailable(id, "disabled by SERVER policy");
                continue;
            }
            var authoring = PatchTypes.authoring(id);
            if (authoring == null) {
                if (runtime) throw unavailable(id, "author-data capability is absent");
                continue;
            }
            if (!available(id, policy, new HashSet<>())) {
                if (runtime) throw unavailable(id, "required native mod is unavailable");
                continue;
            }
            validationTypes.add(id);
        }
        if (runtime)
            PatchOrdering.order(
                    PatchStage.VALIDATION,
                    validationTypes.stream()
                            .map(
                                    id ->
                                            new PatchIdentity(
                                                    id,
                                                    UUID.nameUUIDFromBytes(
                                                            ("author-validation/" + id)
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)),
                                                    null,
                                                    null))
                            .toList(),
                    policy);
        for (var id :
                PatchOrdering.discoveryOrder(PatchStage.VALIDATION, validationTypes, policy)) {
            var authoring = PatchTypes.authoring(id);
            List<Data> validationRecords = records;
            var schemas = PatchTypes.payloads(id);
            if (schemas != null && !schemas.authorData().isEmpty()) {
                var canonical = upgradedData(doc, id, tag -> {});
                var merged = new ArrayList<Data>(records.size());
                for (var record : records)
                    if (!id.equals(record.identity().type())) merged.add(record);
                merged.addAll(canonical);
                validationRecords = List.copyOf(merged);
            }
            try (var context =
                    new Window(
                            id,
                            doc,
                            doc,
                            identity(kind),
                            validationRecords,
                            validationRecords,
                            null,
                            null)) {
                // The validation window already owns its canonical provider-local view. Avoid
                // converting the same historic records again when validate uses upgradedRecords.
                context.upgraded = context.records();
                callback(
                        () -> {
                            authoring.validate(context);
                            return null;
                        });
            }
        }
    }

    private static void requireLayerFrames(SchematicDocument doc) {
        if (!doc.hasMetadata(BlockAreaLayers.KEY)) return;
        var areas = doc.metadataReadOnly(BlockAreaLayers.KEY);
        for (var item : areas.getList("layers", Tag.TAG_COMPOUND)) {
            var layer = (CompoundTag) item;
            for (String field : List.of("source", "content")) {
                var packed = layer.getCompound(field);
                var metadata =
                        packed.contains("metadata", Tag.TAG_COMPOUND)
                                ? packed.getCompound("metadata")
                                : packed;
                if (metadata.contains(KEY))
                    throw new IllegalArgumentException(
                            "Author data in a saved layer-local "
                                    + field
                                    + " has no supported document-frame projection; operation refused, original retained");
            }
        }
    }

    private static boolean available(ResourceLocation id) {
        return available(id, PatchPolicy.defaults(), new HashSet<>());
    }

    private static boolean available(
            ResourceLocation id, PatchPolicy policy, Set<ResourceLocation> visited) {
        if (!visited.add(id)) return true;
        var provider = PatchTypes.provider(id);
        return provider != null
                && policy.enabled(id)
                && provider.requiredMods().stream().allMatch(mod -> ModList.get().isLoaded(mod))
                && provider.registration().requires().stream()
                        .allMatch(required -> available(required, policy, visited));
    }

    private static IllegalArgumentException unavailable(ResourceLocation id, String reason) {
        return new IllegalArgumentException(
                "Author data provider "
                        + id
                        + ": "
                        + reason
                        + "; payload retained, operation refused");
    }

    private static <T> T callback(Supplier<T> callback) {
        if (CALLBACK.get() || PayloadEvolution.inCallback())
            throw new IllegalStateException("Reentrant author-data callback");
        CALLBACK.set(true);
        try {
            return callback.get();
        } finally {
            CALLBACK.remove();
        }
    }

    private static SchematicDocument run(
            SchematicDocument before,
            SchematicDocument candidate,
            Operation operation,
            LiveSource source,
            ResourceMemoryBudget budget,
            CompoundTag original,
            boolean discover,
            PatchPolicy policy) {
        return run(
                before, candidate, operation, source, budget, original, discover, policy, Set.of(),
                0);
    }

    private static SchematicDocument run(
            SchematicDocument before,
            SchematicDocument candidate,
            Operation operation,
            LiveSource source,
            ResourceMemoryBudget budget,
            CompoundTag original,
            boolean discover,
            PatchPolicy policy,
            Set<BlockPos> deferred,
            int mirrorAxes) {
        return run(
                before,
                candidate,
                operation,
                source,
                budget,
                original,
                discover,
                policy,
                deferred,
                mirrorAxes,
                null);
    }

    private static SchematicDocument run(
            SchematicDocument before,
            SchematicDocument candidate,
            Operation operation,
            LiveSource source,
            ResourceMemoryBudget budget,
            CompoundTag original,
            boolean discover,
            PatchPolicy policy,
            Set<BlockPos> deferred,
            int mirrorAxes,
            CaptureSession capture) {
        var states = new SchematicStateProposals(before, candidate, operation);
        var beforeData = read(before);
        var candidateData = before == candidate ? beforeData : read(candidate);
        var nextData = new ArrayList<>(candidateData);
        var types = new TreeSet<ResourceLocation>();
        beforeData.forEach(value -> types.add(value.identity().type()));
        nextData.forEach(value -> types.add(value.identity().type()));
        if (discover) types.addAll(PatchTypes.authoringProviders().keySet());
        var edits = new ArrayList<FieldEdit>();
        var originals = new HashMap<UUID, UUID>();
        var identities = new ArrayList<PatchIdentity>();
        for (var id : types) {
            if (!policy.enabled(id)) {
                var definition = PatchTypes.provider(id);
                boolean owned =
                        beforeData.stream().anyMatch(d -> d.identity().type().equals(id))
                                || nextData.stream().anyMatch(d -> d.identity().type().equals(id));
                if (owned
                        && (definition == null
                                || definition.registration().disabledBehavior()
                                        != PatchRegistration.DisabledBehavior.SKIP))
                    throw unavailable(id, "disabled by SERVER policy");
                continue;
            }
            if (PatchTypes.authoring(id) == null || !available(id, policy, new HashSet<>())) {
                if (beforeData.stream().anyMatch(d -> d.identity().type().equals(id))
                        || nextData.stream().anyMatch(d -> d.identity().type().equals(id)))
                    throw unavailable(id, "required capture/transform capability is unavailable");
                continue;
            }
            identities.add(
                    new PatchIdentity(
                            id,
                            UUID.nameUUIDFromBytes(
                                    ("author-operation/" + id)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            null,
                            null));
        }
        var candidates = new TreeSet<ResourceLocation>();
        identities.forEach(value -> candidates.add(value.type()));
        var ordered =
                PatchOrdering.discoveryOrder(PatchStage.AUTHOR_PREPARATION, candidates, policy);
        var editedTypes = new TreeSet<ResourceLocation>();
        for (var id : ordered) {
            var provider = PatchTypes.authoring(id);
            List<Data> owned =
                    beforeData.stream().filter(d -> d.identity().type().equals(id)).toList();
            Proposal proposal;
            try (var context =
                    new Window(
                            id,
                            before,
                            candidate,
                            operation,
                            beforeData,
                            candidateData,
                            source,
                            original)) {
                if (capture != null) {
                    context.captureAspects = capture.aspects;
                    context.captureClaims = capture.claims.getOrDefault(id, List.of());
                }
                proposal =
                        callback(
                                () -> {
                                    if (!discover)
                                        states.claims(
                                                id,
                                                context.records(),
                                                provider.blockStateClaims(context));
                                    return discover
                                            ? provider.capture(context)
                                            : provider.transform(context);
                                });
                requireProposal(id, context, proposal);
                if (capture != null) capture.validateProposal(id, proposal);
                states.states(id, context.stateEdits);
                states.fields(id, proposal.edits());
            }
            for (var old : owned) {
                var mapped = operation.mapping().identity(old.identity());
                originals.put(mapped.instanceId(), old.identity().instanceId());
            }
            // The provider owns the meaning of its references, selection and absence. A complete
            // provider-local replacement permits removal without host guesses about foreign fields.
            nextData.removeIf(value -> id.equals(value.identity().type()));
            nextData.addAll(proposal.data());
            edits.addAll(proposal.edits());
            if (!proposal.edits().isEmpty() || states.edited(id)) editedTypes.add(id);
        }
        return accept(
                before,
                candidate,
                nextData,
                edits,
                originals,
                editedTypes,
                budget,
                policy,
                states,
                deferred,
                mirrorAxes,
                operation,
                capture);
    }

    private static SchematicDocument accept(
            SchematicDocument before,
            SchematicDocument candidate,
            List<Data> nextData,
            List<FieldEdit> edits,
            Map<UUID, UUID> originals,
            Set<ResourceLocation> editedTypes,
            ResourceMemoryBudget budget,
            PatchPolicy policy,
            SchematicStateProposals states,
            Set<BlockPos> deferred,
            int mirrorAxes,
            Operation operation,
            CaptureSession capture) {
        states.validateFields(nextData);
        states.requireMirrorFields(deferred, mirrorAxes);
        // Discovery alone is not applicability. Refuse conflicts only for retained/proposed data
        // or actual native-field proposals, before any changed document can escape this owner.
        var active = new TreeSet<ResourceLocation>(editedTypes);
        // An unrelated local edit preserves absent providers opaquely. They have no executable
        // ordering metadata; transformations already refuse them before reaching acceptance.
        nextData.forEach(
                value -> {
                    if (PatchTypes.provider(value.identity().type()) != null)
                        active.add(value.identity().type());
                });
        PatchOrdering.order(
                PatchStage.AUTHOR_PREPARATION,
                active.stream()
                        .map(
                                id ->
                                        new PatchIdentity(
                                                id,
                                                UUID.nameUUIDFromBytes(
                                                        ("author-active/" + id)
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)),
                                                null,
                                                null))
                        .toList(),
                policy);
        // Validate the complete immutable candidate only after every provider has proposed
        // successfully.
        ResourceMemoryBudget.Scope reservation =
                budget == null ? null : budget.scope("author-data proposal/copy payload");
        try {
            if (reservation != null)
                for (var value : nextData) {
                    var weight = PreparedTemplateResources.measure(value.payload());
                    reservation.reserve(Math.addExact(weight.mutableBytes(), weight.textBytes()));
                }
            var result = write(candidate, before, nextData, originals);
            result = states.apply(result, fields(result, edits));
            result =
                    SchematicCaptureReceipts.transfer(
                            before, candidate, result, operation, originals);
            if (capture != null) result = capture.attach(result, nextData);
            read(result);
            validate(result, false, Kind.VALIDATE, policy);
            return result;
        } finally {
            // Only this operation's transient copies are accounted here. Editor history/native
            // addon
            // allocations remain the existing explicit D3 boundary; runtime/catalogue owners
            // re-admit.
            if (reservation != null) reservation.close();
        }
    }

    private static Map<BlockPos, CompoundTag> fields(SchematicDocument doc, List<FieldEdit> edits) {
        if (edits.isEmpty()) return Map.of();
        var keys = new HashSet<String>();
        var changes = new HashMap<BlockPos, CompoundTag>();
        for (var edit : edits) {
            String key = edit.key();
            if (Set.of("id", "x", "y", "z").contains(key)
                    || key.startsWith("npships")
                    || key.startsWith("NPShips"))
                throw new IllegalArgumentException(
                        "Author proposal cannot edit host/native ownership field " + key);
            if (!keys.add(edit.position() + "/" + key))
                throw new IllegalArgumentException(
                        "Conflicting provider BE field proposals at "
                                + edit.position()
                                + "/"
                                + key);
            var entry = changes.computeIfAbsent(edit.position(), doc::blockEntryCopy);
            if (entry == null || !entry.contains("nbt", Tag.TAG_COMPOUND))
                throw new IllegalArgumentException(
                        "Missing proposal BlockEntity target " + edit.position());
            var data = entry.getCompound("nbt");
            if (!Objects.equals(data.get(key), edit.expected()))
                throw new IllegalArgumentException(
                        "Stale BlockEntity field proposal " + edit.position() + "/" + key);
            if (edit.replacement() == null) data.remove(key);
            else data.put(key, edit.replacement());
        }
        return changes;
    }

    public static Operation operation(
            Kind kind,
            Function<BlockPos, List<BlockPos>> cells,
            Function<Vec3, List<Vec3>> points,
            UnaryOperator<PatchIdentity> identities) {
        return operation(kind, cells, points, identities, ignored -> Optional.empty());
    }

    public static Operation operation(
            Kind kind,
            Function<BlockPos, List<BlockPos>> cells,
            Function<Vec3, List<Vec3>> points,
            UnaryOperator<PatchIdentity> identities,
            Function<UUID, Optional<UUID>> roots) {
        return new Operation(
                kind,
                new Mapping() {
                    public List<BlockPos> cells(BlockPos p) {
                        return List.copyOf(cells.apply(p));
                    }

                    public List<Vec3> points(Vec3 p) {
                        return List.copyOf(points.apply(p));
                    }

                    public PatchIdentity identity(PatchIdentity id) {
                        return Objects.requireNonNull(identities.apply(id));
                    }

                    public Optional<UUID> root(UUID id) {
                        return roots.apply(id);
                    }
                },
                null,
                null,
                null);
    }

    public static Operation identity(Kind kind) {
        return operation(kind, List::of, List::of, UnaryOperator.identity(), Optional::of);
    }

    public static Operation translate(Kind kind, BlockPos delta) {
        return operation(
                kind,
                p -> List.of(p.offset(delta)),
                p -> List.of(p.add(delta.getX(), delta.getY(), delta.getZ())),
                UnaryOperator.identity(),
                Optional::of);
    }

    public static Operation turn(BlockPos size, boolean right) {
        return operation(
                Kind.TURN,
                p ->
                        List.of(
                                right
                                        ? new BlockPos(
                                                size.getZ() - 1 - p.getZ(), p.getY(), p.getX())
                                        : new BlockPos(
                                                p.getZ(), p.getY(), size.getX() - 1 - p.getX())),
                p ->
                        List.of(
                                right
                                        ? new Vec3(size.getZ() - p.z, p.y, p.x)
                                        : new Vec3(p.z, p.y, size.getX() - p.x)),
                UnaryOperator.identity(),
                Optional::of);
    }

    public static Operation mirror(BlockPos size, int axes) {
        return operation(
                Kind.MIRROR,
                p ->
                        List.of(
                                new BlockPos(
                                        (axes & 1) == 0 ? p.getX() : size.getX() - 1 - p.getX(),
                                        (axes & 2) == 0 ? p.getY() : size.getY() - 1 - p.getY(),
                                        (axes & 4) == 0 ? p.getZ() : size.getZ() - 1 - p.getZ())),
                p ->
                        List.of(
                                new Vec3(
                                        (axes & 1) == 0 ? p.x : size.getX() - p.x,
                                        (axes & 2) == 0 ? p.y : size.getY() - p.y,
                                        (axes & 4) == 0 ? p.z : size.getZ() - p.z)),
                UnaryOperator.identity(),
                Optional::of);
    }

    public static Operation clip(Kind kind, BlockPos origin, BlockPos size, BlockPos delta) {
        return new Operation(
                kind,
                operation(
                                kind,
                                p -> inside(p, origin, size) ? List.of(p.offset(delta)) : List.of(),
                                p ->
                                        inside(BlockPos.containing(p), origin, size)
                                                ? List.of(
                                                        p.add(
                                                                delta.getX(),
                                                                delta.getY(),
                                                                delta.getZ()))
                                                : List.of(),
                                UnaryOperator.identity(),
                                Optional::of)
                        .mapping(),
                origin,
                size,
                null);
    }

    private static boolean inside(BlockPos p, BlockPos o, BlockPos s) {
        return p.getX() >= o.getX()
                && (long) p.getX() < (long) o.getX() + s.getX()
                && p.getY() >= o.getY()
                && (long) p.getY() < (long) o.getY() + s.getY()
                && p.getZ() >= o.getZ()
                && (long) p.getZ() < (long) o.getZ() + s.getZ();
    }

    private static final class View implements Document {
        private SchematicDocument document;
        private List<Data> data;
        private final Thread thread = Thread.currentThread();
        private final List<Cursor> cursors = new ArrayList<>();

        private final class Cursor implements Iterator<BlockPos> {
            private Iterator<BlockPos> iterator;

            Cursor() {
                iterator = require().blocks().keySet().iterator();
            }

            public boolean hasNext() {
                require();
                return iterator.hasNext();
            }

            public BlockPos next() {
                require();
                return iterator.next();
            }

            void close() {
                iterator = null;
            }
        }

        View(SchematicDocument document, List<Data> data) {
            this.document = document;
            this.data = data;
        }

        private SchematicDocument require() {
            if (document == null || Thread.currentThread() != thread)
                throw new IllegalStateException("Author document callback ended or wrong thread");
            return document;
        }

        public BlockPos size() {
            return require().size();
        }

        public String blockName(BlockPos p) {
            var d = require();
            if (!d.contains(p)) throw new IllegalArgumentException("Read outside document");
            return d.blockName(p);
        }

        public CompoundTag blockData(BlockPos p) {
            var d = require();
            if (!d.contains(p)) throw new IllegalArgumentException("Read outside document");
            return d.blockEntityData(p);
        }

        public CompoundTag blockState(BlockPos p) {
            var d = require();
            if (!d.contains(p)) throw new IllegalArgumentException("Read outside document");
            return d.extensionBlockState(p);
        }

        boolean hasRoot(UUID root) {
            var d = require();
            for (var tag : d.extensionEntitiesReadOnly())
                if (root.equals(
                        com.eynny.npships.authoring.patches.EntityIdentity.read(
                                ((CompoundTag) tag).getCompound("nbt")))) return true;
            return false;
        }

        public List<UUID> entityRoots() {
            var d = require();
            var result = new ArrayList<UUID>();
            for (var tag : d.extensionEntitiesReadOnly()) {
                var id =
                        com.eynny.npships.authoring.patches.EntityIdentity.read(
                                ((CompoundTag) tag).getCompound("nbt"));
                if (id != null) result.add(id);
            }
            return List.copyOf(result);
        }

        public Optional<CompoundTag> entityData(UUID root) {
            var d = require();
            for (var tag : d.extensionEntitiesReadOnly()) {
                var entry = (CompoundTag) tag;
                if (root.equals(
                        com.eynny.npships.authoring.patches.EntityIdentity.read(
                                entry.getCompound("nbt")))) return Optional.of(entry.copy());
            }
            return Optional.empty();
        }

        public Iterable<BlockPos> positions() {
            require();
            // The returned iterable cannot retain an unguarded iterator into the document graph.
            return () -> {
                var cursor = new Cursor();
                cursors.add(cursor);
                return cursor;
            };
        }

        public List<Data> data(ResourceLocation id) {
            var current = require();
            Objects.requireNonNull(id);
            return data == null
                    ? read(current, id, tag -> {})
                    : data.stream().filter(d -> d.identity().type().equals(id)).toList();
        }

        void close() {
            document = null;
            data = null;
            for (var cursor : cursors) cursor.close();
            cursors.clear();
        }
    }

    private static final class Window implements Context, AutoCloseable {
        private final UUID revision = UUID.randomUUID();
        private final ResourceLocation type;
        private View before, after;
        private Operation operation;
        private List<Data> records, upgraded;
        private Proposal stateProposal;
        private List<BlockStateEdit> stateEdits = List.of();
        private List<CaptureAspect> captureAspects = List.of();
        private List<CaptureClaim> captureClaims = List.of();
        private boolean capturePlanning;
        private LiveSource source;
        private CompoundTag original;
        private final Thread thread = Thread.currentThread();

        Window(
                ResourceLocation type,
                SchematicDocument old,
                SchematicDocument next,
                Operation operation,
                List<Data> data,
                List<Data> candidateData,
                LiveSource source,
                CompoundTag original) {
            this.type = type;
            this.before = new View(old, data);
            this.after = new View(next, candidateData);
            this.operation = operation;
            this.records =
                    data == null
                            ? null
                            : data.stream().filter(d -> d.identity().type().equals(type)).toList();
            this.source = source;
            this.original = original;
        }

        private void require() {
            if (before == null || Thread.currentThread() != thread)
                throw new IllegalStateException("Author callback ended or wrong thread");
        }

        public Operation operation() {
            require();
            // A retained descriptor cannot bypass the callback window through its functional map.
            return new Operation(
                    operation.kind(),
                    new Mapping() {
                        public List<BlockPos> cells(BlockPos p) {
                            require();
                            return operation.mapping().cells(p);
                        }

                        public List<Vec3> points(Vec3 p) {
                            require();
                            return operation.mapping().points(p);
                        }

                        public PatchIdentity identity(PatchIdentity id) {
                            require();
                            return operation.mapping().identity(id);
                        }

                        public Optional<UUID> root(UUID id) {
                            require();
                            return operation.mapping().root(id).filter(after::hasRoot);
                        }
                    },
                    operation.selectionOrigin(),
                    operation.selectionSize(),
                    operation.occurrence());
        }

        public Document before() {
            require();
            return before;
        }

        public Document after() {
            require();
            return after;
        }

        public List<CaptureAspect> captureAspects() {
            require();
            return captureAspects;
        }

        public List<CaptureClaim> captureClaims() {
            require();
            return captureClaims;
        }

        public Source source() {
            require();
            return source == null
                    ? null
                    : new Source() {
                        public ResourceLocation dimension() {
                            require();
                            return source.dimension();
                        }

                        public BlockPos worldOrigin() {
                            require();
                            return source.worldOrigin();
                        }

                        public CompoundTag blockData(BlockPos p) {
                            require();
                            return source.blockData(p);
                        }

                        public String blockName(BlockPos p) {
                            require();
                            return source.blockName(p);
                        }
                    };
        }

        public CompoundTag importSource() {
            require();
            return original == null ? null : original.copy();
        }

        public List<Data> records() {
            require();
            if (records == null) records = before.data(type);
            return records;
        }

        public List<Data> upgradedRecords() {
            require();
            if (upgraded == null) upgraded = upgradedData(before.require(), type, tag -> {});
            return upgraded;
        }

        public UUID revision() {
            require();
            return revision;
        }

        public Proposal propose(List<Data> data, List<FieldEdit> edits) {
            require();
            if (capturePlanning)
                throw new IllegalStateException(
                        "Capture ownership planning cannot propose author changes");
            return new Proposal(revision, data, edits);
        }

        public Proposal propose(
                List<Data> data, List<FieldEdit> edits, List<BlockStateEdit> states) {
            require();
            Objects.requireNonNull(states);
            if (states.isEmpty()) return propose(data, edits);
            if (stateProposal != null)
                throw new IllegalArgumentException(
                        "Only one BlockState-bearing proposal per callback is supported");
            if (operation.kind() == Kind.CAPTURE
                    || operation.kind() == Kind.IMPORT
                    || operation.kind() == Kind.SAVE
                    || operation.kind() == Kind.EXPORT
                    || operation.kind() == Kind.VALIDATE)
                throw new IllegalArgumentException(
                        "BlockState proposals require an existing authored transform/edit target");
            stateEdits = List.copyOf(states);
            stateProposal = propose(data, edits);
            return stateProposal;
        }

        public Proposal unchanged() {
            require();
            return propose(after.data(type), List.of());
        }

        public void close() {
            if (before != null) {
                before.close();
                after.close();
                before = null;
                after = null;
                operation = null;
                records = null;
                upgraded = null;
                stateProposal = null;
                stateEdits = List.of();
                captureAspects = List.of();
                captureClaims = List.of();
                source = null;
                original = null;
            }
        }
    }

    private static final class LiveSource implements Source, AutoCloseable {
        private ServerLevel level;
        private final BlockPos origin, size;
        private final Map<BlockPos, CompoundTag> snapshots = new HashMap<>();
        private final ResourceMemoryBudget.Scope reservation;

        LiveSource(ServerLevel level, BlockPos origin, BlockPos size) {
            this.level = level;
            this.origin = origin.immutable();
            this.size = size;
            reservation =
                    ServerResourcePolicy.memory(level.getServer())
                            .scope("author capture source snapshots");
        }

        private void require() {
            if (level == null || !level.getServer().isSameThread())
                throw new IllegalStateException("Native capture callback ended or wrong thread");
        }

        private BlockPos position(BlockPos local) {
            require();
            if (!inside(local, BlockPos.ZERO, size))
                throw new IllegalArgumentException("Native capture read outside selection");
            BlockPos p = origin.offset(local);
            if (level.isOutsideBuildHeight(p)
                    || !level.getWorldBorder().isWithinBounds(p)
                    || !level.hasChunkAt(p))
                throw new IllegalArgumentException("Native capture position unavailable " + p);
            return p;
        }

        public ResourceLocation dimension() {
            require();
            return level.dimension().location();
        }

        public BlockPos worldOrigin() {
            require();
            return origin;
        }

        public String blockName(BlockPos local) {
            var p = position(local);
            // Do not retain a second map for every air/ordinary cell in a sparse selection.
            return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString();
        }

        public CompoundTag blockData(BlockPos local) {
            var p = position(local);
            var snapshot = snapshots.get(local);
            if (snapshot == null) {
                var entity = level.getBlockEntity(p);
                if (entity == null) return new CompoundTag();
                snapshot = entity.saveWithFullMetadata(level.registryAccess());
                var weight = PreparedTemplateResources.measure(snapshot);
                reservation.reserve(Math.addExact(weight.mutableBytes(), weight.textBytes()));
                // Declared primitive/reference slots of the immutable key and map entry;
                // object headers/table/native serializer allocation remain explicit D3 boundaries.
                reservation.reserve(Integer.BYTES * 4L + Long.BYTES * 3L);
                snapshots.put(local.immutable(), snapshot);
            }
            var weight = PreparedTemplateResources.measure(snapshot);
            reservation.reserve(weight.mutableBytes());
            return snapshot.copy();
        }

        public void close() {
            level = null;
            snapshots.clear();
            reservation.close();
        }
    }
}
