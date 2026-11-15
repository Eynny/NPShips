package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchContext;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.integration.computercraft.ComputerFiles;
import com.eynny.npships.resources.PreparedTemplateResources;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.eynny.npships.ship.EntityPlacementScope;
import com.google.gson.JsonObject;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;

/** Per-spawn state. No global pending graph, no per-tick polling or cross-attempt ID reuse. */
public final class PatchedTemplate extends StructureTemplate implements AutoCloseable {
    SpawnTimingProfiler timing;

    public void timing(SpawnTimingProfiler timing) {
        this.timing = timing;
    }

    final PreparedTemplateResources resources;

    public com.eynny.npships.authoring.document.SchematicDocument assemblyDocument() {
        return sourceDoc;
    }

    public ResourceWorkMeter work() {
        return resources == null ? null : resources.work();
    }

    @Override
    public void load(
            net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blocks,
            CompoundTag tag) {
        if (resources == null) {
            super.load(blocks, tag);
            return;
        }
        long reserved = resources.beforeNativeLoad(tag);
        boolean success = false;
        try {
            super.load(blocks, tag);
            resources.afterNativeLoad(tag, reserved);
            success = true;
        } finally {
            if (!success) resources.release(reserved);
        }
    }

    final CompoundTag source;
    final com.eynny.npships.authoring.document.SchematicDocument sourceDoc;
    final List<JsonObject> patches;
    Map<BlockPos, BlockPos> moved = new HashMap<>();
    final Set<BlockPos> tracked;
    private static final ThreadLocal<PatchedTemplate> ASSEMBLING = new ThreadLocal<>();
    ServerLevel level;
    BlockPos origin;
    private boolean gasRestored;
    private final Set<UUID> physicalIds = new LinkedHashSet<>();

    public List<
                    Map.Entry<
                            net.minecraft.resources.ResourceLocation,
                            java.util.function.Consumer<ServerSubLevel>>>
            delayedRepairs() {
        return externalHandlers.delayedRepairs();
    }

    public List<UUID> physicalIds() {
        return List.copyOf(physicalIds);
    }

    private net.minecraft.core.Vec3i directSize;

    @Override
    public net.minecraft.core.Vec3i getSize() {
        return directSize == null ? super.getSize() : directSize;
    }

    /** The caller transfers its private composed root and keeps its existing reservation owner. */
    public static Construction beginConstruction(
            CompoundTag owned, PreparedTemplateResources resources) {
        return new Construction(owned, resources);
    }

    /** No native template block list is needed by the direct placement owner. */
    private PatchedTemplate(
            CompoundTag owned,
            PreparedTemplateResources resources,
            com.eynny.npships.authoring.document.SchematicDocument document,
            List<JsonObject> patches,
            List<JsonObject> entityPatches,
            Set<BlockPos> tracked) {
        this.resources = resources;
        this.source = owned;
        this.sourceDoc = document;
        this.patches = patches;
        this.entityPatches = entityPatches;
        this.tracked = tracked;
        var size = owned.getList("size", Tag.TAG_INT);
        directSize = new BlockPos(size.getInt(0), size.getInt(1), size.getInt(2));
    }

    public static final class Construction implements AutoCloseable {
        private final CompoundTag source;
        private final PreparedTemplateResources resources;
        private final com.eynny.npships.authoring.document.SchematicDocument.RuntimeRead document;
        private final List<JsonObject> patches = new ArrayList<>();
        private final Set<BlockPos> imported = new HashSet<>();
        private PreparedTemplateResources.Copy<ListTag> palette;
        private com.eynny.npships.authoring.document.SchematicDocument resultDocument;
        private SchematicPatches.RuntimeValidation validation;
        private SchematicPatches.CaptureValidation capture;
        private PatchedTemplate result;
        private int phase, blockIndex, patchIndex;
        private long progress;
        private boolean closed, transferred, cleanupComplete;
        private int cleanupPhase;
        private long cleanupProgress;

        private Construction(CompoundTag owned, PreparedTemplateResources resources) {
            this.source = Objects.requireNonNull(owned);
            this.resources = Objects.requireNonNull(resources);
            document =
                    com.eynny.npships.authoring.document.SchematicDocument.beginOwnedRuntime(
                            source, resources::reserveCapacity);
        }

        public boolean step(java.util.function.BooleanSupplier canContinue) {
            if (closed) throw new IllegalStateException("Template construction is closed");
            boolean[] granted = {false};
            java.util.function.BooleanSupplier nested =
                    () -> {
                        if (granted[0]) {
                            granted[0] = false;
                            return true;
                        }
                        return canContinue.getAsBoolean();
                    };
            while (phase < 7 && canContinue.getAsBoolean()) {
                granted[0] = true;
                switch (phase) {
                    case 0 -> {
                        if (!document.step(nested)) return false;
                        resultDocument = document.result();
                        palette =
                                resources.copyIncrementally(
                                        source.getList("palette", Tag.TAG_COMPOUND));
                        phase = 1;
                    }
                    case 1 -> {
                        if (!palette.step(nested)) return false;
                        phase = 2;
                    }
                    case 2 -> {
                        var blocks = source.getList("blocks", Tag.TAG_COMPOUND);
                        if (blockIndex < blocks.size()) {
                            var block = blocks.getCompound(blockIndex++);
                            var at = SchematicPatches.blockPosition(block);
                            if (block.contains(com.eynny.npships.authoring.io.SubLevelImport.GROUP))
                                imported.add(at);
                            for (var patch : SchematicPatches.embeddedEntries(block)) {
                                patch.add("pos", SchematicPatches.vector(at));
                                patches.add(patch);
                            }
                        } else {
                            validation =
                                    new SchematicPatches.RuntimeValidation(
                                            resultDocument,
                                            true,
                                            patches,
                                            palette.result(),
                                            source,
                                            resources::reserveCapacity);
                            phase = 3;
                        }
                    }
                    case 3 -> {
                        if (validation.step()) {
                            capture =
                                    new SchematicPatches.CaptureValidation(
                                            source, resultDocument, patches);
                            phase = 4;
                        }
                    }
                    case 4 -> {
                        if (capture.step()) phase = 5;
                    }
                    case 5 -> {
                        result =
                                new PatchedTemplate(
                                        source,
                                        resources,
                                        resultDocument,
                                        patches,
                                        EntityPatches.entries(resultDocument),
                                        imported);
                        result.validatedAuthorInputs = validation.preparedAuthorData();
                        phase = 6;
                    }
                    case 6 -> {
                        if (patchIndex == patches.size()) {
                            phase = 7;
                            return true;
                        }
                        var patch = patches.get(patchIndex++);
                        if (ExternalPatches.external(patch)
                                || PatchSourceOperationsDispatch.all().stream()
                                        .anyMatch(code -> code.tracks(patch))) {
                            var at = SchematicPatches.position(patch.getAsJsonArray("pos"));
                            result.tracked.add(at);
                            if (!ExternalPatches.external(patch)) result.collect(patch, at);
                        }
                    }
                    default ->
                            throw new IllegalStateException("Unknown template construction phase");
                }
                progress++;
            }
            return phase == 7;
        }

        public long progress() {
            return progress + document.progress() + (palette == null ? 0 : palette.progress());
        }

        public PatchedTemplate result() {
            if (closed || phase != 7)
                throw new IllegalStateException("Template construction is incomplete");
            transferred = true;
            return result;
        }

        public void beginCleanup() {
            if (closed) return;
            closed = true;
            if (!transferred && result != null) result.beginCleanup();
        }

        public long cleanupProgress() {
            return cleanupProgress;
        }

        /** Keeps cancellation of a partly constructed template with its existing attempt owner. */
        public boolean stepCleanup(java.util.function.BooleanSupplier canContinue) {
            beginCleanup();
            java.util.function.BooleanSupplier allowance =
                    () -> {
                        if (!canContinue.getAsBoolean()) return false;
                        cleanupProgress++;
                        return true;
                    };
            while (!cleanupComplete) {
                switch (cleanupPhase) {
                    case 0 -> {
                        if (!transferred && result != null && !result.stepCleanup(allowance))
                            return false;
                        result = null;
                        cleanupPhase++;
                    }
                    case 1 -> {
                        if (!allowance.getAsBoolean()) return false;
                        document.close();
                        resultDocument = null;
                        cleanupPhase++;
                    }
                    case 2 -> {
                        if (!allowance.getAsBoolean()) return false;
                        if (validation != null) validation.close();
                        validation = null;
                        capture = null;
                        cleanupPhase++;
                    }
                    case 3 -> {
                        if (!allowance.getAsBoolean()) return false;
                        if (palette != null) palette.close();
                        palette = null;
                        cleanupComplete = true;
                    }
                    default ->
                            throw new IllegalStateException(
                                    "Invalid template construction cleanup phase");
                }
            }
            return true;
        }

        /** Synchronous drain is reserved for explicit shutdown and detached fixture ownership. */
        @Override
        public void close() {
            while (!stepCleanup(() -> true)) {}
        }
    }

    public PatchedTemplate(CompoundTag source) {
        this(source, null);
    }

    public PatchedTemplate(CompoundTag source, PreparedTemplateResources resources) {
        this.tracked = new HashSet<>();
        this.resources = resources;
        this.source = resources == null ? source.copy() : resources.copy(source);
        com.eynny.npships.authoring.SchematicSafety.validate(this.source);
        // SchematicDocument owns a second defensive root and a separate palette copy.
        if (resources != null) {
            resources.reserveCopy(this.source);
            resources.reserveCopy(
                    this.source.contains("palette", Tag.TAG_LIST)
                            ? this.source.getList("palette", Tag.TAG_COMPOUND)
                            : this.source.getList("palettes", Tag.TAG_LIST).getList(0));
        }
        sourceDoc = new com.eynny.npships.authoring.document.SchematicDocument(this.source, null);
        SchematicPatches.validatePatches(sourceDoc, true);
        SchematicPatches.requireCapture(this.source, sourceDoc);
        patches = SchematicPatches.fromTemplate(source);
        entityPatches = EntityPatches.entries(sourceDoc);
        for (var value : source.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            if (block.contains(com.eynny.npships.authoring.io.SubLevelImport.GROUP))
                tracked.add(SchematicPatches.blockPosition(block));
        }
        for (var patch : patches)
            if (ExternalPatches.external(patch)
                    || PatchSourceOperationsDispatch.all().stream()
                            .anyMatch(code -> code.tracks(patch))) {
                BlockPos owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                tracked.add(owner);
                if (!ExternalPatches.external(patch)) collect(patch, owner);
            }
    }

    private Preparation preparation;
    private boolean prepared;
    private boolean closed;
    private boolean nativePlacementStarted;
    private CompoundTag directAssemblyInput;
    private long directBytes;
    private boolean directPlanningReleased;

    private com.eynny.npships.api.patches.PatchPolicy assemblyPolicy;

    /** Admit direct construction scratch against the existing attempt reservation. */
    public void reserveDirectCapacity(long bytes) {
        requireOpen();
        if (!prepared) throw new IllegalStateException("Direct admission requires preparation");
        long next = Math.addExact(directBytes, bytes);
        capabilityResources().reserveCapacity(bytes);
        directBytes = next;
    }

    /**
     * The direct owner drops its maps first; helper-owned capacity stays charged through cleanup.
     */
    public void releaseDirectPlanning() {
        directAssemblyInput = null;
        directPlanningReleased = true;
        if (cleanupComplete) releaseDirectCapacity();
    }

    private void releaseDirectCapacity() {
        if (!directPlanningReleased || directBytes == 0) return;
        capabilityResources().release(directBytes);
        directBytes = 0;
    }

    public com.eynny.npships.api.patches.PatchPolicy assemblyPolicy() {
        requireOpen();
        if (!prepared) throw new IllegalStateException("Assembly policy requires preparation");
        return assemblyPolicy;
    }

    /** Borrow the prepared graph; all tags retain this template's resource/lifetime ownership. */
    public CompoundTag directInput() {
        requireOpen();
        if (!prepared || directAssemblyInput == null)
            throw new IllegalStateException("Direct input is not prepared");
        return directAssemblyInput;
    }

    private java.util.function.Function<BlockPos, BlockPos> directResolver;

    public void beginDirectPlacement(
            java.util.function.Function<BlockPos, BlockPos> resolver,
            net.minecraft.world.level.Level sourceView)
            throws Exception {
        requireOpen();
        if (directResolver != null)
            throw new IllegalStateException("Direct placement already opened");
        directResolver = java.util.Objects.requireNonNull(resolver);
        integrationState.beginDirectPlacement(sourceView);
    }

    public boolean prepareDirectPlacementStep() throws Exception {
        requireOpen();
        return integrationState.prepareDirectPlacementStep();
    }

    public BlockPos findFinalPosition(BlockPos local) {
        requireOpen();
        var movedPosition = moved.get(local);
        if (movedPosition != null) return movedPosition;
        return directResolver == null ? origin.offset(local) : directResolver.apply(local);
    }

    public BlockPos resolveFinalPosition(BlockPos local) {
        var position = findFinalPosition(local);
        if (position == null)
            throw new IllegalStateException("Direct target not allocated: " + local);
        return position;
    }

    public void prepareInitialBlock(BlockPos local, CompoundTag data) throws Exception {
        integrationState.prepareInitialBlock(local, data);
        externalHandlers.nativeInitialData(integrationState, local, data);
    }

    public void initialBlockPlaced(
            BlockPos local, net.minecraft.world.level.block.entity.BlockEntity entity)
            throws Exception {
        integrationState.initialBlockPlaced(local, entity);
        externalHandlers.nativeInitialPlaced(integrationState, local, entity);
    }

    /** Register one address before its initial BE load, without scanning a whole body. */
    public void directCellPlaced(BlockPos local, BlockPos target, ServerSubLevel body) {
        requireOpen();
        physicalIds.add(body.getUniqueId());
        if (!tracked.contains(local)) return;
        if (placementData != null) placementData.transferred(local, target, body);
        moved.put(local, target);
    }

    public Set<UUID> directPhysicalIds() {
        return Collections.unmodifiableSet(physicalIds);
    }

    private int directFinishPhase, directRecord;

    /** One provider cursor step or native lifecycle boundary; no private full-tick allowance. */
    public boolean finishDirectStep(java.util.List<UUID> mobs) throws Exception {
        requireOpen();
        switch (directFinishPhase) {
            case 0 -> {
                if (!patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_ASSEMBLY, 1, null))
                    return false;
            }
            case 1 -> {
                if (!patchStage(
                        com.eynny.npships.api.patches.PatchStage.BEFORE_NATIVE_ASSEMBLY, 1, null))
                    return false;
                gasRestored = true;
            }
            case 2 -> {
                try (var observation = observe(this)) {
                    if (!patchStage(
                            com.eynny.npships.api.patches.PatchStage.BEFORE_BODY_PLACEMENT,
                            1,
                            null)) return false;
                }
            }
            case 3 -> {
                if (!finalPlacementCaptured) completePlacement(physicalIds);
            }
            case 4 -> {
                if (!entityModeChecked) requireEntityPlacement(!physicalIds.isEmpty(), mobs);
            }
            case 5 -> {
                try (var reads = placementData == null ? null : placementData.nativeReads()) {
                    externalHandlers.nativeBodies(integrationState);
                }
            }
            case 6 -> {
                if (!patchStage(
                        com.eynny.npships.api.patches.PatchStage.AFTER_BODY_PLACEMENT, 1, null))
                    return false;
            }
            case 7, 8 -> {
                var stage =
                        directFinishPhase == 7
                                ? com.eynny.npships.api.patches.PatchStage.RESTORE_LOCAL
                                : com.eynny.npships.api.patches.PatchStage.RESTORE_REFERENCES;
                if (directRecord < patches.size()) {
                    var patch = patches.get(directRecord);
                    integrationState.currentPatch = patch;
                    try {
                        if (patchStage(
                                stage, 1, SchematicPatches.position(patch.getAsJsonArray("pos"))))
                            directRecord++;
                    } finally {
                        integrationState.currentPatch = null;
                    }
                    return false;
                }
                directRecord = 0;
            }
            case 9 -> {
                if (!patchStage(com.eynny.npships.api.patches.PatchStage.AFTER_PLACEMENT, 1, null))
                    return false;
            }
            case 10 -> externalHandlers.requireFinalRoots(entityPlacement);
            default -> {
                return true;
            }
        }
        directFinishPhase++;
        return false;
    }

    /** One placement boundary shared by native templates and directly populated native plots. */
    public AutoCloseable beginNativePlacement() {
        requireOpen();
        if (entityPlacement == null) {
            if (!entityPatches.isEmpty())
                throw new IllegalStateException("Entity patches require preparation");
            entityPlacement = new EntityPlacementScope(level);
        }
        var scope = entityPlacement.open();
        try {
            patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_NATIVE_PLACEMENT);
            if (!nativePlacementStarted) {
                computerFiles.commit();
                nativePlacementStarted = true;
            }
            return scope;
        } catch (RuntimeException | Error error) {
            try {
                scope.close();
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    final ComputerFiles computerFiles = new ComputerFiles();
    private final ExternalPatchHandlers externalHandlers = new ExternalPatchHandlers();
    private BuiltinPatchState integrationState;
    private PreparedPatchData preparedData;
    private PatchPlacementState placementData;
    private PreparedTemplateResources capabilityResources;
    private boolean planningCapabilities;

    private PreparedTemplateResources capabilityResources() {
        if (resources != null) return resources;
        if (capabilityResources == null)
            capabilityResources =
                    new PreparedTemplateResources(
                            com.eynny.npships.resources.ServerResourcePolicy.memory(
                                    level.getServer()),
                            new ResourceWorkMeter());
        return capabilityResources;
    }

    private PatchContext basePatchContext;
    private final java.util.function.Function<Class<?>, ?> patchCapabilities =
            this::patchCapability;
    private EntityPlacementScope entityPlacement;
    private final List<JsonObject> entityPatches;
    private boolean entityModeChecked;

    private int cleanupPhase;
    private long cleanupProgress;
    private boolean cleanupComplete, cleanupRunning;

    /** Refuse new callbacks immediately; keep all existing owners charged until their cleanup. */
    public void beginCleanup() {
        if (closed) return;
        closed = true;
        if (timing != null && resources != null)
            timing.ownedResources(resources.work(), resources.bytes());
        externalHandlers.beginCleanup();
        if (integrationState != null) integrationState.beginCleanup();
    }

    public long cleanupProgress() {
        return cleanupProgress;
    }

    public boolean stepCleanup(java.util.function.BooleanSupplier canContinue) {
        beginCleanup();
        if (cleanupComplete) return true;
        if (cleanupRunning || externalHandlers.cleanupDeferred()) return false;
        cleanupRunning = true;
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CLEANUP);
        java.util.function.BooleanSupplier allowance =
                () -> {
                    if (!canContinue.getAsBoolean()) return false;
                    cleanupProgress++;
                    return true;
                };
        try {
            while (!cleanupComplete) {
                switch (cleanupPhase) {
                    case 0 -> {
                        if (!externalHandlers.stepCleanup(allowance)) return false;
                        cleanupPhase++;
                    }
                    case 1 -> {
                        if (!allowance.getAsBoolean()) return false;
                        if (entityPlacement != null) entityPlacement.close();
                        entityPlacement = null;
                        cleanupPhase++;
                    }
                    case 2 -> {
                        if (!computerFiles.stepCleanup(allowance)) return false;
                        cleanupPhase++;
                    }
                    case 3 -> {
                        if (!allowance.getAsBoolean()) return false;
                        if (integrationState != null && !integrationState.stepCleanup())
                            return false;
                        integrationState = null;
                        cleanupPhase++;
                    }
                    case 4 -> {
                        if (preparedData != null && !preparedData.stepCleanup(allowance))
                            return false;
                        preparedData = null;
                        cleanupPhase++;
                    }
                    case 5 -> {
                        if (!allowance.getAsBoolean()) return false;
                        if (placementData != null) placementData.close();
                        placementData = null;
                        cleanupPhase++;
                    }
                    case 6 -> {
                        if (!allowance.getAsBoolean()) return false;
                        // Their private graphs have no remaining callback owner. Native/source data
                        // remain readable and retain this template's resource token until
                        // collection.
                        if (preparation != null && preparation.entityValidation != null)
                            preparation.entityValidation.close();
                        preparation = null;
                        directAssemblyInput = null;
                        directResolver = null;
                        basePatchContext = null;
                        authorInputs = Map.of();
                        validatedAuthorInputs = Map.of();
                        moved = Map.of();
                        releaseDirectCapacity();
                        level = null;
                        if (capabilityResources != null) capabilityResources.retire();
                        if (resources != null) resources.retire();
                        cleanupComplete = true;
                    }
                    default -> throw new IllegalStateException("Invalid template cleanup phase");
                }
            }
            return cleanupComplete;
        } finally {
            cleanupRunning = false;
            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    /**
     * Explicit synchronous shutdown/fixture drain; active callbacks defer to their existing owner.
     */
    public void close() {
        beginCleanup();
        if (cleanupRunning || externalHandlers.cleanupDeferred()) return;
        while (!stepCleanup(() -> true)) {
            if (externalHandlers.cleanupDeferred()) return;
        }
    }

    private java.util.Map<
                    net.minecraft.resources.ResourceLocation,
                    java.util.List<com.eynny.npships.api.patches.PatchAuthoring.Data>>
            authorInputs = new java.util.HashMap<>();

    private Map<
                    net.minecraft.resources.ResourceLocation,
                    List<com.eynny.npships.api.patches.PatchAuthoring.Data>>
            validatedAuthorInputs = Map.of();

    private java.util.List<com.eynny.npships.api.patches.PatchAuthoring.Data> authorData(
            net.minecraft.resources.ResourceLocation type) {
        if (!source.contains(com.eynny.npships.authoring.document.SchematicExtensions.KEY))
            return java.util.List.of();
        var prepared = externalHandlers.preparedAuthorData(type);
        if (prepared != null) return prepared;
        // The runtime factory already canonicalized available providers under its allowance.
        // Missing providers still follow the original capability lookup and error policy.
        var validated = validatedAuthorInputs.get(type);
        if (validated != null) return validated;
        return authorInputs.computeIfAbsent(
                type,
                id -> {
                    var owner =
                            resources != null
                                    ? resources
                                    : level == null ? null : capabilityResources();
                    long[] temporary = {0};
                    try {
                        return com.eynny.npships.authoring.document.SchematicExtensions
                                .upgradedData(
                                        sourceDoc,
                                        id,
                                        tag -> {
                                            if (owner != null)
                                                temporary[0] =
                                                        Math.addExact(
                                                                temporary[0],
                                                                owner.reserveCopy(tag));
                                        },
                                        tag -> {
                                            if (owner != null)
                                                temporary[0] =
                                                        Math.addExact(
                                                                temporary[0],
                                                                owner.reserveGrowth(tag));
                                        },
                                        (tag, changedVersion) -> {
                                            if (owner != null) {
                                                if (changedVersion) owner.reserveGrowth(tag);
                                                else owner.reserveCopy(tag);
                                            }
                                        });
                    } finally {
                        if (owner != null) owner.release(temporary[0]);
                    }
                });
    }

    /** Detached source reads and the internal native adapter capability share template lifetime. */
    private Object patchCapability(Class<?> type) {
        if (type == BuiltinPatchState.class) return integrationState;
        if (type == PreparedPatchData.class) {
            if (preparedData == null && planningCapabilities)
                preparedData = new PreparedPatchData(source, capabilityResources());
            return preparedData;
        }
        if (type == PatchPlacementState.class) return placementData;
        if (type == com.eynny.npships.api.patches.PatchTemplateInput.class)
            return new com.eynny.npships.api.patches.PatchTemplateInput(
                    () -> resources == null ? source.copy() : resources.copy(source),
                    source::contains,
                    id -> authorData(id));
        return null;
    }

    private boolean patchStage(
            com.eynny.npships.api.patches.PatchStage stage, long budgetNanos, BlockPos unit) {
        if (!externalHandlers.has(stage)) return true;
        return externalHandlers.step(
                stage, patchContext(), patchCapabilities, budgetNanos, entityPlacement, unit);
    }

    private void patchStage(com.eynny.npships.api.patches.PatchStage stage) {
        patchStage(stage, Long.MAX_VALUE, null);
    }

    /** Native source order is a host-unit boundary; all local units finish before references. */
    private void patchRecords(com.eynny.npships.api.patches.PatchStage stage) {
        if (!externalHandlers.has(stage)) return;
        try {
            for (var patch : patches) {
                integrationState.currentPatch = patch;
                patchStage(
                        stage,
                        Long.MAX_VALUE,
                        SchematicPatches.position(patch.getAsJsonArray("pos")));
            }
        } finally {
            integrationState.currentPatch = null;
        }
    }

    private PatchContext patchContext() {
        if (basePatchContext == null)
            basePatchContext = new PatchContext(level, origin, this::resolveFinalPosition);
        return basePatchContext;
    }

    public void prepare(ServerLevel level, BlockPos origin) throws Exception {
        while (!prepareStep(level, origin, 8_000_000L)) {
            // Compatibility helper for detached callers; production uses the shared-budget cursor.
        }
    }

    private java.util.function.BooleanSupplier preparationAllowance;

    /** One host preparation portion under the existing attempt's shared deadline. */
    public boolean prepareStep(
            ServerLevel level, BlockPos origin, java.util.function.BooleanSupplier allowed)
            throws Exception {
        var previous = preparationAllowance;
        preparationAllowance = allowed;
        try {
            return prepareStep(level, origin, 1);
        } finally {
            preparationAllowance = previous;
        }
    }

    public boolean prepareStep(ServerLevel level, BlockPos origin, long budgetNanos)
            throws Exception {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PREPARE);
        try {
            if (!level.getServer().isSameThread())
                throw new IllegalStateException("Preparation requires server thread");
            if (this.level != null && (this.level != level || !this.origin.equals(origin)))
                throw new IllegalStateException("Template preparation belongs to one placement");
            if (closed) throw new IllegalStateException("Template provider state is closed");
            if (prepared) return true;
            try {
                if (preparation == null) preparation = new Preparation(level, origin);
                if (!preparation.step(Math.max(1, budgetNanos))) return false;
                requireOpen();
                prepared = true;
                preparation = null;
                return true;
            } catch (Exception | Error error) {
                if (preparationAllowance == null) close();
                throw error;
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    /** Per-attempt preparation; no blocks/entities enter the world before it completes. */
    private final class Preparation {
        private CompoundTag copy;
        private int blockIndex, wireIndex, initialization, finalization;
        private Iterator<BlockPos> footprint;
        private Boolean sourcePassNeeded;
        private EntityPatches.RuntimeValidation entityValidation;
        private final PatchAvailability availability = new PatchAvailability();
        private int availabilityIndex;
        private ExternalPatchHandlers.Planning handlerPlanning;

        Preparation(ServerLevel level, BlockPos origin) {
            PatchedTemplate.this.origin = origin;
            PatchedTemplate.this.level = level;
            assemblyPolicy = com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy();
            integrationState = new BuiltinPatchState(PatchedTemplate.this);
        }

        private boolean initialize() throws Exception {
            switch (initialization) {
                case 0 -> {
                    if (entityValidation == null)
                        entityValidation =
                                new EntityPatches.RuntimeValidation(
                                        sourceDoc,
                                        source,
                                        patches,
                                        entityPatches,
                                        true,
                                        capabilityResources()::reserveCapacity);
                    if (!entityValidation.step()) return false;
                    if (availabilityIndex < patches.size()) {
                        String unavailable =
                                availability.unavailable(List.of(patches.get(availabilityIndex++)));
                        if (!unavailable.isEmpty()) throw new IllegalArgumentException(unavailable);
                        return false;
                    }
                }
                case 1 -> {
                    if (!integrationState.prepareIndexStep()) return false;
                }
                case 2 -> {
                    int[] portion = {0};
                    if (!integrationState.stepPreparedCopy(() -> portion[0]++ == 0)) return false;
                    copy = integrationState.preparedCopy();
                }
                case 3 -> {
                    planningCapabilities = true;
                    try {
                        if (handlerPlanning == null)
                            handlerPlanning =
                                    externalHandlers.beginPlan(
                                            sourceDoc,
                                            patches,
                                            entityPatches,
                                            assemblyPolicy,
                                            new com.eynny.npships.api.patches.PatchExecutionContext(
                                                    patchContext(), patchCapabilities),
                                            integrationState.sourcePalette(),
                                            entityValidation.targets(),
                                            source,
                                            capabilityResources()::reserveCapacity,
                                            capabilityResources()::release);
                        int[] portion = {128};
                        if (!handlerPlanning.step(
                                () ->
                                        portion[0]-- > 0
                                                && (preparationAllowance == null
                                                        || preparationAllowance.getAsBoolean())))
                            return false;
                        handlerPlanning.close();
                        handlerPlanning = null;
                    } finally {
                        planningCapabilities = false;
                    }
                    footprint = externalHandlers.footprint().iterator();
                }
                case 4 -> {
                    if (footprint.hasNext()) {
                        tracked.add(footprint.next());
                        return false;
                    }
                    footprint = null;
                    entityPlacement =
                            new EntityPlacementScope(level, externalHandlers.requiredRoots());
                }
                default -> {
                    return true;
                }
            }
            initialization++;
            return false;
        }

        boolean step(long budgetNanos) throws Exception {
            if (!initialize()) return false;
            long start = System.nanoTime();
            if (!patchStage(
                    com.eynny.npships.api.patches.PatchStage.INPUT_VALIDATION,
                    Math.max(1, budgetNanos - (System.nanoTime() - start)),
                    null)) return false;
            if (!patchStage(
                    com.eynny.npships.api.patches.PatchStage.PREPARE,
                    Math.max(1, budgetNanos - (System.nanoTime() - start)),
                    null)) return false;
            var blocks = copy.getList("blocks", Tag.TAG_COMPOUND);
            while (blockIndex < blocks.size()) {
                block(blocks.get(blockIndex++));
                if (System.nanoTime() - start >= budgetNanos) return false;
            }
            if (sourcePassNeeded == null)
                sourcePassNeeded =
                        externalHandlers.needsSourcePass(
                                com.eynny.npships.api.patches.PatchStage.PREPARE_WIRES,
                                patchContext(),
                                patchCapabilities);
            if (!sourcePassNeeded) wireIndex = blocks.size();
            while (wireIndex < blocks.size()) {
                wire(blocks.get(wireIndex++));
                if (System.nanoTime() - start >= budgetNanos) return false;
            }
            return finishStep();
        }

        private void block(Tag value) throws Exception {
            var block = (CompoundTag) value;
            integrationState.core(block);
            if (externalHandlers.has(com.eynny.npships.api.patches.PatchStage.PREPARE_BLOCK))
                patchStage(
                        com.eynny.npships.api.patches.PatchStage.PREPARE_BLOCK,
                        Long.MAX_VALUE,
                        SchematicPatches.blockPosition(block));
        }

        private void wire(Tag value) {
            integrationState.currentBlock = (CompoundTag) value;
            patchStage(
                    com.eynny.npships.api.patches.PatchStage.PREPARE_WIRES,
                    Long.MAX_VALUE,
                    SchematicPatches.blockPosition(integrationState.currentBlock));
        }

        private boolean finishStep() {
            switch (finalization) {
                case 0 -> {
                    integrationState.currentBlock = null;
                    if (!patchStage(
                            com.eynny.npships.api.patches.PatchStage.PREPARE_REFERENCES, 1, null))
                        return false;
                }
                case 1 -> {
                    int[] portion = {128};
                    if (preparedData != null
                            && !preparedData.attachStep(
                                    copy,
                                    () ->
                                            portion[0]-- > 0
                                                    && (preparationAllowance == null
                                                            || preparationAllowance
                                                                    .getAsBoolean()))) return false;
                }
                case 2 -> {
                    if (!patchStage(
                            com.eynny.npships.api.patches.PatchStage.PREPARE_FINALIZE, 1, null))
                        return false;
                }
                case 3 -> externalHandlers.delayedRepairs();
                case 4 -> {
                    if (externalHandlers.placementRequested() || !tracked.isEmpty()) {
                        capabilityResources()
                                .reserveCapacity(
                                        Math.multiplyExact((long) tracked.size(), 6L * Long.BYTES));
                        placementData = new PatchPlacementState(level, origin, moved);
                    }
                }
                case 5 -> {
                    // The direct owner consumes prepared NBT; no duplicate native block list.
                    if (directSize == null)
                        load(level.registryAccess().lookupOrThrow(Registries.BLOCK), copy);
                    if (preparedData != null) preparedData.preparedLoaded();
                }
                case 6 -> {
                    if (!patchStage(
                            com.eynny.npships.api.patches.PatchStage.TEMPLATE_LOADED, 1, null))
                        return false;
                }
                case 7 -> {
                    directAssemblyInput = copy;
                    integrationState.releasePreparedInput();
                }
                default -> {
                    return true;
                }
            }
            finalization++;
            return false;
        }
    }

    /**
     * Mandatory native defaults/identity sanitation, outside administrator-disableable restoration.
     */
    void coreBlock(
            CompoundTag block,
            Set<BlockPos> ropeEnds,
            ListTag sourcePalette,
            Map<Integer, net.minecraft.world.level.block.state.BlockState> decodedStates)
            throws Exception {
        var sourceState = sourcePalette.getCompound(block.getInt("state"));
        String sourceName = sourceState.getString("Name");
        var data = block.getCompound("nbt");
        // A patch can introduce NBT for a previously pristine block. Native readers often
        // interpret absent settings as zero instead of keeping constructor defaults.
        if (block.contains("nbt")
                || block.contains(SchematicPatches.EMBEDDED)
                || block.getBoolean(SchematicPatches.UNPATCHED)
                || ropeEnds.contains(SchematicPatches.blockPosition(block))) {
            var state =
                    decodedStates.computeIfAbsent(
                            block.getInt("state"),
                            index ->
                                    NbtUtils.readBlockState(
                                            level.holderLookup(Registries.BLOCK),
                                            sourcePalette.getCompound(index)));
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock factory) {
                var entity = factory.newBlockEntity(SchematicPatches.blockPosition(block), state);
                if (entity != null)
                    data = entity.saveWithoutMetadata(level.registryAccess()).merge(data);
                com.eynny.npships.authoring.document.BlockModes.validateData(
                        entity, data, level.registryAccess());
                externalHandlers.nativePrepared(integrationState, entity, block, data);
                for (var code : PatchSourceOperationsDispatch.all())
                    code.sanitizeEntity(entity, data);
            }
            block.put("nbt", data);
        } else if (externalHandlers.inspectBareNative()) {
            var state =
                    decodedStates.computeIfAbsent(
                            block.getInt("state"),
                            index ->
                                    NbtUtils.readBlockState(
                                            level.holderLookup(Registries.BLOCK),
                                            sourcePalette.getCompound(index)));
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock factory)
                externalHandlers.nativePrepared(
                        integrationState,
                        factory.newBlockEntity(SchematicPatches.blockPosition(block), state),
                        block,
                        data,
                        true);
        }
        boolean unpatched = block.getBoolean(SchematicPatches.UNPATCHED);
        if (unpatched) {
            PatchDevices.clearAddresses(sourceName, data);
            block.put("nbt", data);
        }
        for (var code : PatchSourceOperationsDispatch.all())
            if (code.sanitize(
                    sourceName,
                    data,
                    unpatched,
                    ropeEnds.contains(SchematicPatches.blockPosition(block))))
                block.put("nbt", data);
    }

    private void collect(com.google.gson.JsonElement value, BlockPos owner) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("offset"))
                tracked.add(
                        owner.offset(SchematicPatches.position(object.getAsJsonArray("offset"))));
            for (var e : object.entrySet()) collect(e.getValue(), owner);
        } else if (value.isJsonArray()) for (var e : value.getAsJsonArray()) collect(e, owner);
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope observe(StructureTemplate template) {
        var previous = ASSEMBLING.get();
        if (template instanceof PatchedTemplate patched) ASSEMBLING.set(patched);
        else ASSEMBLING.remove();
        return () -> {
            if (previous == null) ASSEMBLING.remove();
            else ASSEMBLING.set(previous);
        };
    }

    /** Native work reentered during this host-owned assembly must retain the same owner. */
    public static boolean observesNativeAssembly(ServerLevel level) {
        var active = ASSEMBLING.get();
        if (active == null || active.level != level) return false;
        active.requireOpen();
        return true;
    }

    /** One synchronous native transfer within the already-owned host assembly observation. */
    public static com.eynny.npships.integration.NativeBlockAccess.Scope nativeTransferScope(
            ServerLevel level,
            dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<BlockPos> blocks) {
        var active = ASSEMBLING.get();
        if (active == null || active.level != level) return null;
        active.requireOpen();
        if (transform.getLevel() != level)
            throw new IllegalStateException("Host assembly changed native level");
        var iterator = blocks.iterator();
        if (!iterator.hasNext()) return null;
        var first = iterator.next();
        var target = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, transform.apply(first));
        var source = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, first);
        if (!(target instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel current))
            throw new IllegalStateException("Host assembly target owner is unavailable");
        if (!com.eynny.npships.integration.NativeBlockAccess.current(level, current))
            throw new IllegalStateException("Host assembly target owner changed");
        return com.eynny.npships.integration.NativeBlockAccess.openTransfer(
                level,
                key -> {
                    active.requireOpen();
                    var at = new net.minecraft.world.level.ChunkPos(key);
                    if (current.getPlot().contains(at)) return current;
                    return source instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body
                                    && body.getPlot().contains(at)
                            ? body
                            : null;
                },
                source != current
                                && source
                                        instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body
                        ? body
                        : null);
    }

    public static void trackPhysicalBody(ServerLevel level, UUID id) {
        var active = ASSEMBLING.get();
        if (active != null && active.level == level && id != null) active.physicalIds.add(id);
    }

    public static void onAssembled(
            ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, ServerSubLevel body) {
        var active = ASSEMBLING.get();
        if (active != null && active.level == level) active.assembled(anchor, blocks, body);
    }

    private void assembled(BlockPos anchor, Iterable<BlockPos> blocks, ServerSubLevel body) {
        physicalIds.add(body.getUniqueId());
        if (tracked.isEmpty() || com.eynny.npships.ship.DirectSpawnOwnership.mapsNativeCells(level))
            return;
        // Native membership callbacks report the actual transfer anchor.
        var offset = body.getPlot().getCenterBlock().subtract(anchor);
        var previous = new HashMap<BlockPos, BlockPos>();
        moved.forEach((local, world) -> previous.put(world, local));
        for (var world : blocks) {
            BlockPos local = previous.getOrDefault(world, world.subtract(origin));
            if (tracked.contains(local)) {
                var next = world.offset(offset);
                if (placementData != null) placementData.transferred(local, next, body);
                moved.put(local, next);
            }
        }
    }

    /** Prepared patch templates may only be placed through their direct construction owner. */
    @Override
    public boolean placeInWorld(
            net.minecraft.world.level.ServerLevelAccessor world,
            BlockPos at,
            BlockPos seed,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
                    settings,
            net.minecraft.util.RandomSource random,
            int flags) {
        requireOpen();
        throw new UnsupportedOperationException(
                "Prepared templates require asynchronous direct construction");
    }

    public void requireEntityPlacement(
            boolean physical, java.util.List<java.util.UUID> capturedMobs) {
        externalHandlers.requireEntityPlacement(entityPlacement, physical, capturedMobs);
        entityModeChecked = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Template provider state is closed");
    }

    public void beforeAssembly(ServerLevel level) throws Exception {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PATCHES);
        try {
            requireOpen();
            if (gasRestored) return;
            patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_ASSEMBLY);
            patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_NATIVE_ASSEMBLY);
            gasRestored = true;

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    public void finish(ServerLevel level) throws Exception {
        finish(level, () -> {});
    }

    private boolean finalPlacementCaptured;

    /** Existing placement owner supplies all main/child IDs after native positioning and yaw. */
    public void completePlacement(java.util.Collection<UUID> ids) {
        requireOpen();
        if (finalPlacementCaptured) throw new IllegalStateException("Placement already captured");
        if (placementData != null) placementData.complete(ids);
        finalPlacementCaptured = true;
    }

    public void finish(ServerLevel level, Runnable placeBodies) throws Exception {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PATCHES);
        try {
            requireOpen();
            beforeAssembly(level);
            try (var scope = observe(this)) {
                patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_BODY_PLACEMENT);
            }
            // All native children now exist. Place the entire assembly before creating any
            // world-space
            // rope points, docking constraints or other device links.
            placeBodies.run();
            if (!finalPlacementCaptured) completePlacement(physicalIds);
            if (!entityModeChecked) requireEntityPlacement(!physicalIds.isEmpty(), List.of());
            try (var reads = placementData == null ? null : placementData.nativeReads()) {
                externalHandlers.nativeBodies(integrationState);
            }
            patchStage(com.eynny.npships.api.patches.PatchStage.AFTER_BODY_PLACEMENT);
            patchRecords(com.eynny.npships.api.patches.PatchStage.RESTORE_LOCAL);
            patchRecords(com.eynny.npships.api.patches.PatchStage.RESTORE_REFERENCES);
            patchStage(com.eynny.npships.api.patches.PatchStage.AFTER_PLACEMENT);
            externalHandlers.requireFinalRoots(entityPlacement);

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }
}
