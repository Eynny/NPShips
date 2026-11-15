package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.api.patches.PatchException.Reason;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.ship.EntityPlacementScope;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Function;

/** One per-template executor; its filename predates document-scoped adapters. */
final class ExternalPatchHandlers implements AutoCloseable {
    private record Plan(
            JsonObject patch,
            boolean entity,
            PatchIdentity identity,
            PatchAdapter adapter,
            PatchBinding binding,
            Set<PatchStage> stages,
            Set<Class<?>> capabilities,
            Map<UUID, PatchType.EntityMode> roots,
            List<PatchPreparedInput.Claim> claims) {}

    private static final class Entry {
        final Plan plan;
        final PatchHandler handler;
        boolean validated, prepared;

        Entry(Plan plan, PatchHandler handler) {
            this.plan = plan;
            this.handler = handler;
        }

        boolean complete(PatchStage stage) {
            return stage == PatchStage.INPUT_VALIDATION
                    ? validated
                    : stage == PatchStage.PREPARE && prepared;
        }

        void completed(PatchStage stage) {
            if (stage == PatchStage.INPUT_VALIDATION) validated = true;
            if (stage == PatchStage.PREPARE) prepared = true;
        }
    }

    /** Authored records retain their planned identity through target preparation. */
    record Authored(JsonObject patch, boolean entity, PatchIdentity identity) {}

    private Map<PatchStage, List<Plan>> orders = new EnumMap<>(PatchStage.class);
    private Map<PatchStage, Integer> cursors = new EnumMap<>(PatchStage.class);
    private Map<UUID, Entry> acquired = new HashMap<>();
    private List<Entry> entries = new ArrayList<>();
    private IdentityHashMap<PatchHandler, Boolean> identities = new IdentityHashMap<>();
    private IdentityHashMap<JsonObject, Plan> targetPlans = new IdentityHashMap<>();
    private Set<BlockPos> footprint = new HashSet<>();
    private List<Authored> preparation = List.of();
    private SchematicDocument document;
    private ListTag palette;
    private Map<UUID, PatchTarget> entityTargets = Map.of();
    private Set<UUID> requiredRoots = Set.of();
    private Map<UUID, ResourceLocation> rootTypes = Map.of();
    private List<Plan> rootPlans = List.of();
    private boolean placementRequested;
    private boolean closed;
    private int callbacks, cleanupFailures;
    private boolean cleanupComplete, closingEntry;
    private PatchPolicy capturedPolicy;
    private Map<ResourceLocation, PatchNativePreparation> nativePreparation;
    private List<Map.Entry<ResourceLocation, PatchNativePreparation>> nativePreparationEntries;
    private Set<ResourceLocation> nativeProviders = new TreeSet<>();
    private Set<ResourceLocation> plannedProviders = new TreeSet<>();
    private Map<ResourceLocation, List<ResourceLocation>> singleNativeOrders = new HashMap<>();
    private Boolean inspectBareNative;
    private List<ResourceLocation> initialNativeOrder;
    private List<
                    Map.Entry<
                            ResourceLocation,
                            java.util.function.Consumer<
                                    dev.ryanhcode.sable.sublevel.ServerSubLevel>>>
            repairs;

    /** Plan authored target records without document-adapter discovery. */
    void plan(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy) {
        planInternal(doc, blocks, entities, policy, null, null, Map.of(), null);
    }

    /** Discover document adapters before acquiring any handler. */
    void plan(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets) {
        Objects.requireNonNull(discovery);
        planInternal(doc, blocks, entities, policy, discovery, palette, entityTargets, null);
    }

    void plan(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets,
            net.minecraft.nbt.CompoundTag nativeSource) {
        Objects.requireNonNull(discovery);
        planInternal(
                doc, blocks, entities, policy, discovery, palette, entityTargets, nativeSource);
    }

    private Planning pendingPlanning;
    private Map<ResourceLocation, List<PatchAuthoring.Data>> preparedAuthorData;

    /** Null only while detached discovery has not yet completed canonical author validation. */
    List<PatchAuthoring.Data> preparedAuthorData(ResourceLocation type) {
        return preparedAuthorData == null ? null : preparedAuthorData.getOrDefault(type, List.of());
    }

    /** Compatibility callers may conservatively retain scratch until their outer owner closes. */
    Planning beginPlan(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets,
            net.minecraft.nbt.CompoundTag nativeSource,
            java.util.function.LongConsumer reserve) {
        return beginPlan(
                doc,
                blocks,
                entities,
                policy,
                discovery,
                palette,
                entityTargets,
                nativeSource,
                reserve,
                ignored -> {});
    }

    Planning beginPlan(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets,
            net.minecraft.nbt.CompoundTag nativeSource,
            java.util.function.LongConsumer reserve,
            java.util.function.LongConsumer release) {
        requireOpen();
        if (!orders.isEmpty() || pendingPlanning != null)
            throw new IllegalStateException("Patch plan already owned");
        pendingPlanning =
                new Planning(
                        doc,
                        blocks,
                        entities,
                        policy,
                        discovery,
                        palette,
                        entityTargets,
                        nativeSource,
                        reserve,
                        release);
        return pendingPlanning;
    }

    private void planInternal(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets,
            net.minecraft.nbt.CompoundTag nativeSource) {
        try (var planning =
                beginPlan(
                        doc,
                        blocks,
                        entities,
                        policy,
                        discovery,
                        palette,
                        entityTargets,
                        nativeSource,
                        ignored -> {})) {
            while (!planning.step(() -> true)) {}
        }
    }

    /**
     * Detached planning owns no handler; every owned collection/graph pass yields to its caller.
     */
    final class Planning implements AutoCloseable {
        private enum Phase {
            BLOCKS,
            ENTITIES,
            DISCOVER,
            BINDING_COPY,
            BINDING_NEXT,
            BINDING_POSITIONS,
            BINDING_VALIDATE,
            CAPTURE_CAPABILITIES,
            CAPTURE_ROOTS,
            CAPTURE_CLAIMS,
            BINDING_COMMIT,
            RECEIPTS,
            READ_EXTENSIONS,
            INDEX,
            CHECK_RECEIPTS,
            RECEIPT_POSITIONS,
            POLICY,
            POLICY_PROVIDERS,
            VALIDATE_EXTENSIONS,
            FOOTPRINT,
            TARGETS,
            DECLARATIONS,
            CHECK_CAPABILITIES,
            NATIVE_ROOTS,
            CHECK_ROOTS,
            DECLARE,
            SEAL,
            ORDER_START,
            ORDER_INDEX,
            ORDER,
            ORDER_OUTPUT,
            RETAIN,
            PREPARATION,
            COMMIT,
            DONE
        }

        private final SchematicDocument doc;
        private final List<JsonObject> blocks, entityPatches;
        private final PatchPolicy policy;
        private final PatchExecutionContext discovery;
        private final ListTag sourcePalette;
        private final Map<UUID, PatchTarget> sourceTargets;
        private final net.minecraft.nbt.CompoundTag nativeSource;
        private final java.util.function.LongConsumer reserve, release;
        private com.eynny.npships.authoring.document.SchematicExtensions.RuntimeValidation
                runtimeValidation;
        private Iterator<ResourceLocation> extensionProviders;
        private Phase phase = Phase.BLOCKS;
        private int index, bindingIndex, skipped, stageIndex, nativeIndex;
        private long progress;
        private boolean doneClosed, internal, nextPlacement;
        private List<Plan> plans = new ArrayList<>(), nextRootPlans = new ArrayList<>(), stagePlans;
        private Map<BlockPos, Integer> schemas = new HashMap<>();
        private Map<PatchIdentity, Plan> bound = new HashMap<>();
        private Set<UUID> ids = new HashSet<>(), nextRoots = new HashSet<>();
        private Set<ResourceLocation> active = new HashSet<>(), nextProviders = new TreeSet<>();
        private Set<BlockPos> nextFootprint = new HashSet<>();
        private Map<UUID, ResourceLocation> nextRootTypes = new HashMap<>();
        private Map<UUID, PatchTarget> nextTargets = new HashMap<>();
        private Map<UUID, net.minecraft.nbt.CompoundTag> nativeRoots;
        private Map<PatchStage, List<Plan>> nextOrders = new EnumMap<>(PatchStage.class);
        private IdentityHashMap<JsonObject, Plan> nextTargetPlans = new IdentityHashMap<>();
        private List<Authored> nextPreparation = new ArrayList<>();
        private Iterator<PatchTypes.Provider> providers;
        private PatchTypes.Provider provider;
        private PatchAdapter adapter;
        private List<PatchBinding> bindings;
        private Iterator<PatchBinding> bindingSource;
        private PatchBinding binding;
        private Set<PatchStage> stages;
        private Set<Class<?>> capabilities;
        private Map<UUID, PatchType.EntityMode> roots;
        private List<PatchPreparedInput.Claim> claims;
        private Iterator<BlockPos> positions;
        private Iterator<Class<?>> capabilitySource;
        private Iterator<Map.Entry<UUID, PatchType.EntityMode>> rootSource;
        private Iterator<PatchPreparedInput.Claim> claimSource;
        private Iterator<Map.Entry<UUID, PatchTarget>> targets;
        private Map<PatchIdentity, Set<BlockPos>> required;
        private Iterator<Map.Entry<PatchIdentity, Set<BlockPos>>> receipts;
        private Plan current;
        private PreparedPatchData preparedOwner;
        private PreparedPatchData.Declaration declaration;
        private PatchOrderCursor ordering;
        private List<PatchIdentity> selected, ordered;
        private Map<UUID, Plan> byId;

        Planning(
                SchematicDocument doc,
                List<JsonObject> blocks,
                List<JsonObject> entities,
                PatchPolicy policy,
                PatchExecutionContext discovery,
                ListTag palette,
                Map<UUID, PatchTarget> entityTargets,
                net.minecraft.nbt.CompoundTag nativeSource,
                java.util.function.LongConsumer reserve,
                java.util.function.LongConsumer release) {
            this.doc = Objects.requireNonNull(doc);
            this.blocks = Objects.requireNonNull(blocks);
            this.entityPatches = Objects.requireNonNull(entities);
            this.policy = Objects.requireNonNull(policy);
            this.discovery = discovery;
            this.sourcePalette = palette;
            this.sourceTargets = entityTargets;
            this.nativeSource = nativeSource;
            this.reserve = Objects.requireNonNull(reserve);
            this.release = Objects.requireNonNull(release);
        }

        boolean step(java.util.function.BooleanSupplier canContinue) {
            if (doneClosed) throw new IllegalStateException("Patch planning is closed");
            enter();
            try {
                while (phase != Phase.DONE) {
                    if (phase == Phase.READ_EXTENSIONS || phase == Phase.VALIDATE_EXTENSIONS) {
                        long before = runtimeValidation.progress();
                        boolean done;
                        try {
                            done =
                                    phase == Phase.READ_EXTENSIONS
                                            ? runtimeValidation.stepRead(canContinue)
                                            : runtimeValidation.step(canContinue);
                        } finally {
                            progress += runtimeValidation.progress() - before;
                        }
                        if (!done) return false;
                        if (phase == Phase.READ_EXTENSIONS) {
                            required = runtimeValidation.requiredPositions();
                            index = 0;
                            phase = Phase.INDEX;
                        } else {
                            preparedAuthorData = runtimeValidation.preparedData();
                            runtimeValidation.close();
                            runtimeValidation = null;
                            index = 0;
                            positions = null;
                            phase = Phase.FOOTPRINT;
                        }
                        progress++;
                        continue;
                    }
                    if (phase == Phase.DECLARE && declaration != null) {
                        long before = declaration.progress();
                        boolean done;
                        try {
                            done = declaration.step(canContinue);
                        } finally {
                            progress += declaration.progress() - before;
                        }
                        if (!done) return false;
                        declaration.close();
                        declaration = null;
                        index++;
                        phase = Phase.DECLARATIONS;
                        progress++;
                        continue;
                    }
                    if (phase == Phase.SEAL && preparedOwner != null) {
                        long before = preparedOwner.sealProgress();
                        boolean done;
                        try {
                            done = preparedOwner.sealDeclarationsStep(canContinue);
                        } finally {
                            progress += preparedOwner.sealProgress() - before;
                        }
                        if (!done) return false;
                        phase = Phase.ORDER_START;
                        progress++;
                        continue;
                    }
                    if (phase == Phase.ORDER) {
                        long before = ordering.progress();
                        boolean done;
                        try {
                            done = ordering.step(canContinue);
                        } finally {
                            progress += ordering.progress() - before;
                        }
                        if (!done) return false;
                        ordered = ordering.result();
                        index = 0;
                        stagePlans = new ArrayList<>();
                        phase = Phase.ORDER_OUTPUT;
                        progress++;
                        continue;
                    }
                    if (!canContinue.getAsBoolean()) return false;
                    requireOpen();
                    switch (phase) {
                        case BLOCKS -> {
                            if (index == blocks.size()) {
                                index = 0;
                                phase = Phase.ENTITIES;
                                break;
                            }
                            var patch = blocks.get(index++);
                            if (ExternalPatches.external(patch)) {
                                var pos = SchematicPatches.position(patch.getAsJsonArray("pos"));
                                int schema =
                                        schemas.computeIfAbsent(
                                                pos, p -> SchematicPatches.schema(doc, p));
                                reserve.accept(64);
                                plans.add(
                                        target(
                                                patch,
                                                false,
                                                PatchInstances.identity(patch, false, schema)));
                            }
                        }
                        case ENTITIES -> {
                            if (index < entityPatches.size()) {
                                var patch = entityPatches.get(index++);
                                reserve.accept(64);
                                plans.add(
                                        target(
                                                patch,
                                                true,
                                                PatchInstances.identity(
                                                        patch, true, EntityPatches.schema(doc))));
                            } else {
                                index = 0;
                                providers = PatchTypes.providers().values().iterator();
                                phase = Phase.DISCOVER;
                            }
                        }
                        case DISCOVER -> {
                            if (discovery == null || !providers.hasNext()) {
                                phase = Phase.RECEIPTS;
                                break;
                            }
                            provider = providers.next();
                            adapter = PatchTypes.adapter(provider.id());
                            if (adapter == null) break;
                            internal = provider.id().getNamespace().equals("npships");
                            var window = new Window();
                            try {
                                var context =
                                        scoped(
                                                discovery.context(),
                                                type -> discovery.findCapability(type).orElse(null),
                                                window,
                                                internal);
                                // bind promises stable detached records; retain no live
                                // callback-scoped capability.
                                bindingSource =
                                        Objects.requireNonNull(adapter.bind(context)).iterator();
                                requireOpen();
                            } finally {
                                window.close();
                            }
                            bindings = new ArrayList<>();
                            phase = Phase.BINDING_COPY;
                        }
                        case BINDING_COPY -> {
                            if (bindingSource.hasNext()) {
                                reserve.accept(8);
                                bindings.add(Objects.requireNonNull(bindingSource.next()));
                            } else {
                                bindingSource = null;
                                if (!internal
                                        && bindings.size() > 1
                                        && provider.registration().multiplicity()
                                                != PatchRegistration.Multiplicity.MULTIPLE)
                                    throw new IllegalArgumentException(
                                            "Document adapter multiplicity requires MULTIPLE: "
                                                    + provider.id());
                                bindingIndex = 0;
                                phase = Phase.BINDING_NEXT;
                            }
                        }
                        case BINDING_NEXT -> {
                            if (bindingIndex == bindings.size()) {
                                bindings = null;
                                phase = Phase.DISCOVER;
                                break;
                            }
                            binding = bindings.get(bindingIndex++);
                            positions = binding.localPositions().iterator();
                            phase = internal ? Phase.BINDING_VALIDATE : Phase.BINDING_POSITIONS;
                        }
                        case BINDING_POSITIONS -> {
                            if (positions.hasNext()) {
                                var local = positions.next();
                                if (!doc.contains(local))
                                    throw new IllegalArgumentException(
                                            "Document mapping footprint is outside source dimensions: "
                                                    + provider.id()
                                                    + " at "
                                                    + local);
                            } else phase = Phase.BINDING_VALIDATE;
                        }
                        case BINDING_VALIDATE -> {
                            if (!provider.id().equals(binding.identity().type()))
                                throw new IllegalArgumentException(
                                        "Adapter bound another provider's identity: "
                                                + provider.id());
                            adapter.validate(binding);
                            requireOpen();
                            stages = Set.copyOf(adapter.stages());
                            capabilities = new HashSet<>();
                            roots = new HashMap<>();
                            claims = new ArrayList<>();
                            capabilitySource =
                                    policy.enabled(provider.id())
                                            ? Objects.requireNonNull(
                                                            adapter.requestedCapabilities(binding))
                                                    .iterator()
                                            : Collections.emptyIterator();
                            phase = Phase.CAPTURE_CAPABILITIES;
                        }
                        case CAPTURE_CAPABILITIES -> {
                            if (capabilitySource.hasNext()) {
                                reserve.accept(32);
                                capabilities.add(Objects.requireNonNull(capabilitySource.next()));
                            } else {
                                rootSource =
                                        policy.enabled(provider.id())
                                                ? Objects.requireNonNull(
                                                                adapter.entityRoots(binding))
                                                        .entrySet()
                                                        .iterator()
                                                : Collections.emptyIterator();
                                phase = Phase.CAPTURE_ROOTS;
                            }
                        }
                        case CAPTURE_ROOTS -> {
                            if (rootSource.hasNext()) {
                                var entry = rootSource.next();
                                reserve.accept(48);
                                roots.put(
                                        Objects.requireNonNull(entry.getKey()),
                                        Objects.requireNonNull(entry.getValue()));
                            } else {
                                claimSource =
                                        policy.enabled(provider.id())
                                                ? Objects.requireNonNull(
                                                                adapter.preparedClaims(binding))
                                                        .iterator()
                                                : Collections.emptyIterator();
                                phase = Phase.CAPTURE_CLAIMS;
                            }
                        }
                        case CAPTURE_CLAIMS -> {
                            if (claimSource.hasNext()) {
                                reserve.accept(8);
                                claims.add(Objects.requireNonNull(claimSource.next()));
                            } else phase = Phase.BINDING_COMMIT;
                        }
                        case BINDING_COMMIT -> {
                            reserve.accept(64);
                            plans.add(
                                    new Plan(
                                            null,
                                            false,
                                            binding.identity(),
                                            adapter,
                                            binding,
                                            stages,
                                            Collections.unmodifiableSet(capabilities),
                                            Collections.unmodifiableMap(roots),
                                            Collections.unmodifiableList(claims)));
                            capabilities = null;
                            roots = null;
                            claims = null;
                            phase = Phase.BINDING_NEXT;
                        }
                        case RECEIPTS -> {
                            if (discovery == null) {
                                required = Map.of();
                                index = 0;
                                phase = Phase.INDEX;
                            } else {
                                runtimeValidation =
                                        com.eynny.npships.authoring.document.SchematicExtensions
                                                .beginRuntimeValidation(doc, policy, reserve);
                                phase = Phase.READ_EXTENSIONS;
                            }
                        }
                        case INDEX -> {
                            if (index < plans.size()) {
                                var plan = plans.get(index++);
                                reserve.accept(96);
                                if (!ids.add(plan.identity.instanceId()))
                                    throw new IllegalArgumentException(
                                            "Duplicate patch instance "
                                                    + plan.identity.instanceId());
                                active.add(plan.identity.type());
                                if (!required.isEmpty()
                                        && plan.adapter != null
                                        && plan.binding != null
                                        && bound.putIfAbsent(plan.identity, plan) != null)
                                    throw new IllegalArgumentException(
                                            "Duplicate delegated runtime identity: "
                                                    + plan.identity);
                            } else {
                                receipts = required.entrySet().iterator();
                                phase = Phase.CHECK_RECEIPTS;
                            }
                        }
                        case CHECK_RECEIPTS -> {
                            if (!receipts.hasNext()) {
                                phase = Phase.POLICY;
                                break;
                            }
                            var ownership = receipts.next();
                            current = bound.get(ownership.getKey());
                            if (current == null)
                                throw new IllegalArgumentException(
                                        "Required capture owner has no complete runtime binding: "
                                                + ownership.getKey());
                            positions = ownership.getValue().iterator();
                            phase = Phase.RECEIPT_POSITIONS;
                        }
                        case RECEIPT_POSITIONS -> {
                            if (positions.hasNext()) {
                                if (!current.binding.localPositions().contains(positions.next()))
                                    throw new IllegalArgumentException(
                                            "Required capture owner has no complete runtime binding: "
                                                    + current.identity);
                            } else phase = Phase.CHECK_RECEIPTS;
                        }
                        case POLICY -> {
                            if (discovery == null) {
                                index = 0;
                                positions = null;
                                phase = Phase.FOOTPRINT;
                            } else {
                                extensionProviders = runtimeValidation.providerIds().iterator();
                                phase = Phase.POLICY_PROVIDERS;
                            }
                        }
                        case POLICY_PROVIDERS -> {
                            if (extensionProviders.hasNext()) {
                                reserve.accept(32);
                                active.add(extensionProviders.next());
                            } else {
                                // Registry-sized host dependency validation precedes any author
                                // callback.
                                PatchRuntimePolicy.validate(policy, active);
                                extensionProviders = null;
                                phase = Phase.VALIDATE_EXTENSIONS;
                            }
                        }
                        case FOOTPRINT -> {
                            if (positions != null && positions.hasNext()) {
                                reserve.accept(32);
                                nextFootprint.add(positions.next());
                                break;
                            }
                            positions = null;
                            if (index == plans.size()) {
                                targets = sourceTargets.entrySet().iterator();
                                phase = Phase.TARGETS;
                                break;
                            }
                            var plan = plans.get(index++);
                            if (!policy.enabled(plan.identity.type())) {
                                skipped++;
                                break;
                            }
                            if (plan.binding != null)
                                positions = plan.binding.localPositions().iterator();
                        }
                        case TARGETS -> {
                            if (targets.hasNext()) {
                                var entry = targets.next();
                                reserve.accept(64);
                                nextTargets.put(
                                        Objects.requireNonNull(entry.getKey()),
                                        Objects.requireNonNull(entry.getValue()));
                                nextRoots.add(entry.getKey());
                            } else {
                                index = 0;
                                phase = Phase.DECLARATIONS;
                            }
                        }
                        case DECLARATIONS -> {
                            if (index == plans.size()) {
                                if (nextRoots.size() > 1024)
                                    throw new IllegalArgumentException(
                                            "Maximum 1024 required authored roots");
                                phase = Phase.SEAL;
                                break;
                            }
                            current = plans.get(index);
                            if (!policy.enabled(current.identity.type())) {
                                index++;
                                break;
                            }
                            capabilitySource = current.capabilities.iterator();
                            phase = Phase.CHECK_CAPABILITIES;
                        }
                        case CHECK_CAPABILITIES -> {
                            if (capabilitySource.hasNext()) {
                                var capability = capabilitySource.next();
                                if (capability != PatchPreparedInput.class
                                        && capability != PatchPlacement.class)
                                    throw new IllegalArgumentException(
                                            "Unsupported required host capability: "
                                                    + capability.getName());
                                break;
                            }
                            if (!current.claims.isEmpty()
                                    && !current.capabilities.contains(PatchPreparedInput.class))
                                throw new IllegalArgumentException(
                                        "Prepared claims require PatchPreparedInput capability");
                            if (!current.roots.isEmpty()
                                    && !current.capabilities.contains(PatchPlacement.class))
                                throw new IllegalArgumentException(
                                        "Entity root declarations require PatchPlacement capability");
                            nextPlacement |= current.capabilities.contains(PatchPlacement.class);
                            rootSource = current.roots.entrySet().iterator();
                            if (!current.roots.isEmpty() && nativeRoots == null) {
                                if (nativeSource == null)
                                    throw new IllegalArgumentException(
                                            "Native authored root source unavailable");
                                nativeRoots = new HashMap<>();
                                nativeIndex = 0;
                                phase = Phase.NATIVE_ROOTS;
                            } else phase = Phase.CHECK_ROOTS;
                        }
                        case NATIVE_ROOTS -> {
                            var list =
                                    nativeSource.getList(
                                            "entities", net.minecraft.nbt.Tag.TAG_COMPOUND);
                            if (nativeIndex == list.size()) {
                                phase = Phase.CHECK_ROOTS;
                                break;
                            }
                            var data = list.getCompound(nativeIndex++).getCompound("nbt");
                            var token = EntityIdentity.read(data);
                            if (token != null) {
                                reserve.accept(48);
                                if (nativeRoots.putIfAbsent(token, data) != null)
                                    throw new IllegalArgumentException(
                                            "Duplicate authored root identity " + token);
                            }
                        }
                        case CHECK_ROOTS -> {
                            if (rootSource.hasNext()) {
                                var declared = rootSource.next();
                                var token = declared.getKey();
                                var data = nativeRoots.get(token);
                                if (data == null)
                                    throw new IllegalArgumentException(
                                            "Missing declared authored root " + token);
                                if (!data.getList("Passengers", net.minecraft.nbt.Tag.TAG_COMPOUND)
                                        .isEmpty())
                                    throw new IllegalArgumentException(
                                            "Declared authored roots with passengers are unsupported");
                                var type = ResourceLocation.tryParse(data.getString("id"));
                                if (type == null
                                        || !net.minecraft.core.registries.BuiltInRegistries
                                                .ENTITY_TYPE
                                                .containsKey(type))
                                    throw new IllegalArgumentException(
                                            "Unavailable declared native root type "
                                                    + data.getString("id"));
                                if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                        .get(type)
                                        .canSerialize())
                                    throw new IllegalArgumentException(
                                            "Unserializable declared native root type " + type);
                                if (declared.getValue() == PatchType.EntityMode.STATIC_ONLY
                                        && EntityPatches.authoredPhysical(doc))
                                    throw new IllegalArgumentException(
                                            "STATIC_ONLY root cannot target an authored physical assembly");
                                reserve.accept(64);
                                nextRootTypes.put(token, type);
                                nextRoots.add(token);
                                break;
                            }
                            if (!current.roots.isEmpty()) {
                                reserve.accept(8);
                                nextRootPlans.add(current);
                            }
                            if (current.capabilities.contains(PatchPreparedInput.class)) {
                                if (preparedOwner == null)
                                    preparedOwner =
                                            (PreparedPatchData)
                                                    discovery
                                                            .findCapability(PreparedPatchData.class)
                                                            .orElseThrow();
                                declaration =
                                        preparedOwner.beginDeclaration(
                                                current.identity,
                                                current.binding.localPositions(),
                                                current.roots.keySet(),
                                                current.claims);
                                phase = Phase.DECLARE;
                            } else {
                                index++;
                                phase = Phase.DECLARATIONS;
                            }
                        }
                        case SEAL -> phase = Phase.ORDER_START;
                        case ORDER_START -> {
                            if (stageIndex == PatchStage.values().length) {
                                index = 0;
                                phase = Phase.RETAIN;
                                break;
                            }
                            selected = new ArrayList<>();
                            byId = new HashMap<>();
                            index = 0;
                            phase = Phase.ORDER_INDEX;
                        }
                        case ORDER_INDEX -> {
                            var stage = PatchStage.values()[stageIndex];
                            if (index < plans.size()) {
                                var plan = plans.get(index++);
                                if (plan.stages.contains(stage)) {
                                    reserve.accept(48);
                                    selected.add(plan.identity);
                                    byId.put(plan.identity.instanceId(), plan);
                                }
                            } else {
                                ordering =
                                        new PatchOrderCursor(
                                                stage,
                                                selected,
                                                policy,
                                                discovery == null
                                                        ? List.of()
                                                        : BuiltInPatchAdapters.defaultOrder(stage),
                                                reserve,
                                                release);
                                phase = Phase.ORDER;
                            }
                        }
                        case ORDER_OUTPUT -> {
                            if (index < ordered.size()) {
                                reserve.accept(8);
                                stagePlans.add(byId.get(ordered.get(index++).instanceId()));
                            } else {
                                nextOrders.put(
                                        PatchStage.values()[stageIndex++],
                                        Collections.unmodifiableList(stagePlans));
                                selected = ordered = null;
                                byId = null;
                                // All result identities have moved to separately charged retained
                                // stagePlans.
                                ordering.close();
                                ordering = null;
                                phase = Phase.ORDER_START;
                            }
                        }
                        case RETAIN -> {
                            if (index < plans.size()) {
                                var plan = plans.get(index++);
                                reserve.accept(64);
                                nextProviders.add(plan.identity.type());
                                if (plan.patch != null) nextTargetPlans.put(plan.patch, plan);
                            } else {
                                index = 0;
                                phase = Phase.PREPARATION;
                            }
                        }
                        case PREPARATION -> {
                            var sequence = nextOrders.getOrDefault(PatchStage.PREPARE, List.of());
                            if (index < sequence.size()) {
                                var plan = sequence.get(index++);
                                if (plan.patch != null) {
                                    reserve.accept(32);
                                    nextPreparation.add(
                                            new Authored(plan.patch, plan.entity, plan.identity));
                                }
                            } else phase = Phase.COMMIT;
                        }
                        case COMMIT -> {
                            requireOpen();
                            document = doc;
                            palette = sourcePalette;
                            entityTargets = Collections.unmodifiableMap(nextTargets);
                            requiredRoots = Collections.unmodifiableSet(nextRoots);
                            rootTypes = Collections.unmodifiableMap(nextRootTypes);
                            rootPlans = Collections.unmodifiableList(nextRootPlans);
                            placementRequested = nextPlacement;
                            orders.putAll(nextOrders);
                            capturedPolicy = policy;
                            plannedProviders = nextProviders;
                            footprint = Collections.unmodifiableSet(nextFootprint);
                            targetPlans = nextTargetPlans;
                            preparation = Collections.unmodifiableList(nextPreparation);
                            if (skipped > 0)
                                com.mojang.logging.LogUtils.getLogger()
                                        .warn(
                                                "Skipped {} explicitly nonessential disabled patch instances; authored payload is retained",
                                                skipped);
                            phase = Phase.DONE;
                        }
                        default ->
                                throw new IllegalStateException(
                                        "Invalid patch planning phase: " + phase);
                    }
                    progress++;
                }
                return true;
            } finally {
                leave();
            }
        }

        long progress() {
            return progress;
        }

        @Override
        public void close() {
            if (doneClosed) return;
            doneClosed = true;
            if (declaration != null) declaration.close();
            if (ordering != null) ordering.close();
            declaration = null;
            ordering = null;
            if (runtimeValidation != null) runtimeValidation.close();
            runtimeValidation = null;
            extensionProviders = null;
            plans = nextRootPlans = stagePlans = null;
            schemas = null;
            bound = null;
            ids = nextRoots = null;
            active = nextProviders = null;
            nextFootprint = null;
            nextRootTypes = null;
            nextTargets = null;
            nativeRoots = null;
            nextOrders = null;
            nextTargetPlans = null;
            nextPreparation = null;
            bindings = null;
            bindingSource = null;
            positions = null;
            capabilitySource = null;
            rootSource = null;
            claimSource = null;
            targets = null;
            required = null;
            receipts = null;
            current = null;
            selected = ordered = null;
            byId = null;
            if (pendingPlanning == this) pendingPlanning = null;
        }
    }

    List<Authored> preparation() {
        return preparation;
    }

    void nativePrepared(
            BuiltinPatchState state,
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.nbt.CompoundTag block,
            net.minecraft.nbt.CompoundTag data)
            throws Exception {
        nativePrepared(state, entity, block, data, false);
    }

    private void loadNativePreparation() {
        if (nativePreparation == null) {
            nativePreparation = PatchTypes.capabilities(PatchNativePreparation.class);
            nativePreparationEntries = List.copyOf(nativePreparation.entrySet());
        }
    }

    boolean inspectBareNative() {
        requireOpen();
        loadNativePreparation();
        if (inspectBareNative == null)
            inspectBareNative =
                    nativePreparation.values().stream()
                            .anyMatch(code -> code.inspectBare(capturedPolicy, plannedProviders));
        return inspectBareNative;
    }

    void nativePrepared(
            BuiltinPatchState state,
            net.minecraft.world.level.block.entity.BlockEntity entity,
            net.minecraft.nbt.CompoundTag block,
            net.minecraft.nbt.CompoundTag data,
            boolean bareInspection)
            throws Exception {
        enter();
        try {
            loadNativePreparation();
            TreeSet<ResourceLocation> matched = null;
            for (int i = 0; i < nativePreparationEntries.size(); i++) {
                var entry = nativePreparationEntries.get(i);
                if (bareInspection
                        && !entry.getValue().inspectBare(capturedPolicy, plannedProviders))
                    continue;
                if (!entry.getValue().applies(entity, block)) continue;
                if (matched == null) matched = new TreeSet<>();
                matched.add(entry.getKey());
            }
            if (matched == null) return;
            if (!nativeProviders.containsAll(matched)) {
                var active = new TreeSet<>(nativeProviders);
                active.addAll(matched);
                active.addAll(plannedProviders);
                PatchRuntimePolicy.validate(capturedPolicy, active);
            }
            nativeProviders.addAll(matched);
            var ordered =
                    matched.size() == 1
                            ? singleNativeOrders.computeIfAbsent(
                                    matched.first(),
                                    id ->
                                            PatchOrdering.discoveryOrder(
                                                    PatchStage.PREPARE_NATIVE_DATA,
                                                    Set.of(id),
                                                    capturedPolicy))
                            : PatchOrdering.discoveryOrder(
                                    PatchStage.PREPARE_NATIVE_DATA, matched, capturedPolicy);
            for (var id : ordered) {
                requireOpen();
                nativePreparation.get(id).prepare(state, entity, block, data);
                requireOpen();
            }
        } finally {
            leave();
        }
    }

    private List<ResourceLocation> initialNativeOrder() {
        loadNativePreparation();
        if (initialNativeOrder == null) {
            var enabled = new TreeSet<ResourceLocation>();
            for (var id : nativePreparation.keySet())
                if (capturedPolicy.enabled(id)) enabled.add(id);
            initialNativeOrder =
                    PatchOrdering.discoveryOrder(
                            PatchStage.PREPARE_NATIVE_DATA, enabled, capturedPolicy);
        }
        return initialNativeOrder;
    }

    void nativeInitialData(
            BuiltinPatchState state, BlockPos local, net.minecraft.nbt.CompoundTag data)
            throws Exception {
        enter();
        try {
            for (var id : initialNativeOrder()) {
                requireOpen();
                nativePreparation.get(id).beforeInitialBlock(state, local, data);
                requireOpen();
            }
        } finally {
            leave();
        }
    }

    void nativeInitialPlaced(
            BuiltinPatchState state,
            BlockPos local,
            net.minecraft.world.level.block.entity.BlockEntity entity)
            throws Exception {
        if (entity == null) return;
        enter();
        try {
            for (var id : initialNativeOrder()) {
                requireOpen();
                nativePreparation.get(id).afterInitialBlock(state, local, entity);
                requireOpen();
            }
        } finally {
            leave();
        }
    }

    void nativeBodies(BuiltinPatchState state) throws Exception {
        enter();
        try {
            if (nativePreparation == null) return;
            for (var id :
                    PatchOrdering.discoveryOrder(
                            PatchStage.RESTORE_NATIVE_CONTEXT, nativeProviders, capturedPolicy)) {
                requireOpen();
                nativePreparation.get(id).afterBodies(state);
                requireOpen();
            }
        } finally {
            leave();
        }
    }

    List<
                    Map.Entry<
                            ResourceLocation,
                            java.util.function.Consumer<
                                    dev.ryanhcode.sable.sublevel.ServerSubLevel>>>
            delayedRepairs() {
        requireOpen();
        if (repairs != null) return repairs;
        loadNativePreparation();
        var callbacks =
                new TreeMap<
                        ResourceLocation,
                        java.util.function.Consumer<dev.ryanhcode.sable.sublevel.ServerSubLevel>>();
        for (var entry : nativePreparation.entrySet()) {
            if (!capturedPolicy.enabled(entry.getKey())) continue;
            var callback = entry.getValue().delayedRepair();
            if (callback != null) callbacks.put(entry.getKey(), callback);
        }
        repairs =
                PatchOrdering.discoveryOrder(
                                PatchStage.POST_PLACEMENT_MAINTENANCE,
                                callbacks.keySet(),
                                capturedPolicy)
                        .stream()
                        .map(id -> Map.entry(id, callbacks.get(id)))
                        .toList();
        return repairs;
    }

    boolean needsSourcePass(
            PatchStage stage, PatchContext base, Function<Class<?>, ?> capabilities) {
        enter();
        try {
            var code = PatchTypes.capabilities(PatchSourcePass.class);
            var visited = new HashSet<ResourceLocation>();
            for (var plan : orders.getOrDefault(stage, List.of())) {
                if (!visited.add(plan.identity.type())) continue;
                var demand = code.get(plan.identity.type());
                if (demand == null) return true;
                var window = new Window();
                try {
                    var context = targetContext(plan, base, null, stage, window);
                    var execution =
                            scoped(
                                    context,
                                    capabilities,
                                    window,
                                    plan.identity.type().getNamespace().equals("npships"),
                                    plan,
                                    stage,
                                    null);
                    boolean needed = demand.needed(stage, execution);
                    requireOpen();
                    if (needed) return true;
                } finally {
                    window.close();
                }
            }
            return false;
        } finally {
            leave();
        }
    }

    boolean has(PatchStage stage) {
        var sequence = orders.get(stage);
        return sequence != null && !sequence.isEmpty();
    }

    Set<BlockPos> footprint() {
        return Collections.unmodifiableSet(footprint);
    }

    private static Plan target(JsonObject patch, boolean entity, PatchIdentity identity) {
        var stages =
                EnumSet.of(
                        PatchStage.PREPARE, PatchStage.BEFORE_ASSEMBLY, PatchStage.AFTER_PLACEMENT);
        stages.addAll(PatchTypes.provider(identity.type()).registration().ordering().keySet());
        stages.remove(PatchStage.VALIDATION);
        stages.remove(PatchStage.AUTHOR_PREPARATION);
        // These fixed boundaries require their actual scoped capability. Ordering metadata alone
        // cannot fabricate a raw native-data/capture/queue callback on a target handler.
        stages.remove(PatchStage.CAPTURE_BLOCK);
        stages.remove(PatchStage.PREPARE_NATIVE_DATA);
        stages.remove(PatchStage.RESTORE_NATIVE_CONTEXT);
        stages.remove(PatchStage.POST_PLACEMENT_MAINTENANCE);
        return new Plan(
                patch,
                entity,
                identity,
                null,
                null,
                Set.copyOf(stages),
                Set.of(),
                Map.of(),
                List.of());
    }

    /** Existing preparation owner supplies the budget; only its cooperative phases may yield. */
    boolean step(
            PatchStage stage,
            PatchContext base,
            Function<Class<?>, ?> capabilities,
            long budgetNanos,
            EntityPlacementScope mapping,
            BlockPos unit) {
        enter();
        boolean cooperative = stage == PatchStage.INPUT_VALIDATION || stage == PatchStage.PREPARE;
        long start = cooperative ? System.nanoTime() : 0;
        var sequence = orders.getOrDefault(stage, List.of());
        int index = cooperative ? cursors.getOrDefault(stage, 0) : 0;
        try {
            while (index < sequence.size()) {
                var plan = sequence.get(index);
                if (unit != null
                        && plan.patch != null
                        && (plan.entity
                                || !unit.equals(
                                        SchematicPatches.position(
                                                plan.patch.getAsJsonArray("pos"))))) {
                    index++;
                    continue;
                }
                if (unit != null
                        && plan.binding != null
                        && !plan.binding.localPositions().isEmpty()
                        && !plan.binding.localPositions().contains(unit)) {
                    index++;
                    continue;
                }
                boolean done = invoke(plan, stage, base, capabilities, mapping, null, null);
                if (done) index++;
                if (cooperative
                        && index < sequence.size()
                        && System.nanoTime() - start >= Math.max(1, budgetNanos)) {
                    cursors.put(stage, index);
                    return false;
                }
            }
            if (cooperative) cursors.put(stage, index);
            return true;
        } finally {
            leave();
        }
    }

    void prepareOne(
            SchematicDocument doc, JsonObject patch, PatchContext context, ListTag palette) {
        prepareTarget(patch, context, palette, null);
    }

    void prepareEntityOne(
            SchematicDocument doc,
            JsonObject patch,
            PatchContext context,
            ListTag palette,
            PatchTarget target) {
        prepareTarget(patch, context, palette, target);
    }

    private void prepareTarget(
            JsonObject patch, PatchContext context, ListTag palette, PatchTarget target) {
        if (!ExternalPatches.external(patch)) return;
        enter();
        try {
            var plan = Objects.requireNonNull(targetPlans.get(patch), "Patch was not preflighted");
            if (!invoke(plan, PatchStage.PREPARE, context, type -> null, null, palette, target))
                throw new IllegalStateException("B1 preparation bridge cannot yield");
        } finally {
            leave();
        }
    }

    void beforeAssembly(PatchContext context) {
        step(PatchStage.BEFORE_ASSEMBLY, context, type -> null, Long.MAX_VALUE, null, null);
    }

    void afterPlacement(PatchContext context, EntityPlacementScope mapping) {
        step(PatchStage.AFTER_PLACEMENT, context, type -> null, Long.MAX_VALUE, mapping, null);
    }

    private boolean invoke(
            Plan plan,
            PatchStage stage,
            PatchContext base,
            Function<Class<?>, ?> capabilities,
            EntityPlacementScope mapping,
            ListTag preparationPalette,
            PatchTarget preparationTarget) {
        var existing = acquired.get(plan.identity.instanceId());
        if (existing != null && existing.complete(stage)) return true;
        var window = new Window();
        var placement = (PatchPlacementState) capabilities.apply(PatchPlacementState.class);
        try (var reads = placement == null ? null : placement.nativeReads()) {
            var context = targetContext(plan, base, mapping, stage, window);
            var execution =
                    scoped(
                            context,
                            capabilities,
                            window,
                            plan.identity.type().getNamespace().equals("npships"),
                            plan,
                            stage,
                            mapping);
            var entry =
                    existing == null
                            ? acquire(plan, execution, preparationPalette, preparationTarget)
                            : existing;
            boolean done = entry.handler.step(stage, execution);
            requireOpen();
            boolean cooperative =
                    stage == PatchStage.INPUT_VALIDATION || stage == PatchStage.PREPARE;
            if (!done && !cooperative)
                throw new IllegalStateException(
                        "Patch cannot yield during atomic host stage " + stage);
            if (done) entry.completed(stage);
            if (stage == PatchStage.AFTER_PLACEMENT) {
                if (plan.entity) mapping.require(EntityPatches.token(plan.patch));
                for (var token : plan.roots.keySet()) mapping.require(token);
            }
            return done;
        } catch (Throwable error) {
            fatal(error);
            var reason = reason(stage);
            if (plan.patch != null)
                throw plan.entity
                        ? EntityPatches.failure(plan.patch, reason, error.toString(), error)
                        : ExternalPatches.failure(plan.patch, reason, error.toString(), error);
            throw new PatchException(
                    reason,
                    plan.identity.type().toString(),
                    "document/" + plan.identity.instanceId(),
                    error.toString(),
                    error);
        } finally {
            window.close();
        }
    }

    private static Reason reason(PatchStage stage) {
        if (stage == PatchStage.BEFORE_ASSEMBLY || stage == PatchStage.BEFORE_NATIVE_ASSEMBLY)
            return Reason.BEFORE_ASSEMBLY_FAILURE;
        if (stage == PatchStage.INPUT_VALIDATION
                || stage == PatchStage.PREPARE
                || stage.name().startsWith("PREPARE_")
                || stage == PatchStage.TEMPLATE_LOADED
                || stage == PatchStage.BEFORE_NATIVE_PLACEMENT) return Reason.PREPARATION_FAILURE;
        return Reason.RESTORATION_FAILURE;
    }

    private final class Window implements AutoCloseable {
        boolean open = true;
        PatchPreparedInput prepared;
        PatchPlacement placement;

        public void close() {
            open = false;
            try {
                if (prepared != null) prepared.close();
            } finally {
                prepared = null;
                if (placement != null) placement.close();
                placement = null;
            }
        }
    }

    Set<UUID> requiredRoots() {
        return requiredRoots;
    }

    boolean placementRequested() {
        return placementRequested;
    }

    private PatchExecutionContext scoped(
            PatchContext context,
            Function<Class<?>, ?> capabilities,
            Window window,
            boolean internal) {
        return scoped(context, capabilities, window, internal, null, null, null);
    }

    private PatchExecutionContext scoped(
            PatchContext context,
            Function<Class<?>, ?> capabilities,
            Window window,
            boolean internal,
            Plan plan,
            PatchStage stage,
            EntityPlacementScope mapping) {
        return new PatchExecutionContext(
                context,
                type -> {
                    requireWindow(window, context);
                    if (!internal
                            && (type == BuiltinPatchState.class
                                    || type == PreparedPatchData.class
                                    || type == PatchPlacementState.class)) return null;
                    if (type == PatchPreparedInput.class) {
                        if (plan == null
                                || stage != PatchStage.PREPARE_FINALIZE
                                || !plan.capabilities.contains(type)) return null;
                        if (window.prepared == null)
                            window.prepared =
                                    ((PreparedPatchData)
                                                    capabilities.apply(PreparedPatchData.class))
                                            .capability(
                                                    plan.identity,
                                                    () -> requireWindow(window, context));
                        return window.prepared;
                    }
                    if (type == PatchPlacement.class) {
                        if (plan == null
                                || !plan.capabilities.contains(type)
                                || !(stage == PatchStage.AFTER_BODY_PLACEMENT
                                        || stage == PatchStage.RESTORE_LOCAL
                                        || stage == PatchStage.RESTORE_REFERENCES
                                        || stage == PatchStage.AFTER_PLACEMENT)) return null;
                        if (window.placement == null) {
                            var state =
                                    (PatchPlacementState)
                                            capabilities.apply(PatchPlacementState.class);
                            if (state == null) return null;
                            window.placement =
                                    state.capability(
                                            plan.binding.localPositions(),
                                            plan.roots.keySet(),
                                            () -> requireWindow(window, context),
                                            mapping,
                                            name -> {
                                                if (!name.getNamespace()
                                                        .equals(
                                                                plan.identity
                                                                        .type()
                                                                        .getNamespace()))
                                                    throw new IllegalArgumentException(
                                                            "Ephemeral record belongs to another provider namespace");
                                                var owner =
                                                        (PreparedPatchData)
                                                                capabilities.apply(
                                                                        PreparedPatchData.class);
                                                try {
                                                    var value =
                                                            owner == null
                                                                    ? Optional
                                                                            .<net.minecraft.nbt
                                                                                            .CompoundTag>
                                                                                    empty()
                                                                    : owner.record(
                                                                            plan.identity, name);
                                                    return new PatchPlacement.RecordRead(
                                                            value.isPresent()
                                                                    ? PatchPlacement.RecordStatus
                                                                            .AVAILABLE
                                                                    : PatchPlacement.RecordStatus
                                                                            .ABSENT,
                                                            value,
                                                            "");
                                                } catch (
                                                        com.eynny.npships.resources
                                                                        .ResourceLimitException
                                                                refused) {
                                                    // This optional read follows world commit.
                                                    // Report denied
                                                    // copy admission without throwing into
                                                    // mandatory cleanup.
                                                    return new PatchPlacement.RecordRead(
                                                            PatchPlacement.RecordStatus.REFUSED,
                                                            Optional.empty(),
                                                            refused.getMessage());
                                                }
                                            });
                        }
                        return window.placement;
                    }
                    Object value = capabilities.apply(type);
                    if (value instanceof PatchTemplateInput input)
                        return new PatchTemplateInput(
                                () -> {
                                    requireWindow(window, context);
                                    return input.sourceCopy();
                                },
                                key -> {
                                    requireWindow(window, context);
                                    return input.contains(key);
                                },
                                id -> {
                                    requireWindow(window, context);
                                    return input.authorData(id);
                                });
                    return value;
                });
    }

    private void requireWindow(Window window, PatchContext context) {
        if (!window.open || closed || !context.level().getServer().isSameThread())
            throw new IllegalStateException("Patch execution callback ended");
    }

    private Entry acquire(
            Plan plan,
            PatchExecutionContext execution,
            ListTag preparationPalette,
            PatchTarget preparationTarget)
            throws Exception {
        PatchHandler handler;
        if (plan.adapter != null) handler = plan.adapter.create(plan.binding, execution);
        else {
            var type = ExternalPatches.type(plan.patch);
            var target =
                    plan.entity
                            ? preparationTarget != null
                                    ? preparationTarget
                                    : entityTargets.get(EntityPatches.token(plan.patch))
                            : ExternalPatches.target(
                                    document,
                                    plan.patch,
                                    type,
                                    preparationPalette == null ? palette : preparationPalette);
            handler =
                    type.factory()
                            .create(
                                    target,
                                    ExternalPatches.canonicalPayload(plan.patch, plan.entity));
        }
        if (handler == null || identities.put(handler, true) != null)
            throw new IllegalArgumentException("Factory must return a fresh non-null handler");
        var entry = new Entry(plan, handler);
        if (closed) {
            closeEntry(entry);
            requireOpen();
        }
        entries.add(entry);
        acquired.put(plan.identity.instanceId(), entry);
        return entry;
    }

    void requireEntityPlacement(
            EntityPlacementScope mapping, boolean physical, List<UUID> capturedMobs) {
        requireOpen();
        for (var plan : rootPlans)
            for (var declaration : plan.roots.entrySet()) {
                var entity = mapping.require(declaration.getKey());
                if (!net.minecraft.world.entity.EntityType.getKey(entity.getType())
                        .equals(rootTypes.get(declaration.getKey())))
                    throw new IllegalArgumentException(
                            "Inserted root type changed: " + declaration.getKey());
                if (physical
                        && (declaration.getValue() != PatchType.EntityMode.MOB_ROOT
                                || !(entity instanceof net.minecraft.world.entity.Mob)
                                || !capturedMobs.contains(entity.getUUID())))
                    throw new IllegalArgumentException(
                            "Physical declared root requires captured Mob and MOB_ROOT: "
                                    + declaration.getKey());
            }
        for (var entry : entries)
            if (entry.plan.entity) {
                var patch = entry.plan.patch;
                try {
                    var entity = mapping.require(EntityPatches.token(patch));
                    if (!net.minecraft.world.entity.EntityType.getKey(entity.getType())
                            .equals(ResourceLocation.parse(patch.get("entity_type").getAsString())))
                        throw new IllegalArgumentException(
                                "Native entity type differs from required entity_type");
                    if (physical
                            && (EntityPatches.type(patch).entityMode()
                                            != PatchType.EntityMode.MOB_ROOT
                                    || !(entity instanceof net.minecraft.world.entity.Mob)
                                    || !capturedMobs.contains(entity.getUUID())))
                        throw new IllegalArgumentException(
                                "This entity target supports static placement; physical requires a captured Mob root and MOB_ROOT mode");
                } catch (Exception | LinkageError error) {
                    throw EntityPatches.failure(
                            patch, Reason.RESTORATION_FAILURE, error.toString(), error);
                }
            }
    }

    void requireFinalRoots(EntityPlacementScope mapping) {
        requireOpen();
        for (var token : rootTypes.keySet()) mapping.require(token);
    }

    private PatchContext targetContext(
            Plan plan,
            PatchContext base,
            EntityPlacementScope mapping,
            PatchStage stage,
            Window window) {
        java.util.function.Supplier<net.minecraft.world.entity.Entity> entity =
                () -> {
                    throw new IllegalStateException("No mapped entity in this callback phase");
                };
        if (plan.entity && stage == PatchStage.AFTER_PLACEMENT) {
            var value = mapping.require(EntityPatches.token(plan.patch));
            entity =
                    () -> {
                        requireWindow(window, base);
                        if (value.isRemoved() || base.level().getEntity(value.getUUID()) != value)
                            throw new IllegalStateException(
                                    "Mapped entity removed during callback");
                        return value;
                    };
        }
        return new PatchContext(
                base.level(),
                base.origin(),
                p -> {
                    requireWindow(window, base);
                    if (plan.entity)
                        throw new IllegalArgumentException("Entity patches do not resolve blocks");
                    if (plan.binding != null) {
                        if (!plan.binding.localPositions().contains(p))
                            throw new IllegalArgumentException(
                                    "Adapter resolves only its declared footprint: " + p);
                    } else {
                        var local = SchematicPatches.position(plan.patch.getAsJsonArray("pos"));
                        if (!local.equals(p))
                            throw new IllegalArgumentException(
                                    "This patch context resolves only its declared target: "
                                            + local);
                    }
                    return base.resolve().apply(p);
                },
                entity,
                plan.identity);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Template provider state is closed");
    }

    private void enter() {
        requireOpen();
        callbacks++;
    }

    private void clear() {
        preparedAuthorData = null;
        if (pendingPlanning != null) pendingPlanning.close();
        pendingPlanning = null;
        nativePreparation = null;
        nativePreparationEntries = null;
        initialNativeOrder = null;
        nativeProviders = Set.of();
        plannedProviders = Set.of();
        singleNativeOrders = Map.of();
        inspectBareNative = null;
        repairs = null;
        capturedPolicy = null;
        entries = List.of();
        acquired = Map.of();
        identities = new IdentityHashMap<>();
        targetPlans = new IdentityHashMap<>();
        orders = Map.of();
        cursors = Map.of();
        footprint = Set.of();
        preparation = List.of();
        document = null;
        palette = null;
        entityTargets = Map.of();
        rootPlans = List.of();
        rootTypes = Map.of();
        requiredRoots = Set.of();
        placementRequested = false;
    }

    private void leave() {
        callbacks--;
        // The existing attempt resumes cleanup after the last callback returns; no hidden drain.
    }

    void beginCleanup() {
        closed = true;
    }

    boolean cleanupDeferred() {
        return callbacks != 0 || closingEntry;
    }

    boolean stepCleanup(java.util.function.BooleanSupplier canContinue) {
        beginCleanup();
        if (cleanupDeferred()) return false;
        while (!cleanupComplete && canContinue.getAsBoolean()) {
            if (!entries.isEmpty()) {
                var entry = entries.removeLast();
                closingEntry = true;
                try {
                    closeEntry(entry);
                } finally {
                    closingEntry = false;
                }
            } else {
                clear();
                if (cleanupFailures > 4)
                    com.mojang.logging.LogUtils.getLogger()
                            .error("{} additional provider cleanup failures", cleanupFailures - 4);
                cleanupComplete = true;
            }
        }
        return cleanupComplete;
    }

    private static void fatal(Throwable error) {
        if (error instanceof VirtualMachineError vm) throw vm;
        if (error instanceof ThreadDeath death) throw death;
    }

    private void closeEntry(Entry entry) {
        try {
            entry.handler.close();
        } catch (Throwable error) {
            fatal(error);
            if (cleanupFailures++ < 4) {
                var plan = entry.plan;
                Object target =
                        plan.patch == null
                                ? "document/" + plan.identity.instanceId()
                                : plan.patch.get(plan.entity ? "entity" : "pos");
                com.mojang.logging.LogUtils.getLogger()
                        .error(
                                "Patch {} at {} cleanup failed: {}",
                                plan.identity.type(),
                                target,
                                cleanupDetail(error));
            }
        }
    }

    private static String cleanupDetail(Throwable error) {
        var text = error.toString().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return text.length() <= 384 ? text : text.substring(0, 384) + "...";
    }

    /** Explicit legacy drain outside callbacks; managed ownership uses stepCleanup. */
    @Override
    public void close() {
        beginCleanup();
        if (cleanupDeferred()) return;
        while (!stepCleanup(() -> true)) {
            if (cleanupDeferred()) return;
        }
    }
}
