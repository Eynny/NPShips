package com.eynny.npships.ship;

import com.eynny.npships.config.InsideRule;
import com.eynny.npships.config.NPShipsStructureConfig;
import com.eynny.npships.diagnostics.SpawnTimingProfiler;
import com.eynny.npships.lifecycle.NPShipsLifecycleManager;
import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;
import com.eynny.npships.resources.ResourceWorkMeter;
import com.simibubi.create.content.schematics.SchematicProcessor;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluid;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ShipSpawner {

    private ShipSpawner() {}

    /** Returns null after successful static placement without assembly points; failures throw. */
    @Nullable
    public static Ship spawn(
            final ServerLevel level, final ResourceLocation structureId, final BlockPos origin) {
        return spawn(level, structureId, origin, null);
    }

    public static Ship spawn(
            final ServerLevel level,
            final ResourceLocation structureId,
            final BlockPos origin,
            final com.eynny.npships.authoring.StructureSegments.Plan segmentPlan) {

        return spawnInternal(level, structureId, origin, segmentPlan, false, null, 0);
    }

    /**
     * Consumes an already admitted pinned direct template; command/natural paths stay unchanged.
     */
    static Ship spawnPreparedDirect(
            ServerLevel level,
            ResourceLocation id,
            BlockPos origin,
            StructureTemplate template,
            NPShipsSchematicManager.SchematicInfo pinned) {
        return spawnInternal(level, id, origin, null, false, pinned, 0, template, null);
    }

    public static Ship spawnNatural(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan segmentPlan) {
        return spawnInternal(level, structureId, origin, segmentPlan, true, null, 0);
    }

    public static Ship spawnNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan) {
        return spawnInternal(
                level, candidate.structureId(), origin, plan, true, candidate.schematic(), 0);
    }

    public static Ship spawnNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            double yaw) {
        return spawnInternal(
                level, candidate.structureId(), origin, plan, true, candidate.schematic(), yaw);
    }

    public static Ship spawnNatural(
            ServerLevel level,
            ResourceLocation id,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            double yaw) {
        return spawnInternal(level, id, origin, plan, true, null, yaw);
    }

    /** Detached composition gets its own server tick before any world mutation. */
    public static PreparedNatural prepareNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan) {
        return prepareNatural(level, candidate, origin, plan, null);
    }

    public static PreparedNatural prepareNatural(
            ServerLevel level,
            com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan plan,
            @Nullable SpawnTimingProfiler timing) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Preparation requires the server thread");
        var info = candidate.schematic();
        var template =
                info.snapshot() != null
                        ? candidate.raritySelection() == null
                                ? info.snapshot().load(level, plan, origin, -1, timing)
                                : info.snapshot()
                                        .load(
                                                level,
                                                plan,
                                                origin,
                                                candidate.raritySelection().palette(),
                                                timing)
                        : NPShipsSchematicManager.loadTemplate(
                                level, candidate.structureId(), plan, origin, timing);
        return new PreparedNatural(level, candidate, origin.immutable(), plan, template, timing);
    }

    public static final class PreparedNatural implements AutoCloseable {
        private final ServerLevel level;
        private final com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate;
        private final BlockPos origin;
        private final com.eynny.npships.authoring.StructureSegments.Plan plan;
        private final StructureTemplate template;
        private final SpawnTimingProfiler timing;
        private boolean consumed;

        private PreparedNatural(
                ServerLevel level,
                com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
                BlockPos origin,
                com.eynny.npships.authoring.StructureSegments.Plan plan,
                StructureTemplate template,
                SpawnTimingProfiler timing) {
            this.level = level;
            this.candidate = candidate;
            this.origin = origin;
            this.plan = plan;
            this.template = template;
            this.timing = timing;
        }

        @Override
        public void close() {
            consumed = true;
            if (template instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                patched.close();
        }

        public boolean matches(
                ServerLevel level,
                com.eynny.npships.naturalspawn.NaturalSpawnStructureCandidate candidate,
                BlockPos origin,
                com.eynny.npships.authoring.StructureSegments.Plan plan) {
            return !consumed
                    && this.level == level
                    && this.candidate == candidate
                    && this.origin.equals(origin)
                    && this.plan == plan;
        }

        public boolean prepareDevices() throws Exception {
            if (consumed) throw new IllegalStateException("Spawn already consumed");
            return !(template
                            instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                    || patched.prepareStep(level, origin, 8_000_000L);
        }

        /** Final detached composition bounds, before consuming or mutating the world. */
        public Vec3i size() {
            return template.getSize();
        }

        public Ship spawn(double yaw) {
            if (!level.getServer().isSameThread() || consumed)
                throw new IllegalStateException("Prepared spawn is server-thread and single-use");
            consumed = true;
            return spawnInternal(
                    level,
                    candidate.structureId(),
                    origin,
                    plan,
                    true,
                    candidate.schematic(),
                    yaw,
                    template,
                    timing);
        }
    }

    private static Ship spawnInternal(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan segmentPlan,
            boolean natural,
            NPShipsSchematicManager.SchematicInfo pinned,
            double yaw) {
        var timing =
                level.getServer().isSameThread()
                                && com.eynny.npships.config.NPShipsRuntimeConfig
                                        .developerModeEnabled()
                        ? SpawnTimingProfiler.start(
                                null,
                                natural ? "DIRECT_NATURAL" : "MANUAL_DIRECT",
                                level.getServer().overworld().getGameTime())
                        : null;
        long token = timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.SLICE);
        if (timing != null) timing.candidate(structureId.toString());
        String outcome = "FAILED";
        try {
            var result =
                    spawnInternal(
                            level,
                            structureId,
                            origin,
                            segmentPlan,
                            natural,
                            pinned,
                            yaw,
                            null,
                            timing);
            outcome = "SUCCESS";
            return result;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            if (timing != null) timing.discard();
            throw fatal;
        } catch (RuntimeException failure) {
            if (timing != null && timing.active())
                outcome = "FAILED:" + failure.getClass().getSimpleName();
            throw failure;
        } finally {
            if (timing != null) {
                timing.finished(outcome, level.getServer().overworld().getGameTime());
                timing.end(token);
            }
        }
    }

    private static Ship spawnInternal(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan segmentPlan,
            boolean natural,
            NPShipsSchematicManager.SchematicInfo pinned,
            double yaw,
            StructureTemplate prepared,
            @Nullable SpawnTimingProfiler timing) {
        var bridge = com.eynny.npships.integration.events.NPShipsEventBridge.currentOperation();
        if (bridge != null || !level.getServer().isSameThread())
            return spawnOwned(
                    level,
                    structureId,
                    origin,
                    segmentPlan,
                    natural,
                    pinned,
                    yaw,
                    prepared,
                    timing);
        var operation =
                new com.eynny.npships.integration.events.NPShipsEventBridge.Operation(
                        UUID.randomUUID(),
                        com.eynny.npships.api.events.NPShipsLifecycleEvent.Route.DIRECT_INTERNAL,
                        structureId,
                        origin);
        var scope =
                com.eynny.npships.integration.events.NPShipsEventBridge.enter(
                        operation, () -> true);
        final Ship result;
        try (scope) {
            com.eynny.npships.integration.events.NPShipsEventBridge.accepted(level);
            result =
                    spawnOwned(
                            level,
                            structureId,
                            origin,
                            segmentPlan,
                            natural,
                            pinned,
                            yaw,
                            prepared,
                            timing);
        } catch (RuntimeException | LinkageError failure) {
            com.eynny.npships.integration.events.NPShipsEventBridge.complete(
                    level,
                    operation,
                    com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase.FAILED,
                    "PLACEMENT_FAILED",
                    failure.toString(),
                    com.eynny.npships.api.SpawnResult.Placement.UNCONFIRMED,
                    List.of(),
                    null,
                    scope.placementStarted());
            throw failure;
        }
        com.eynny.npships.integration.events.NPShipsEventBridge.complete(
                level,
                operation,
                com.eynny.npships.api.events.NPShipsLifecycleEvent.SpawnPhase.SUCCEEDED,
                "SUCCESS",
                "",
                result == null
                        ? com.eynny.npships.api.SpawnResult.Placement.STATIC
                        : com.eynny.npships.api.SpawnResult.Placement.PHYSICAL,
                result == null ? List.of() : result.getPhysicalIds(),
                origin,
                scope.placementStarted());
        return result;
    }

    private static Ship spawnOwned(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            com.eynny.npships.authoring.StructureSegments.Plan segmentPlan,
            boolean natural,
            NPShipsSchematicManager.SchematicInfo pinned,
            double yaw,
            StructureTemplate prepared,
            @Nullable SpawnTimingProfiler timing) {
        var spawnTiming = timing;
        long spawnToken =
                spawnTiming == null ? 0 : spawnTiming.begin(SpawnTimingProfiler.Stage.PLACEMENT);
        try {
            if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Invalid spawn rotation");
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException(
                        "Physical structure creation must run on the server thread.");
            }

            /*
             * 1. Load template.
             */
            final StructurePlaceSettings settings = new StructurePlaceSettings();

            settings.addProcessor(SchematicProcessor.INSTANCE);

            final StructureTemplate template =
                    prepared != null
                            ? prepared
                            : pinned != null && pinned.snapshot() != null
                                    ? pinned.snapshot().load(level, segmentPlan, origin, -1, timing)
                                    : NPShipsSchematicManager.loadTemplate(
                                            level, structureId, segmentPlan, origin, timing);

            try {
                final Vec3i templateSize = template.getSize();

                final BlockPos size =
                        new BlockPos(templateSize.getX(), templateSize.getY(), templateSize.getZ());

                if (size.getX() <= 0
                        || size.getY() <= 0
                        || size.getZ() <= 0
                        || origin.getY() < level.getMinBuildHeight()
                        || (long) origin.getY() + size.getY() > level.getMaxBuildHeight()
                        || origin.getX() < level.getWorldBorder().getMinX()
                        || origin.getZ() < level.getWorldBorder().getMinZ()
                        || (double) origin.getX() + size.getX() > level.getWorldBorder().getMaxX()
                        || (double) origin.getZ() + size.getZ()
                                > level.getWorldBorder().getMaxZ()) {
                    throw new IllegalArgumentException(
                            "Schematic dimensions or placement exceed world bounds.");
                }

                requirePlacementReady(level, origin, size);

                /*
                 * 2. Determine whether the structure requires
                 *    temporary fluid removal/restoration.
                 */
                final FluidRestoreData fluidRestore =
                        natural
                                ? prepareFluidRestore(
                                        level,
                                        structureId,
                                        pinned != null
                                                ? pinned.config()
                                                : NPShipsSchematicManager.findConfig(
                                                        level.getServer(), structureId.toString()))
                                : null;

                var pointDocument =
                        template instanceof com.eynny.npships.authoring.patches.PatchedTemplate p
                                ? p.assemblyDocument()
                                : null;
                var markers =
                        pointDocument == null
                                ? List.<BlockPos>of()
                                : AssemblyRules.points(pointDocument, origin);
                var permitted =
                        markers.isEmpty()
                                ? Set.<BlockPos>of()
                                : AssemblyRules.membership(pointDocument, origin);
                var coveredByMarker = new java.util.HashSet<BlockPos>();
                var importedGroups =
                        markers.isEmpty()
                                ? java.util.Map.<BlockPos, Set<BlockPos>>of()
                                : com.eynny.npships.integration.contraptions.PhysicalGroups
                                        .membership(pointDocument, origin);

                /*
                 * 3. Clear spawn area.
                 */
                if (template
                        instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched) {
                    try {
                        patched.prepare(level, origin);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "Schematic patch preflight failed for "
                                        + structureId
                                        + ": "
                                        + e.getMessage(),
                                e);
                    }
                }
                try (var direct = prepareDirect(template, level, origin, size)) {
                    // A precommit plan records known owned traversal. No quota can interrupt the
                    // subsequent mutation/finally sequence, and native calls remain atomic.
                    var work =
                            template
                                            instanceof
                                            com.eynny.npships.authoring.patches.PatchedTemplate p
                                    ? p.work()
                                    : null;
                    if (work != null)
                        work.record(
                                ResourceWorkMeter.Family.CLEAR_CELL_PLAN,
                                Math.multiplyExact(
                                        Math.multiplyExact((long) size.getX(), size.getY()),
                                        size.getZ()));
                    com.eynny.npships.integration.events.NPShipsEventBridge.beforePlacement(
                            level, structureId, origin);
                    // Trusted callbacks may change availability; refuse before our destructive
                    // phase.
                    requirePlacementReady(level, origin, size);
                    boolean useDirect = direct != null && direct.environmentReady();
                    if (direct != null && !useDirect) direct.close();
                    boolean directSucceeded = false;
                    // Cover partial clearing failures as well as placement/assembly failures.
                    try {
                        clearArea(level, origin, size);
                        if (useDirect) {
                            long nativeToken =
                                    timing == null
                                            ? 0
                                            : timing.begin(SpawnTimingProfiler.Stage.ASSEMBLY);
                            final List<UUID> ids;
                            try {
                                if (work != null)
                                    work.record(ResourceWorkMeter.Family.ASSEMBLY_CALL, 1);
                                ids = direct.place();
                            } catch (Exception error) {
                                throw new IllegalStateException(
                                        "Direct native placement failed for " + structureId, error);
                            } finally {
                                if (timing != null) timing.end(nativeToken);
                            }
                            var container = SubLevelContainer.getContainer(level);
                            for (var id : ids) {
                                var body = container.getSubLevel(id);
                                if (!(body instanceof ServerSubLevel serverBody)
                                        || body.isRemoved())
                                    throw new IllegalStateException(
                                            "Direct native body disappeared: " + id);
                                registerBody(level, template, serverBody);
                            }
                            finishPatches(
                                    template,
                                    level,
                                    structureId,
                                    origin,
                                    size,
                                    ids,
                                    List.of(),
                                    yaw);
                            cleanupWorldBlockEntities(level, origin, size);
                            var result = physicalResult(level, structureId, origin, ids, List.of());
                            directSucceeded = true;
                            return result;
                        }

                        /*
                         * 4. Place structure into the real world.
                         */
                        final boolean placed;
                        final java.util.List<java.util.UUID> spawnedMobs;
                        long staticToken =
                                timing == null ? 0 : timing.begin(SpawnTimingProfiler.Stage.STATIC);
                        try {
                            try (var capture =
                                    new SpawnedMobCapture(
                                            level,
                                            new net.minecraft.world.phys.AABB(
                                                    net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                            origin),
                                                    net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                            origin.offset(size))))) {
                                if (work != null)
                                    work.record(ResourceWorkMeter.Family.STATIC_PLACEMENT_CALL, 1);
                                placed =
                                        template.placeInWorld(
                                                level,
                                                origin,
                                                origin,
                                                settings,
                                                level.getRandom(),
                                                Block.UPDATE_ALL);
                                spawnedMobs = capture.ids();
                            }

                        } finally {
                            if (timing != null) timing.end(staticToken);
                        }

                        if (!placed) {
                            throw new IllegalStateException(
                                    "Failed placing structure: " + structureId);
                        }

                        /*
                         * 5. Read the authored assembly points
                         *    inside the schematic.
                         *
                         *    Collect them BEFORE starting any assembly.
                         */
                        if (template
                                instanceof
                                com.eynny.npships.authoring.patches.PatchedTemplate patched) {
                            try {
                                patched.beforeAssembly(level);
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Pre-assembly patches failed for "
                                                + structureId
                                                + ": "
                                                + e.getMessage(),
                                        e);
                            }
                        }

                        final List<BlockPos> assemblyPoints = markers;

                        if (assemblyPoints.isEmpty()) {
                            finishPatches(
                                    template,
                                    level,
                                    structureId,
                                    origin,
                                    size,
                                    List.of(),
                                    spawnedMobs,
                                    yaw);
                            if (template
                                            instanceof
                                            com.eynny.npships.authoring.patches.PatchedTemplate
                                                            patched
                                    && !patched.physicalIds().isEmpty())
                                return new Ship(
                                        patched.physicalIds().getFirst(),
                                        structureId,
                                        origin,
                                        patched.physicalIds(),
                                        spawnedMobs);
                            return null;
                        }

                        /*
                         * Hard safety limit.
                         */
                        final int MAX_ASSEMBLY_POINTS = 128;

                        if (assemblyPoints.size() > MAX_ASSEMBLY_POINTS) {

                            throw new IllegalStateException(
                                    "Too many assembly points in structure "
                                            + structureId
                                            + ": "
                                            + assemblyPoints.size()
                                            + " (maximum "
                                            + MAX_ASSEMBLY_POINTS
                                            + ")");
                        }

                        /*
                         * 6. Process assembly points one by one.
                         */
                        int assembledCount = 0;
                        final List<UUID> physicalIds = new ArrayList<>();

                        try (var glue =
                                new AssemblyGlueSnapshot(
                                        level,
                                        new net.minecraft.world.phys.AABB(
                                                net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                        origin),
                                                net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                        origin.offset(size))))) {
                            for (final BlockPos assemblyPoint : assemblyPoints) {

                                /*
                                 * A previous point may already have assembled this component.
                                 * Process each physical component only once.
                                 */
                                if (coveredByMarker.contains(assemblyPoint)) continue;

                                /*
                                 * Snapshot currently existing SubLevels.
                                 */
                                glue.restore();
                                final Set<ServerSubLevel> beforeSubLevels = getSubLevels(level);

                                /*
                                 * Assemble exactly one point through the host physics boundary.
                                 */
                                try (var patchScope =
                                        com.eynny.npships.authoring.patches.PatchedTemplate.observe(
                                                template)) {
                                    long nativeToken =
                                            timing == null
                                                    ? 0
                                                    : timing.begin(
                                                            SpawnTimingProfiler.Stage.ASSEMBLY);
                                    try {
                                        if (work != null)
                                            work.record(ResourceWorkMeter.Family.ASSEMBLY_CALL, 1);
                                        coveredByMarker.addAll(
                                                AssemblyRules.assemble(
                                                        level,
                                                        assemblyPoint,
                                                        permitted,
                                                        importedGroups.get(assemblyPoint)));
                                    } finally {
                                        if (timing != null) timing.end(nativeToken);
                                    }
                                }

                                /*
                                 * Find the bodies created by this assembly.
                                 */
                                final var createdSubLevels = getSubLevels(level);
                                createdSubLevels.removeAll(beforeSubLevels);
                                if (createdSubLevels.isEmpty()) {
                                    throw new IllegalStateException(
                                            "Physics assembly created no new ServerSubLevel at "
                                                    + assemblyPoint);
                                }
                                for (final ServerSubLevel shipSubLevel : createdSubLevels) {
                                    registerBody(level, template, shipSubLevel);
                                    assembledCount++;
                                    physicalIds.add(shipSubLevel.getUniqueId());

                                    /*
                                     * Retain all authored blocks, including native assembly devices, in
                                     * the newly created physical structure.
                                     */

                                }
                            }
                        }

                        /*
                         * 7. All assembly points have now been processed.
                         *
                         * Only NOW clean up leftover BlockEntities
                         * in the original world.
                         */
                        finishPatches(
                                template,
                                level,
                                structureId,
                                origin,
                                size,
                                physicalIds,
                                spawnedMobs,
                                yaw);
                        cleanupWorldBlockEntities(level, origin, size);

                        /*
                         * 8. Safety check.
                         */
                        if (assembledCount == 0) {

                            throw new IllegalStateException(
                                    "No assembly points were successfully processed "
                                            + "for structure: "
                                            + structureId);
                        }

                        /*
                         * 9. Retain the actual physical IDs; keep chat feedback separate from body identity.
                         */
                        if (template
                                instanceof
                                com.eynny.npships.authoring.patches.PatchedTemplate patched)
                            for (var id : patched.physicalIds())
                                if (!physicalIds.contains(id)) physicalIds.add(id);
                        return physicalResult(level, structureId, origin, physicalIds, spawnedMobs);

                    } finally {

                        /*
                         * IMPORTANT:
                         *
                         * If the structure's inside rule is FLUID,
                         * restore the original fluid volume now that
                         * assembly processing has finished.
                         *
                         * This also runs if assembly throws an exception,
                         * preventing permanent destruction of the fluid
                         * volume that existed before spawning.
                         */
                        restoreFluid(level, origin, size, fluidRestore);
                        // A failed fluid-finally is still a failed direct operation.
                        if (directSucceeded) direct.commit();
                    }
                }
            } finally {
                if (template instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                    patched.close();
            }

        } finally {

            if (spawnTiming != null) spawnTiming.end(spawnToken);
        }
    }

    private static DirectAssemblySession prepareDirect(
            StructureTemplate template, ServerLevel level, BlockPos origin, BlockPos size) {
        if (!(template instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched))
            return null;
        try {
            return DirectAssemblySession.prepare(level, patched, origin, size);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Detached native assembly planning failed", error);
        }
    }

    private static void registerBody(
            ServerLevel level, StructureTemplate template, ServerSubLevel body) {
        NPShipsLifecycleManager.track(level, body.getUniqueId());
        NaturalSpawnPhysicalChunkManager.track(body);
        if (template instanceof com.eynny.npships.authoring.patches.PatchedTemplate prepared)
            ShipKineticRepair.schedule(level, body, 5, prepared.delayedRepairs());
        else ShipKineticRepair.schedule(level, body, 5);
    }

    private static Ship physicalResult(
            ServerLevel level,
            ResourceLocation structureId,
            BlockPos origin,
            List<UUID> physicalIds,
            List<UUID> spawnedMobs) {
        var operation = com.eynny.npships.integration.events.NPShipsEventBridge.currentOperation();
        // Commands and automatic attempts own their terminal feedback.
        if (operation == null
                || operation.route()
                                != com.eynny.npships.api.events.NPShipsLifecycleEvent.Route
                                        .AUTOMATIC_SEARCH
                        && operation.route()
                                != com.eynny.npships.api.events.NPShipsLifecycleEvent.Route
                                        .DIRECT_COMMAND)
            level.getServer()
                    .getPlayerList()
                    .broadcastSystemMessage(
                            Component.translatable("npships.spawn.placed", structureId.toString()),
                            false);
        return new Ship(physicalIds.getFirst(), structureId, origin, physicalIds, spawnedMobs);
    }

    /** Existing metadata only: a chunk object is insufficient for native entity/glue queries. */
    private static void requirePlacementReady(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = origin.getX() >> 4; x <= ((long) origin.getX() + size.getX() - 1) >> 4; x++)
            for (int z = origin.getZ() >> 4;
                    z <= ((long) origin.getZ() + size.getZ() - 1) >> 4;
                    z++)
                if (!com.eynny.npships.naturalspawn.NaturalSpawnChunkLoader.readyForPlacement(
                        level, x, z))
                    throw new IllegalArgumentException(
                            "Placement region has unready native chunk/entity data: "
                                    + x
                                    + ","
                                    + z);
    }

    private static void finishPatches(
            StructureTemplate template,
            ServerLevel level,
            ResourceLocation id,
            BlockPos origin,
            BlockPos size,
            List<UUID> assembled,
            List<UUID> mobs,
            double yaw) {
        Runnable placeBodies =
                () -> {
                    var ids = new java.util.LinkedHashSet<UUID>(assembled);
                    if (template
                            instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                        ids.addAll(patched.physicalIds());
                    if (template
                            instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                        patched.requireEntityPlacement(!ids.isEmpty(), mobs);
                    if (ids.isEmpty()) {
                        if (template
                                instanceof
                                com.eynny.npships.authoring.patches.PatchedTemplate patched)
                            patched.completePlacement(ids);
                        return;
                    }
                    var container = SubLevelContainer.getContainer(level);
                    // Restoring child mechanisms changes native mass and pivots.
                    // Flush that
                    // data before computing attachment positions, without advancing the simulation.
                    for (var bodyId : ids) {
                        com.eynny.npships.lifecycle.NPShipsSavedData.get(level)
                                .source(bodyId, id.toString());
                        var body = (ServerSubLevel) container.getSubLevel(bodyId);
                        if (body == null || body.isRemoved())
                            throw new IllegalStateException("Missing assembled body: " + bodyId);
                        body.updateMergedMassData(1);
                        if (yaw == 0) ShipRotation.refreshBounds(body);
                    }
                    if (yaw != 0)
                        ShipRotation.rotate(
                                level,
                                new Ship(ids.iterator().next(), id, origin, List.copyOf(ids), mobs),
                                com.eynny.npships.naturalspawn.NaturalSpawnRotationCheck.pivot(
                                        origin, size),
                                yaw);
                    if (template
                            instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched)
                        patched.completePlacement(ids);
                };
        if (template instanceof com.eynny.npships.authoring.patches.PatchedTemplate patched) {
            try {
                patched.finish(level, placeBodies);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Mapped schematic patches failed for " + id + ": " + e.getMessage(), e);
            }
        } else placeBodies.run();
    }

    /**
     * Determines whether this structure requires a fluid volume to be restored after spawning.
     *
     * <p>The configuration system already guarantees that a valid FLUID inside rule contains a
     * concrete fluid id.
     */
    @Nullable
    private static FluidRestoreData prepareFluidRestore(
            ServerLevel level, ResourceLocation structureId, final NPShipsStructureConfig config) {

        if (config == null) {
            return null;
        }

        final InsideRule inside = config.environment().inside();

        if (inside == null || inside.type() != InsideRule.Type.FLUID) {

            return null;
        }

        final ResourceLocation fluidId = inside.fluid();

        if (fluidId == null) {

            throw new IllegalStateException(
                    "Structure " + structureId + " has inside type FLUID but no fluid id.");
        }

        final ResourceKey<Fluid> key = ResourceKey.create(Registries.FLUID, fluidId);

        final HolderLookup.RegistryLookup<Fluid> registry =
                level.registryAccess().lookupOrThrow(Registries.FLUID);

        final var holder = registry.get(key);

        if (holder.isEmpty()) {

            throw new IllegalStateException(
                    "Unknown fluid for structure " + structureId + ": " + fluidId);
        }

        return new FluidRestoreData(fluidId, holder.get().value());
    }

    /**
     * Restores the entire schematic volume with source blocks of the configured fluid.
     *
     * <p>The volume is intentionally reconstructed from the configured fluid type rather than
     * storing the original contents block-by-block.
     */
    private static void restoreFluid(
            final ServerLevel level,
            final BlockPos origin,
            final BlockPos size,
            @Nullable final FluidRestoreData fluidRestore) {

        if (fluidRestore == null) {
            return;
        }

        final BlockState fluidState = fluidRestore.fluid().defaultFluidState().createLegacyBlock();

        for (int x = 0; x < size.getX(); x++) {

            for (int y = 0; y < size.getY(); y++) {

                for (int z = 0; z < size.getZ(); z++) {

                    final BlockPos pos = origin.offset(x, y, z);

                    // Unassembled parts are independent world blocks, not cleanup debris.
                    if (!level.getBlockState(pos).isAir()) continue;
                    level.setBlock(pos, fluidState, Block.UPDATE_ALL);
                }
            }
        }
    }

    private record FluidRestoreData(ResourceLocation fluidId, Fluid fluid) {}

    private static void cleanupWorldBlockEntities(
            final ServerLevel level, final BlockPos origin, final BlockPos size) {

        for (BlockPos pos : blockEntityPositions(level, origin, size)) {
            // Preserve real blocks outside the assembled/glued subset.
            if (!level.getBlockState(pos).hasBlockEntity()) level.removeBlockEntity(pos);
        }
    }

    private static Set<ServerSubLevel> getSubLevels(final ServerLevel level) {

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);

        if (container == null) {
            return java.util.Collections.emptySet();
        }

        final Set<ServerSubLevel> result =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (final Object object : container.getAllSubLevels()) {

            if (object instanceof ServerSubLevel subLevel) {
                result.add(subLevel);
            }
        }

        return result;
    }

    private static List<BlockPos> blockEntityPositions(
            ServerLevel level, BlockPos origin, BlockPos size) {
        var result = new ArrayList<BlockPos>();
        BlockPos end = origin.offset(size);
        for (int x = origin.getX() >> 4; x <= (end.getX() - 1) >> 4; x++)
            for (int z = origin.getZ() >> 4; z <= (end.getZ() - 1) >> 4; z++)
                for (BlockPos pos : level.getChunk(x, z).getBlockEntitiesPos())
                    if (pos.getX() >= origin.getX()
                            && pos.getX() < end.getX()
                            && pos.getY() >= origin.getY()
                            && pos.getY() < end.getY()
                            && pos.getZ() >= origin.getZ()
                            && pos.getZ() < end.getZ()) result.add(pos.immutable());
        return result;
    }

    private static void clearArea(
            final ServerLevel level, final BlockPos origin, final BlockPos size) {

        cleanupWorldBlockEntities(level, origin, size);
        for (int x = 0; x < size.getX(); x++) {

            for (int y = 0; y < size.getY(); y++) {

                for (int z = 0; z < size.getZ(); z++) {

                    final BlockPos pos = origin.offset(x, y, z);

                    if (level.getBlockState(pos).isAir()) continue;
                    level.removeBlockEntity(pos);

                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
