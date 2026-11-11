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

    private final Map<PatchStage, List<Plan>> orders = new EnumMap<>(PatchStage.class);
    private final Map<PatchStage, Integer> cursors = new EnumMap<>(PatchStage.class);
    private final Map<UUID, Entry> acquired = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final IdentityHashMap<PatchHandler, Boolean> identities = new IdentityHashMap<>();
    private final IdentityHashMap<JsonObject, Plan> targetPlans = new IdentityHashMap<>();
    private final Set<BlockPos> footprint = new HashSet<>();
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
    private PatchPolicy capturedPolicy;
    private Map<ResourceLocation, PatchNativePreparation> nativePreparation;
    private List<Map.Entry<ResourceLocation, PatchNativePreparation>> nativePreparationEntries;
    private final Set<ResourceLocation> nativeProviders = new TreeSet<>();
    private final Set<ResourceLocation> plannedProviders = new TreeSet<>();
    private final Map<ResourceLocation, List<ResourceLocation>> singleNativeOrders =
            new HashMap<>();
    private Boolean inspectBareNative;
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

    private void planInternal(
            SchematicDocument doc,
            List<JsonObject> blocks,
            List<JsonObject> entities,
            PatchPolicy policy,
            PatchExecutionContext discovery,
            ListTag palette,
            Map<UUID, PatchTarget> entityTargets,
            net.minecraft.nbt.CompoundTag nativeSource) {
        enter();
        try {
            if (!orders.isEmpty()) throw new IllegalStateException("Patch plan already owned");
            var plans = new ArrayList<Plan>();
            var schemas = new HashMap<BlockPos, Integer>();
            for (var patch : blocks)
                if (ExternalPatches.external(patch)) {
                    var pos = SchematicPatches.position(patch.getAsJsonArray("pos"));
                    int schema = schemas.computeIfAbsent(pos, p -> SchematicPatches.schema(doc, p));
                    plans.add(target(patch, false, PatchInstances.identity(patch, false, schema)));
                }
            int schema = EntityPatches.schema(doc);
            for (var patch : entities)
                plans.add(target(patch, true, PatchInstances.identity(patch, true, schema)));
            if (discovery != null) {
                for (var provider : PatchTypes.providers().values()) {
                    var adapter = PatchTypes.adapter(provider.id());
                    if (adapter == null) continue;
                    var window = new Window();
                    List<PatchBinding> bindings;
                    try {
                        var context =
                                scoped(
                                        discovery.context(),
                                        type -> discovery.findCapability(type).orElse(null),
                                        window,
                                        provider.id().getNamespace().equals("npships"));
                        bindings = List.copyOf(adapter.bind(context));
                        requireOpen();
                    } finally {
                        window.close();
                    }
                    boolean internal = provider.id().getNamespace().equals("npships");
                    if (!internal
                            && bindings.size() > 1
                            && provider.registration().multiplicity()
                                    != PatchRegistration.Multiplicity.MULTIPLE)
                        throw new IllegalArgumentException(
                                "Document adapter multiplicity requires MULTIPLE: "
                                        + provider.id());
                    for (var binding : bindings) {
                        if (!internal)
                            for (var local : binding.localPositions())
                                if (!doc.contains(local))
                                    throw new IllegalArgumentException(
                                            "Document mapping footprint is outside source dimensions: "
                                                    + provider.id()
                                                    + " at "
                                                    + local);
                        if (!provider.id().equals(binding.identity().type()))
                            throw new IllegalArgumentException(
                                    "Adapter bound another provider's identity: " + provider.id());
                        adapter.validate(binding);
                        requireOpen();
                        plans.add(
                                new Plan(
                                        null,
                                        false,
                                        binding.identity(),
                                        adapter,
                                        binding,
                                        Set.copyOf(adapter.stages()),
                                        policy.enabled(provider.id())
                                                ? Set.copyOf(adapter.requestedCapabilities(binding))
                                                : Set.of(),
                                        policy.enabled(provider.id())
                                                ? Map.copyOf(adapter.entityRoots(binding))
                                                : Map.of(),
                                        policy.enabled(provider.id())
                                                ? List.copyOf(adapter.preparedClaims(binding))
                                                : List.of()));
                    }
                }
            }
            if (discovery != null) {
                var required =
                        com.eynny.npships.authoring.document.SchematicCaptureReceipts
                                .requiredPositions(doc);
                if (!required.isEmpty()) {
                    var bound = new HashMap<PatchIdentity, Plan>();
                    for (var plan : plans)
                        if (plan.adapter != null
                                && plan.binding != null
                                && bound.putIfAbsent(plan.identity, plan) != null)
                            throw new IllegalArgumentException(
                                    "Duplicate delegated runtime identity: " + plan.identity);
                    for (var ownership : required.entrySet()) {
                        var matched = bound.get(ownership.getKey());
                        if (matched == null
                                || !matched.binding
                                        .localPositions()
                                        .containsAll(ownership.getValue()))
                            throw new IllegalArgumentException(
                                    "Required capture owner has no complete runtime binding: "
                                            + ownership.getKey());
                    }
                }
            }
            var ids = new HashSet<UUID>();
            var active = new HashSet<ResourceLocation>();
            for (var plan : plans) {
                if (!ids.add(plan.identity.instanceId()))
                    throw new IllegalArgumentException(
                            "Duplicate patch instance " + plan.identity.instanceId());
                active.add(plan.identity.type());
            }
            if (discovery != null) {
                active.addAll(
                        com.eynny.npships.authoring.document.SchematicExtensions.providerIds(doc));
                PatchRuntimePolicy.validate(policy, active);
                com.eynny.npships.authoring.document.SchematicExtensions.validate(
                        doc, true, PatchAuthoring.Kind.VALIDATE, policy);
            }
            var nextFootprint = new HashSet<BlockPos>();
            int skipped = 0;
            for (var plan : plans) {
                if (!policy.enabled(plan.identity.type())) {
                    skipped++;
                    continue;
                }
                if (plan.binding != null) nextFootprint.addAll(plan.binding.localPositions());
            }
            var nextRoots = new HashSet<UUID>(entityTargets.keySet());
            var nextRootTypes = new HashMap<UUID, ResourceLocation>();
            var nextRootPlans = new ArrayList<Plan>();
            Map<UUID, net.minecraft.nbt.CompoundTag> nativeRoots = null;
            Boolean authoredPhysical = null;
            PreparedPatchData preparedOwner = null;
            boolean nextPlacementRequested = false;
            for (var plan : plans) {
                if (!policy.enabled(plan.identity.type())) continue;
                for (var capability : plan.capabilities)
                    if (capability != PatchPreparedInput.class
                            && capability != PatchPlacement.class)
                        throw new IllegalArgumentException(
                                "Unsupported required host capability: " + capability.getName());
                if (!plan.claims.isEmpty() && !plan.capabilities.contains(PatchPreparedInput.class))
                    throw new IllegalArgumentException(
                            "Prepared claims require PatchPreparedInput capability");
                if (!plan.roots.isEmpty() && !plan.capabilities.contains(PatchPlacement.class))
                    throw new IllegalArgumentException(
                            "Entity root declarations require PatchPlacement capability");
                nextPlacementRequested |= plan.capabilities.contains(PatchPlacement.class);
                if (!plan.roots.isEmpty()) {
                    if (nativeRoots == null) {
                        if (nativeSource == null)
                            throw new IllegalArgumentException(
                                    "Native authored root source unavailable");
                        nativeRoots = new HashMap<>();
                        for (var value :
                                nativeSource.getList(
                                        "entities", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                            var data = ((net.minecraft.nbt.CompoundTag) value).getCompound("nbt");
                            var token = EntityIdentity.read(data);
                            if (token != null && nativeRoots.putIfAbsent(token, data) != null)
                                throw new IllegalArgumentException(
                                        "Duplicate authored root identity " + token);
                        }
                    }
                    for (var token : plan.roots.keySet()) {
                        var data = nativeRoots.get(token);
                        if (data == null)
                            throw new IllegalArgumentException(
                                    "Missing declared authored root " + token);
                        if (!data.getList("Passengers", net.minecraft.nbt.Tag.TAG_COMPOUND)
                                .isEmpty())
                            throw new IllegalArgumentException(
                                    "Declared authored roots with passengers are unsupported");
                        var nativeType = ResourceLocation.tryParse(data.getString("id"));
                        if (nativeType == null
                                || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                        .containsKey(nativeType))
                            throw new IllegalArgumentException(
                                    "Unavailable declared native root type "
                                            + data.getString("id"));
                        if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .get(nativeType)
                                .canSerialize())
                            throw new IllegalArgumentException(
                                    "Unserializable declared native root type " + nativeType);
                        if (plan.roots.get(token) == PatchType.EntityMode.STATIC_ONLY) {
                            if (authoredPhysical == null)
                                authoredPhysical = EntityPatches.authoredPhysical(doc);
                            if (authoredPhysical)
                                throw new IllegalArgumentException(
                                        "STATIC_ONLY root cannot target an authored physical assembly");
                        }
                        nextRootTypes.put(token, nativeType);
                        nextRoots.add(token);
                    }
                    nextRootPlans.add(plan);
                }
                if (plan.capabilities.contains(PatchPreparedInput.class)) {
                    if (preparedOwner == null)
                        preparedOwner =
                                (PreparedPatchData)
                                        discovery
                                                .findCapability(PreparedPatchData.class)
                                                .orElseThrow();
                    preparedOwner.declare(
                            plan.identity,
                            plan.binding.localPositions(),
                            plan.roots.keySet(),
                            plan.claims);
                }
            }
            if (nextRoots.size() > 1024)
                throw new IllegalArgumentException("Maximum 1024 required authored roots");
            if (preparedOwner != null) preparedOwner.sealDeclarations();
            var nextOrders = new EnumMap<PatchStage, List<Plan>>(PatchStage.class);
            for (var stage : PatchStage.values()) {
                var selected = new ArrayList<PatchIdentity>();
                var byId = new HashMap<UUID, Plan>();
                for (var plan : plans)
                    if (plan.stages.contains(stage)) {
                        selected.add(plan.identity);
                        byId.put(plan.identity.instanceId(), plan);
                    }
                var defaults =
                        discovery == null
                                ? List.<ResourceLocation>of()
                                : BuiltInPatchAdapters.defaultOrder(stage);
                nextOrders.put(
                        stage,
                        PatchOrdering.order(stage, selected, policy, defaults).stream()
                                .map(id -> byId.get(id.instanceId()))
                                .toList());
            }
            requireOpen();
            this.document = doc;
            this.palette = palette;
            this.entityTargets = Map.copyOf(entityTargets);
            requiredRoots = Set.copyOf(nextRoots);
            rootTypes = Map.copyOf(nextRootTypes);
            rootPlans = List.copyOf(nextRootPlans);
            placementRequested = nextPlacementRequested;
            orders.putAll(nextOrders);
            capturedPolicy = policy;
            for (var plan : plans) plannedProviders.add(plan.identity.type());
            footprint.addAll(nextFootprint);
            for (var plan : plans) if (plan.patch != null) targetPlans.put(plan.patch, plan);
            preparation =
                    orders.getOrDefault(PatchStage.PREPARE, List.of()).stream()
                            .filter(plan -> plan.patch != null)
                            .map(plan -> new Authored(plan.patch, plan.entity, plan.identity))
                            .toList();
            if (skipped > 0)
                com.mojang.logging.LogUtils.getLogger()
                        .warn(
                                "Skipped {} explicitly nonessential disabled patch instances; authored payload is retained",
                                skipped);
        } finally {
            leave();
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
        return Set.copyOf(footprint);
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
        nativePreparation = null;
        nativePreparationEntries = null;
        nativeProviders.clear();
        plannedProviders.clear();
        singleNativeOrders.clear();
        inspectBareNative = null;
        repairs = null;
        capturedPolicy = null;
        entries.clear();
        acquired.clear();
        identities.clear();
        targetPlans.clear();
        orders.clear();
        cursors.clear();
        footprint.clear();
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
        if (closed && callbacks == 0) clear();
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

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            for (int i = entries.size() - 1; i >= 0; i--) closeEntry(entries.get(i));
            if (cleanupFailures > 4)
                com.mojang.logging.LogUtils.getLogger()
                        .error("{} additional provider cleanup failures", cleanupFailures - 4);
        } finally {
            if (callbacks == 0) clear();
        }
    }
}
