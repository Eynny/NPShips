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
    final Map<BlockPos, BlockPos> moved = new HashMap<>();
    final Set<BlockPos> tracked = new HashSet<>();
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

    public PatchedTemplate(CompoundTag source) {
        this(source, null);
    }

    public PatchedTemplate(CompoundTag source, PreparedTemplateResources resources) {
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

    /** Existing spawn/attempt owner releases provider state on every terminal path. */
    public void close() {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.CLEANUP);
        try {
            if (closed) return;
            closed = true;
            if (spawnTiming != null && resources != null)
                spawnTiming.ownedResources(resources.work(), resources.bytes());
            try {
                externalHandlers.close();
            } finally {
                try {
                    if (entityPlacement != null) entityPlacement.close();
                } finally {
                    try {
                        computerFiles.close();
                    } finally {
                        try {
                            if (integrationState != null) integrationState.close();
                        } finally {
                            try {
                                if (preparedData != null) preparedData.close();
                            } finally {
                                if (placementData != null) placementData.close();
                                preparation = null;
                                authorInputs.clear();
                                if (capabilityResources != null) capabilityResources.retire();
                            }
                        }
                    }
                }
            }

        } finally {
            if (resources != null) resources.retire();

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private final java.util.Map<
                    net.minecraft.resources.ResourceLocation,
                    java.util.List<com.eynny.npships.api.patches.PatchAuthoring.Data>>
            authorInputs = new java.util.HashMap<>();

    private java.util.List<com.eynny.npships.api.patches.PatchAuthoring.Data> authorData(
            net.minecraft.resources.ResourceLocation type) {
        if (!source.contains(com.eynny.npships.authoring.document.SchematicExtensions.KEY))
            return java.util.List.of();
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
            basePatchContext =
                    new PatchContext(level, origin, p -> moved.getOrDefault(p, origin.offset(p)));
        return basePatchContext;
    }

    public void prepare(ServerLevel level, BlockPos origin) throws Exception {
        while (!prepareStep(level, origin, 8_000_000L)) {
            // Command spawning is synchronous; natural attempts call one step per server tick.
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
                close();
                throw error;
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    /** Per-attempt preparation; no blocks/entities enter the world before it completes. */
    private final class Preparation {
        private final CompoundTag copy;
        private int blockIndex, wireIndex;
        private Boolean sourcePassNeeded;

        Preparation(ServerLevel level, BlockPos origin) throws Exception {
            PatchedTemplate.this.origin = origin;
            PatchedTemplate.this.level = level;
            var patchPolicy = com.eynny.npships.config.NPShipsRuntimeConfig.patchPolicy();
            PatchInstances.validateDocument(sourceDoc);
            integrationState = new BuiltinPatchState(PatchedTemplate.this);
            EntityPatches.validate(sourceDoc, true);
            var entityTargets = EntityPatches.targets(sourceDoc);

            String unavailable = new PatchAvailability().unavailable(patches);
            if (!unavailable.isEmpty()) throw new IllegalArgumentException(unavailable);
            var sourcePalette = integrationState.sourcePalette();
            planningCapabilities = true;
            try {
                externalHandlers.plan(
                        sourceDoc,
                        patches,
                        entityPatches,
                        patchPolicy,
                        new com.eynny.npships.api.patches.PatchExecutionContext(
                                patchContext(), patchCapabilities),
                        sourcePalette,
                        entityTargets,
                        source);
            } finally {
                planningCapabilities = false;
            }
            tracked.addAll(externalHandlers.footprint());
            entityPlacement = new EntityPlacementScope(level, externalHandlers.requiredRoots());
            copy = integrationState.preparedCopy();
        }

        boolean step(long budgetNanos) throws Exception {
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
            finish();
            return true;
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

        private void finish() {
            integrationState.currentBlock = null;
            patchStage(com.eynny.npships.api.patches.PatchStage.PREPARE_REFERENCES);
            // Core/default and reference preparation may replace NBT compounds; index only the
            // final owned graph, immediately before external proposals and native load.
            if (preparedData != null) preparedData.attach(copy);
            patchStage(com.eynny.npships.api.patches.PatchStage.PREPARE_FINALIZE);
            // Validate/order detached delayed code before any placement/world clearing. The
            // existing repair queue receives only this immutable code handoff after assembly.
            externalHandlers.delayedRepairs();
            // Native controller preparation can add mapping targets after initial plan binding.
            // Admit the complete index now, before native placement or assembly can mutate world.
            if (externalHandlers.placementRequested() || !tracked.isEmpty()) {
                // Identifiable map key/value slots are admitted before any native mutation.
                // Native map nodes/DTO graphs are not a complete Java heap guarantee.
                capabilityResources()
                        .reserveCapacity(
                                Math.multiplyExact((long) tracked.size(), 6L * Long.BYTES));
                placementData = new PatchPlacementState(level, origin, moved);
            }
            load(level.registryAccess().lookupOrThrow(Registries.BLOCK), copy);
            if (preparedData != null) preparedData.preparedLoaded();
            patchStage(com.eynny.npships.api.patches.PatchStage.TEMPLATE_LOADED);
            integrationState.releasePreparedInput();
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
        if (tracked.isEmpty()) return;
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

    /**
     * Aeronautics must see the gas before assembly so its native hook transfers the enclosed air
     * mask too.
     */
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
        if (entityPlacement == null) {
            if (!entityPatches.isEmpty())
                throw new IllegalStateException("Entity patches require preparation");
            entityPlacement = new EntityPlacementScope(world.getLevel());
        }
        try (var scope = entityPlacement.open()) {
            // Native block/entity callbacks can observe new IDs before this call returns.
            // Retain their files even if placement subsequently returns false or fails partially.
            patchStage(com.eynny.npships.api.patches.PatchStage.BEFORE_NATIVE_PLACEMENT);
            if (!nativePlacementStarted) {
                computerFiles.commit();
                nativePlacementStarted = true;
            }
            return super.placeInWorld(world, at, seed, settings, random, flags);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Entity mapping cleanup failed", error);
        }
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
