package com.eynny.npships.ship;

import com.eynny.npships.api.patches.PatchTypes;
import com.eynny.npships.authoring.patches.DetachedAssemblyGuard;
import com.eynny.npships.authoring.patches.PatchedTemplate;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.integration.contraptions.PhysicalGroups;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One admitted direct placement. Unsupported data stays on the existing path before mutation; after
 * allocation, failure is terminal and only explicitly owned native objects are removed.
 */
final class DirectAssemblySession implements AutoCloseable {
    private ServerLevel level;
    private PatchedTemplate template;
    private final BlockPos origin;
    private Map<BlockPos, BlockState> blocks;
    private List<DetachedAssemblyPlanner.Component> components;
    private java.util.Set<BlockPos> staticResidue;
    private List<Environment> environments;

    private record Environment(
            BoundingBox3i bounds,
            net.minecraft.world.phys.AABB entityBounds,
            List<BlockPos> worldMembers) {}

    private DirectSpawnLedger ledger;
    private boolean started, closed;

    private DirectAssemblySession(
            ServerLevel level,
            PatchedTemplate template,
            BlockPos origin,
            Map<BlockPos, BlockState> blocks,
            List<DetachedAssemblyPlanner.Component> components,
            java.util.Set<BlockPos> staticResidue,
            List<Environment> environments) {
        this.level = level;
        this.template = template;
        this.origin = origin;
        this.blocks = blocks;
        this.components = components;
        this.staticResidue = staticResidue;
        this.environments = environments;
    }

    static DirectAssemblySession prepare(
            ServerLevel level, PatchedTemplate template, BlockPos origin, BlockPos size)
            throws Exception {
        DirectAssemblySession session = null;
        boolean retained = false;
        try {
            session = plan(level, template, origin, size);
            if (session == null || !session.environmentReady()) return null;
            retained = true;
            return session;
        } catch (PatchedTemplate.DirectAdmissionRefused optionalCapacity) {
            return null;
        } finally {
            if (!retained) {
                if (session != null) session.close();
                else template.releaseDirectPlanning();
            }
        }
    }

    /**
     * This frame and its source view retire before a refused planner returns to native fallback.
     */
    private static DirectAssemblySession plan(
            ServerLevel level, PatchedTemplate template, BlockPos origin, BlockPos size)
            throws Exception {
        var fixedOrigin = origin.immutable();
        var blocks = template.takeDirectAssemblyBlocks();
        if (blocks == null || blocks.isEmpty()) return null;
        var document = template.assemblyDocument();
        try (var source =
                DetachedAssemblySource.open(
                        level, fixedOrigin, size, blocks, template::reserveDirectCapacity)) {
            var result =
                    DetachedAssemblyPlanner.plan(
                            source,
                            document.assemblyPoints(),
                            PhysicalGroups.membership(document, BlockPos.ZERO),
                            template.assemblyPolicy(),
                            template::reserveDirectCapacity);
            if (!(result instanceof DetachedAssemblyPlanner.Ready ready)) return null;
            // Admit chunk/member arrays and construction overlap before publishing any body.
            template.reserveDirectCapacity(
                    Math.addExact(
                            Math.multiplyExact((long) blocks.size(), 4L * Long.BYTES),
                            Math.multiplyExact((long) ready.components().size(), 8L * Long.BYTES)));
            var environments = new ArrayList<Environment>(ready.components().size());
            for (var component : ready.components()) {
                var world = new ArrayList<BlockPos>(component.membersLocal().size());
                for (var local : component.membersLocal())
                    world.add(fixedOrigin.offset(local).immutable());
                var bounds = BoundingBox3i.from(world);
                environments.add(
                        new Environment(bounds, bounds.toAABB().inflate(2), List.copyOf(world)));
            }
            return new DirectAssemblySession(
                    level,
                    template,
                    fixedOrigin,
                    blocks,
                    ready.components(),
                    ready.staticResidueLocal(),
                    List.copyOf(environments));
        }
    }

    /** Recheck after trusted before-placement callbacks, still before clearing or allocation. */
    boolean environmentReady() {
        requireOpen();
        if (started) throw new IllegalStateException("Direct placement already started");
        for (var environment : environments) {
            for (var position : environment.worldMembers()) {
                // Containing-body velocity/rotation belongs to the native transfer path.
                if (Sable.HELPER.getContaining(level, position) != null) return false;
            }
            var bounds = environment.bounds();
            // Native moveOtherStuff considers hanging supports outside the selected cells.
            if (!level.getEntities((Entity) null, environment.entityBounds()).isEmpty())
                return false;
            if (SubLevelTrackingPointSavedData.getOrLoad(level)
                    .getAllTrackingPoints(bounds)
                    .iterator()
                    .hasNext()) return false;
            // Mandatory integration guards remain active when optional restoration is disabled.
            for (var guard : PatchTypes.capabilities(DetachedAssemblyGuard.class).values())
                if (!guard.permits(level, bounds)) return false;
        }
        return true;
    }

    List<UUID> place() throws Exception {
        requireOpen();
        if (started) throw new IllegalStateException("Direct placement is single-use");
        started = true;
        ledger = new DirectSpawnLedger(level);
        var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing native body container");
        try (var placement = template.beginNativePlacement()) {
            // Unmarked plain blocks remain ordinary world cells, as on the transfer path.
            for (var local : staticResidue) {
                var target = origin.offset(local);
                var state = blocks.get(local);
                if (!level.setBlock(target, state, Block.UPDATE_ALL)
                        && !level.getBlockState(target).equals(state))
                    throw new IllegalStateException(
                            "Static direct residue placement refused: " + local);
            }
            template.beforeAssembly(level);
            for (var component : components) {
                var anchor = origin.offset(component.anchorLocal());
                var pose = new Pose3d();
                pose.position().set(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5);
                var body = (ServerSubLevel) container.allocateNewSubLevel(pose);
                ledger.allocated(body);
                var plot = body.getPlot();
                var offset = plot.getCenterBlock().subtract(component.anchorLocal());
                var chunks = new HashSet<ChunkPos>();
                for (var local : component.membersLocal()) {
                    var target = local.offset(offset);
                    var chunk = new ChunkPos(target);
                    if (!plot.contains(chunk) || level.isOutsideBuildHeight(target))
                        throw new IllegalArgumentException(
                                "Direct body exceeds native plot bounds");
                    chunks.add(chunk);
                }
                for (var chunk : chunks) plot.newEmptyChunk(chunk);
                try (var writes =
                        NativeBlockAccess.openTransfer(
                                level,
                                key -> plot.contains(new ChunkPos(key)) ? body : null,
                                null)) {
                    for (var local : component.membersLocal()) {
                        var target = local.offset(offset);
                        var state = blocks.get(local);
                        if (state == null
                                || !level.setBlock(target, state, Block.UPDATE_ALL)
                                        && !level.getBlockState(target).equals(state))
                            throw new IllegalStateException(
                                    "Direct native block placement refused: " + local);
                    }
                    template.directBodyPlaced(
                            component.anchorLocal(), component.membersLocal(), body);
                }
                body.updateMergedMassData(1);
                var mass = body.getMassTracker().getCenterOfMass();
                if (mass == null
                        || !Double.isFinite(body.getMassTracker().getMass())
                        || body.getMassTracker().getMass() <= 0)
                    throw new IllegalStateException("Direct native body has no valid mass");
                // Each body's own COM maps through the same authored origin. Setting every
                // body's position to the marker would collapse independent components together.
                var position =
                        new Vector3d(mass)
                                .add(
                                        origin.getX() - offset.getX(),
                                        origin.getY() - offset.getY(),
                                        origin.getZ() - offset.getZ());
                body.logicalPose().rotationPoint().set(mass);
                container
                        .physicsSystem()
                        .getPipeline()
                        .teleport(body, position, body.logicalPose().orientation());
                body.logicalPose().position().set(position);
                body.updateLastPose();
                body.updateBoundingBox();
                body.forceUpdateGlobalBounds();
            }
        }
        return ledger.ids();
    }

    void commit() {
        requireOpen();
        if (ledger == null) throw new IllegalStateException("No direct native placement");
        ledger.commit();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Direct placement is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var owner = template;
        try {
            if (ledger != null) ledger.close();
        } finally {
            ledger = null;
            blocks = Map.of();
            components = List.of();
            staticResidue = java.util.Set.of();
            environments = List.of();
            level = null;
            template = null;
            owner.releaseDirectPlanning();
        }
    }
}
