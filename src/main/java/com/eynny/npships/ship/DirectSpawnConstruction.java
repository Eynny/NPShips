package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.mixin.NPShipsSubLevelAllocationAccessor;

import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;
import java.util.function.BooleanSupplier;

/** A continuation owned by the existing spawn attempt. It never writes ordinary world cells. */
final class DirectSpawnConstruction implements AutoCloseable, DirectSpawnOwnership.Owner {
    private enum Phase {
        PREPARE,
        INPUT,
        PARTITION,
        PLAN,
        PREPARE_NATIVE,
        GUARD,
        ALLOCATE,
        TOPOLOGY,
        FILL,
        NOTIFY,
        RETIRE_FLUID,
        ENTITIES,
        PATCHES,
        VERIFY_PHYSICAL,
        REGISTER,
        RELEASE,
        COMPLETE
    }

    private static final class Body {
        final DirectAssemblyPartition.Body plan;
        List<List<BlockPos>> order;
        DirectPlacementOrder ordering;
        final UUID id;
        BlockPos seed, offset;
        ServerSubLevel nativeBody;
        FixedConstraintHandle hold;
        boolean ticket;
        int written;

        Body(DirectAssemblyPartition.Body plan) {
            this(plan, UUID.randomUUID());
        }

        Body(DirectAssemblyPartition.Body plan, UUID id) {
            this.plan = plan;
            this.id = id;
        }
    }

    /**
     * Planned once: filling and native transfer mutate this entry without another growing index.
     */
    private static final class Cell {
        Body body;
        boolean written;

        Cell(Body body) {
            this.body = body;
        }
    }

    private final ServerLevel level;
    private final PatchedTemplate template;
    private final ResourceLocation structure;
    private final BlockPos origin, size;
    private final double yaw;
    private final com.eynny.npships.config.NPShipsStructureConfig config;
    private SpawnRegionGuard region;
    private final Quaterniond orientation;
    private final Vec3 pivot;
    private final UUID attempt = UUID.randomUUID();
    private final DirectSpawnLedger ledger;
    private final DirectFluidProvenance fluid;
    private List<Body> bodies = new ArrayList<>();
    private Map<UUID, Body> allocated = new LinkedHashMap<>();
    private List<Body> allocations = new ArrayList<>();
    private Set<UUID> reservations = new LinkedHashSet<>();
    private Map<BlockPos, Cell> owners = new HashMap<>();
    private Map<Long, ServerSubLevel> chunkOwners = new HashMap<>();
    private Set<UUID> physical = new LinkedHashSet<>();
    private List<Entity> entities = new ArrayList<>();
    private List<UUID> mobs = new ArrayList<>();
    private DirectSpawnInput input;
    private List<BlockPos> pendingPlacement;
    private int payloadIndex;
    private Map<BlockPos, CompoundTag> placementPayloads = new HashMap<>();
    private com.eynny.npships.resources.NbtWorkCursor payloadCopy;
    private DirectAssemblyPartition partition;
    private DirectEntityPlacement entityCursor;
    private DirectEntityPlacement.Ready readyEntity;
    private Iterator<Entity> entityEnrollment;
    private Iterator<UUID> physicalValidation;
    private Iterator<BlockPos> notificationCells;
    private boolean entityInserted, entityCursorComplete;
    private Phase phase = Phase.PREPARE;
    private int bodyIndex, entityIndex, finalIndex;
    private Iterator<BlockPos> planningMembers;
    private Body planningBody;
    private long progress;
    private Ship result;
    private boolean closed, cleaning;
    private int cleanupPhase = -2, cleanupIndex;
    private UUID cancellationBody;
    private Iterator<UUID> cleanupReservations;
    private Exception cleanupFailure;
    private IllegalStateException nativeTickFailure;
    private List<UUID> physicalOrder = new ArrayList<>();

    DirectSpawnConstruction(
            ServerLevel level,
            PatchedTemplate template,
            ResourceLocation structure,
            BlockPos origin,
            double yaw,
            com.eynny.npships.config.NPShipsStructureConfig config) {
        if (!level.getServer().isSameThread() || !Double.isFinite(yaw))
            throw new IllegalArgumentException(
                    "Direct construction requires a finite rotation on the server thread");
        this.level = level;
        this.template = template;
        this.structure = structure;
        this.origin = origin.immutable();
        this.yaw = yaw;
        this.config = Objects.requireNonNull(config);
        var extent = template.getSize();
        this.size = new BlockPos(extent.getX(), extent.getY(), extent.getZ());
        this.orientation = new Quaterniond().rotateY(yaw);
        this.pivot = com.eynny.npships.naturalspawn.NaturalSpawnRotationCheck.pivot(origin, size);
        this.ledger = new DirectSpawnLedger(level);
        this.fluid = new DirectFluidProvenance(level, template::reserveDirectCapacity);
    }

    boolean step(BooleanSupplier canContinue) throws Exception {
        requireOpen();
        fluid.check();
        // A nested cursor consumes the grant already obtained by this owner before asking for more.
        // Otherwise a valid one-unit allowance is exhausted by wrappers without advancing any data.
        boolean[] granted = {false};
        BooleanSupplier nested =
                () -> {
                    if (granted[0]) {
                        granted[0] = false;
                        return true;
                    }
                    return canContinue.getAsBoolean();
                };
        while (phase != Phase.COMPLETE) {
            if (region != null && phase != Phase.GUARD) {
                long before = region.operations();
                try {
                    if (!region.ready() && !region.step(canContinue)) return false;
                    region.requireReady();
                } finally {
                    progress += region.operations() - before;
                }
            }
            if (!canContinue.getAsBoolean()) return false;
            granted[0] = true;
            int previousAllocations = allocations.size();
            try (var ownership = DirectSpawnOwnership.open(level, this)) {
                unit(nested);
                for (int i = previousAllocations; i < allocations.size(); i++) {
                    var child = allocations.get(i);
                    if (child.plan == null && child.hold == null) holdChild(child);
                }
            }
            progress++;
        }
        return phase == Phase.COMPLETE;
    }

    long progress() {
        return progress;
    }

    boolean finished() {
        return phase == Phase.COMPLETE;
    }

    Set<UUID> ownedPhysicalIds() {
        return Collections.unmodifiableSet(physical);
    }

    Ship result() {
        if (!finished()) throw new IllegalStateException("Construction has not completed");
        return result;
    }

    @Override
    public boolean owns(UUID id) {
        return physical.contains(id);
    }

    @Override
    public void reserve(UUID id) {
        if (region == null)
            throw new IllegalStateException("Allocation preceded region validation");
        region.own(id);
        DirectSpawnJournal.reserve(level, attempt, id);
        reservations.add(id);
    }

    @Override
    public void allocated(ServerSubLevel body) {
        UUID id = body.getUniqueId();
        if (!reservations.contains(id))
            throw new IllegalStateException("Allocation was not reserved");
        ledger.allocated(body);
        if (physical.add(id)) physicalOrder.add(id);
        var owned = allocated.computeIfAbsent(id, key -> new Body(null, key));
        if (owned.nativeBody != null && owned.nativeBody != body)
            throw new IllegalStateException("Allocation replaced an owned native object");
        owned.nativeBody = body;
        allocations.add(owned);
    }

    @Override
    public DirectFluidProvenance fluidProvenance() {
        return fluid;
    }

    @Override
    public boolean cancelling() {
        return cleaning || closed;
    }

    @Override
    public AutoCloseable nativeTickContext() {
        return PatchedTemplate.observe(template);
    }

    @Override
    public boolean mapsNativeCells() {
        return true;
    }

    @Override
    public boolean awaitingNativeCell(ServerSubLevel source, BlockPos position) {
        var body = allocated.get(source.getUniqueId());
        if (body == null || body.nativeBody != source || body.offset == null) return false;
        var cell = owners.get(position.subtract(body.offset));
        return cell != null && cell.body == body && !cell.written;
    }

    @Override
    public void nativeCellTransferred(
            ServerSubLevel source, BlockPos from, ServerSubLevel target, BlockPos to) {
        var old = allocated.get(source.getUniqueId());
        var next = allocated.get(target.getUniqueId());
        if (old == null
                || old.nativeBody != source
                || old.offset == null
                || next == null
                || next.nativeBody != target)
            throw new IllegalStateException("Native transfer lost its exact body mapping");
        var local = from.subtract(old.offset);
        var cell = owners.get(local);
        if (cell == null || cell.body != old || !cell.written) return;
        var offset = to.subtract(local);
        if (next.offset != null && !next.offset.equals(offset))
            throw new IllegalStateException(
                    "Native transfer requires nonuniform authored coordinates");
        if (next.offset == null) {
            template.reserveDirectCapacity(8L * Long.BYTES);
            next.offset = offset;
        }
        cell.body = next;
        var chunkKey = new ChunkPos(to).toLong();
        if (!chunkOwners.containsKey(chunkKey)) template.reserveDirectCapacity(8L * Long.BYTES);
        chunkOwners.put(chunkKey, target);
        template.directCellPlaced(local, to, target);
        if (phase == Phase.ALLOCATE || phase == Phase.TOPOLOGY || phase == Phase.FILL)
            fluid.authored(target, to, level.getBlockState(to));
    }

    @Override
    public void nativeCallbackChildReady(ServerSubLevel child) {
        var record = allocated.get(child.getUniqueId());
        if (record == null || record.nativeBody != child || record.plan != null)
            throw new IllegalStateException(
                    "Native provider child lost exact construction ownership");
        if (record.hold == null) holdChild(record);
        PatchedTemplate.trackPhysicalBody(level, child.getUniqueId());
    }

    @Override
    public void nativeTickChildReady(ServerSubLevel child) {
        try {
            var record = allocated.get(child.getUniqueId());
            if (record == null || record.nativeBody != child || record.plan != null)
                throw new IllegalStateException(
                        "Native split child lost exact construction ownership");
            if (record.hold == null) holdChild(record);
        } catch (Throwable failure) {
            nativeTickFailed(failure);
        }
        nativeTickFailed(
                new IllegalStateException(
                        "Unscheduled native split invalidated pending construction coordinates"));
    }

    @Override
    public void nativeTickFailed(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (nativeTickFailure == null)
            nativeTickFailure =
                    new IllegalStateException(
                            "Unscheduled native split requires owned construction cleanup",
                            failure);
        else if (nativeTickFailure.getSuppressed().length < 4 && failure != nativeTickFailure)
            nativeTickFailure.addSuppressed(failure);
    }

    private void holdChild(Body child) {
        var body = child.nativeBody;
        if (!NativeBlockAccess.current(level, body))
            throw new IllegalStateException("Native child disappeared");
        body.updateMergedMassData(1);
        if (body.getMassTracker().getMass() <= 0)
            throw new IllegalStateException("Native child has no physical mass");
        var point = new Vector3d(body.logicalPose().rotationPoint());
        var world = body.logicalPose().transformPosition(new Vector3d(point));
        var container = SubLevelContainer.getContainer(level);
        child.hold =
                container
                        .physicsSystem()
                        .getPipeline()
                        .addConstraint(
                                null,
                                body,
                                new FixedConstraintConfiguration(
                                        new Vector3d(world),
                                        point,
                                        new Quaterniond(body.logicalPose().orientation())));
        if (child.hold == null || !child.hold.isValid())
            throw new IllegalStateException("Native child hold refused");
        child.ticket = container.addForceLoadTicket(body, DirectSpawnJournal.BUILD_TICKET, attempt);
        if (!child.ticket) throw new IllegalStateException("Native child loading ticket refused");
    }

    private void unit(BooleanSupplier canContinue) throws Exception {
        switch (phase) {
            case PREPARE -> {
                if (!template.prepareStep(level, origin, canContinue)) return;
                input = new DirectSpawnInput(level, template, size);
                phase = Phase.INPUT;
            }
            case INPUT -> {
                if (!input.step()) return;
                partition =
                        new DirectAssemblyPartition(
                                input.source,
                                input.membership,
                                template.assemblyPolicy(),
                                template::reserveDirectCapacity);
                phase = Phase.PARTITION;
            }
            case PARTITION -> {
                if (!partition.step()) return;
                var container = SubLevelContainer.getContainer(level);
                long available =
                        (1L << (2 * container.getLogSideLength()))
                                - container.getOccupancy().cardinality();
                if (partition.bodies().size() > available)
                    throw new IllegalArgumentException(
                            "Too many separate physical bodies for the available Sable plots");
                phase = Phase.PLAN;
            }
            case PLAN -> planOne();
            case PREPARE_NATIVE -> {
                if (template.prepareDirectPlacementStep()) phase = Phase.GUARD;
            }
            case GUARD -> {
                if (region == null)
                    region =
                            SpawnRegionGuard.open(
                                    level,
                                    config,
                                    origin,
                                    size,
                                    yaw,
                                    template::reserveDirectCapacity);
                int[] portion = {0};
                if (region.step(() -> portion[0]++ == 0)) phase = Phase.ALLOCATE;
            }
            case ALLOCATE -> {
                if (bodyIndex == bodies.size()) {
                    bodyIndex = 0;
                    phase = Phase.TOPOLOGY;
                    return;
                }
                var body = bodies.get(bodyIndex);
                if (!preparePayloads(body.order.getFirst(), canContinue)) return;
                allocate(body);
                finishPayloads();
                bodyIndex++;
            }
            case TOPOLOGY -> {
                if (!entityOne(true)) return;
                entityIndex = 0;
                phase = Phase.FILL;
            }
            case FILL -> {
                if (bodyIndex == bodies.size()) {
                    notificationCells = owners.keySet().iterator();
                    phase = Phase.NOTIFY;
                    return;
                }
                var body = bodies.get(bodyIndex);
                if (body.written >= body.order.size()) {
                    // Native raycasts need the completed body's world bounds in this tick.
                    ShipRotation.refreshBounds(body.nativeBody);
                    bodyIndex++;
                    return;
                }
                var unit = body.order.get(body.written);
                if (!unit.contains(body.seed)) {
                    if (!preparePayloads(unit, canContinue)) return;
                    for (var local : unit) write(body, local);
                    finishPayloads();
                }
                body.written++;
            }
            case NOTIFY -> {
                if (notificationCells.hasNext()) {
                    notifyPlaced(notificationCells.next());
                    return;
                }
                notificationCells = null;
                phase = Phase.RETIRE_FLUID;
            }
            case RETIRE_FLUID -> {
                if (!retireFluid(canContinue)) return;
                phase = Phase.ENTITIES;
            }
            case ENTITIES -> {
                if (!entityOne(false)) return;
                phase = Phase.PATCHES;
            }
            case PATCHES -> {
                if (!template.finishDirectStep(mobs)) return;
                physicalValidation = template.directPhysicalIds().iterator();
                phase = Phase.VERIFY_PHYSICAL;
            }
            case VERIFY_PHYSICAL -> {
                if (physicalValidation.hasNext()) {
                    if (!physical.contains(physicalValidation.next()))
                        throw new IllegalStateException("Provider returned an unowned native body");
                    return;
                }
                physicalValidation = null;
                phase = Phase.REGISTER;
            }
            case REGISTER -> {
                if (finalIndex < allocations.size()) {
                    var body = allocations.get(finalIndex++).nativeBody;
                    ShipSpawner.registerBody(level, template, body);
                    com.eynny.npships.lifecycle.NPShipsSavedData.get(level)
                            .source(body.getUniqueId(), structure.toString());
                    return;
                }
                finalIndex = 0;
                phase = Phase.RELEASE;
            }
            case RELEASE -> {
                if (finalIndex < allocations.size()) {
                    var body = allocations.get(finalIndex++);
                    if (body.hold != null && body.hold.isValid()) body.hold.remove();
                    releaseTicket(body);
                    DirectSpawnJournal.markReady(level, attempt, body.id);
                    return;
                }
                if (physical.isEmpty())
                    throw new IllegalArgumentException("Schematic contains no physical blocks");
                // Both lists are frozen by the terminal phase; external callers receive read-only
                // views.
                var completed =
                        Ship.completedOwned(
                                physicalOrder.getFirst(), structure, origin, physicalOrder, mobs);
                DirectSpawnJournal.publish(level, attempt);
                ledger.commit();
                result = completed;
                phase = Phase.COMPLETE;
            }
            case COMPLETE -> {}
        }
    }

    private void planOne() throws Exception {
        if (planningBody == null) {
            if (bodyIndex >= partition.bodies().size()) {
                bodyIndex = 0;
                template.beginDirectPlacement(this::resolve, input.source);
                com.eynny.npships.integration.events.NPShipsEventBridge.beforePlacement(
                        level, structure, origin);
                phase = Phase.PREPARE_NATIVE;
                return;
            }
            planningBody = new Body(partition.bodies().get(bodyIndex++));
            bodies.add(planningBody);
            planningMembers = planningBody.plan.membersLocal().iterator();
        }
        if (planningMembers != null) {
            if (planningMembers.hasNext()) {
                var local = planningMembers.next();
                var state = input.source.getBlockState(local);
                // Entry/key/table allowance plus the mutable body/written cell. Reserve before
                // retaining it; FILL and native transfer only mutate fields already admitted here.
                template.reserveDirectCapacity(16L * Long.BYTES);
                owners.put(local, new Cell(planningBody));
                return;
            }
            planningMembers = null;
            planningBody.ordering =
                    new DirectPlacementOrder(
                            input.source,
                            planningBody.plan.membersLocal(),
                            partition::placementRequirements,
                            template::reserveDirectCapacity);
        }
        if (!planningBody.ordering.step()) return;
        planningBody.order = planningBody.ordering.result();
        planningBody.seed = planningBody.ordering.seed();
        planningBody.ordering = null;
        planningBody = null;
    }

    private Vector3d worldPoint(double x, double y, double z) {
        return ShipRotation.rotatePosition(
                new Vector3d(origin.getX() + x, origin.getY() + y, origin.getZ() + z), pivot, yaw);
    }

    private BlockPos resolve(BlockPos local) {
        var cell = owners.get(local);
        return cell == null || cell.body.offset == null ? null : local.offset(cell.body.offset);
    }

    private void allocate(Body body) throws Exception {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing native body container");
        var slot = ((NPShipsSubLevelAllocationAccessor) container).npships$firstEmptyPlot();
        if (slot == null) throw new IllegalStateException("No free native plot");
        allocated.put(body.id, body);
        var pose = new Pose3d();
        pose.position()
                .set(
                        worldPoint(
                                body.seed.getX() + .5,
                                body.seed.getY() + .5,
                                body.seed.getZ() + .5));
        pose.orientation().set(orientation);
        try (var allocation = DirectSpawnOwnership.root(body.id)) {
            observe(com.eynny.npships.resources.ResourceWorkMeter.Family.DIRECT_BODY_ALLOCATION);
            var created =
                    (ServerSubLevel) container.allocateSubLevel(body.id, slot.x, slot.y, pose);
            if (body.nativeBody != created)
                throw new IllegalStateException("Native allocation ownership hook did not run");
        }
        var center = body.nativeBody.getPlot().getCenterBlock();
        // Center vertically in the plot instead of tying available height to the arbitrary seed Y.
        body.offset =
                new BlockPos(
                        center.getX() - size.getX() / 2,
                        level.getMinBuildHeight()
                                + Math.max(0, (level.getHeight() - size.getY()) / 2),
                        center.getZ() - size.getZ() / 2);
        // The native peer is completed in the same unit; it may not survive a tick alone.
        write(body, body.seed);
        for (var peer : body.order.getFirst()) if (!peer.equals(body.seed)) write(body, peer);
        body.nativeBody.updateMergedMassData(1);
        var mass = body.nativeBody.getMassTracker();
        if (!Double.isFinite(mass.getMass()) || mass.getMass() <= 0)
            throw new IllegalStateException("Direct seed has no valid native mass");
        var com = mass.getCenterOfMass();
        var position =
                worldPoint(
                        com.x() - body.offset.getX(),
                        com.y() - body.offset.getY(),
                        com.z() - body.offset.getZ());
        body.nativeBody.logicalPose().rotationPoint().set(com);
        container.physicsSystem().getPipeline().teleport(body.nativeBody, position, orientation);
        body.nativeBody.logicalPose().position().set(position);
        body.nativeBody.updateLastPose();
        ShipRotation.refreshBounds(body.nativeBody);
        var plotSeed = body.seed.offset(body.offset);
        body.hold =
                container
                        .physicsSystem()
                        .getPipeline()
                        .addConstraint(
                                null,
                                body.nativeBody,
                                new FixedConstraintConfiguration(
                                        worldPoint(
                                                body.seed.getX() + .5,
                                                body.seed.getY() + .5,
                                                body.seed.getZ() + .5),
                                        new Vector3d(
                                                plotSeed.getX() + .5,
                                                plotSeed.getY() + .5,
                                                plotSeed.getZ() + .5),
                                        new Quaterniond(orientation)));
        if (body.hold == null || !body.hold.isValid())
            throw new IllegalStateException("Native construction hold refused");
        body.ticket =
                container.addForceLoadTicket(
                        body.nativeBody, DirectSpawnJournal.BUILD_TICKET, attempt);
        if (!body.ticket)
            throw new IllegalStateException("Native construction loading ticket refused");
    }

    /** Prepare detached payloads before a native placement unit, including the first seed. */
    private boolean preparePayloads(List<BlockPos> members, BooleanSupplier canContinue) {
        if (pendingPlacement == null) pendingPlacement = members;
        if (pendingPlacement != members)
            throw new IllegalStateException("Placement unit changed during payload copy");
        if (payloadIndex == members.size()) return true;
        var local = members.get(payloadIndex);
        var source = input.source.payload(local);
        if (source == null) {
            payloadIndex++;
            return false;
        }
        if (payloadCopy == null)
            payloadCopy =
                    com.eynny.npships.resources.NbtWorkCursor.copy(
                            source, template::reserveDirectCapacity);
        int[] portion = {128};
        if (!payloadCopy.step(() -> portion[0]-- > 0 && canContinue.getAsBoolean())) return false;
        placementPayloads.put(local, (CompoundTag) payloadCopy.copyResult());
        payloadCopy.close();
        payloadCopy = null;
        payloadIndex++;
        return false;
    }

    private void finishPayloads() {
        pendingPlacement = null;
        payloadIndex = 0;
        placementPayloads.clear();
    }

    private void observe(com.eynny.npships.resources.ResourceWorkMeter.Family family) {
        var work = template.work();
        if (work != null) work.record(family, 1);
    }

    private void write(Body body, BlockPos local) throws Exception {
        var cell = owners.get(local);
        if (cell == null) throw new IllegalStateException("Unplanned authored cell: " + local);
        body = cell.body;
        var nativeBody = body.nativeBody;
        if (!NativeBlockAccess.current(level, nativeBody))
            throw new IllegalStateException("Construction body disappeared");
        var target = local.offset(body.offset);
        prepareNativeChunk(nativeBody, target);
        try (var reads = NativeBlockAccess.openTransfer(level, chunkOwners::get, null);
                var entityScope = template.beginNativePlacement()) {
            var previous = level.getBlockState(target);
            if (!previous.isAir() && !fluid.mayReplace(nativeBody, target, previous))
                throw new IllegalStateException("Unfilled construction cell was changed: " + local);
            var state = input.source.getBlockState(local);
            var data = placementPayloads.get(local);
            if (data != null) template.prepareInitialBlock(local, data);
            template.directCellPlaced(local, target, nativeBody);
            observe(com.eynny.npships.resources.ResourceWorkMeter.Family.DIRECT_BLOCK_WRITE);
            var chunk =
                    NativeBlockAccess.chunk(
                            level, nativeBody, target.getX() >> 4, target.getZ() >> 4);
            boolean previousCapture = level.captureBlockSnapshots;
            dev.ryanhcode.sable.platform.SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(
                    level, true);
            try {
                chunk.setBlockState(target, state, true);
            } finally {
                dev.ryanhcode.sable.platform.SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(
                        level, previousCapture);
            }
            if (!chunk.getBlockState(target).equals(state))
                throw new IllegalStateException("Native cell placement refused: " + local);
            cell.written = true;
            var be = level.getBlockEntity(target);
            if (be != null) {
                if (data != null) {
                    be.loadWithComponents(data, level.registryAccess());
                    be.setChanged();
                }
                template.initialBlockPlaced(local, be);
            } else if (state.hasBlockEntity())
                throw new IllegalStateException("Native block entity missing: " + local);
            fluid.authored(nativeBody, target, state);
        }
    }

    private void notifyPlaced(BlockPos local) throws Exception {
        var cell = owners.get(local);
        var body = cell.body.nativeBody;
        if (!NativeBlockAccess.current(level, body))
            throw new IllegalStateException("Construction body disappeared before updates");
        var target = local.offset(cell.body.offset);
        try (var reads = NativeBlockAccess.openTransfer(level, chunkOwners::get, null);
                var entityScope = template.beginNativePlacement()) {
            var chunk =
                    NativeBlockAccess.chunk(level, body, target.getX() >> 4, target.getZ() >> 4);
            var state = chunk.getBlockState(target);
            state.onPlace(
                    level,
                    target,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    false);
            state = chunk.getBlockState(target);
            dev.ryanhcode.sable.api.SubLevelAssemblyHelper.markAndNotifyBlock(
                    level,
                    target,
                    chunk,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    state,
                    Block.UPDATE_ALL,
                    512);
            level.neighborChanged(target, state.getBlock(), target);
        }
    }

    private void prepareNativeChunk(ServerSubLevel nativeBody, BlockPos target) {
        if (!NativeBlockAccess.current(level, nativeBody))
            throw new IllegalStateException("Construction body disappeared");
        var chunk = new ChunkPos(target);
        var plot = nativeBody.getPlot();
        if (!plot.contains(chunk) || level.isOutsideBuildHeight(target))
            throw new IllegalArgumentException("Schematic exceeds native plot bounds");
        long chunkKey = chunk.toLong();
        if (!chunkOwners.containsKey(chunkKey)) template.reserveDirectCapacity(8L * Long.BYTES);
        if (plot.getChunk(plot.toLocal(chunk)) == null) plot.newEmptyChunk(chunk);
        chunkOwners.put(chunkKey, nativeBody);
    }

    /** Topology precedes the remaining cell placement; actors follow the completed fill. */
    private boolean entityOne(boolean topology) throws Exception {
        if (readyEntity != null) {
            if (entityEnrollment.hasNext()) {
                var entity = entityEnrollment.next();
                if (entityInserted) {
                    if (level.getEntity(entity.getUUID()) != entity)
                        throw new IllegalStateException(
                                "Authored passenger insertion was cancelled");
                } else {
                    var containing =
                            dev.ryanhcode.sable.Sable.HELPER.getContaining(
                                    level, entity.blockPosition());
                    UUID cleanupBody =
                            containing != null && physical.contains(containing.getUniqueId())
                                    ? containing.getUniqueId()
                                    : allocations.getFirst().id;
                    DirectSpawnJournal.reserveEntity(level, attempt, cleanupBody, entity.getUUID());
                    entities.add(entity);
                    if (entity instanceof Mob) {
                        entity.getPersistentData().putBoolean("npships_spawned_mob", true);
                        mobs.add(entity.getUUID());
                    }
                }
                return false;
            }
            if (!entityInserted) {
                if (readyEntity.volume() != null) {
                    var containing =
                            dev.ryanhcode.sable.Sable.HELPER.getContaining(
                                    level, readyEntity.root().blockPosition());
                    var body = containing == null ? null : allocated.get(containing.getUniqueId());
                    if (body == null || body.nativeBody != containing)
                        throw new IllegalStateException(
                                "Native volume lost its exact construction body");
                    prepareNativeChunk(body.nativeBody, readyEntity.root().blockPosition());
                }
                observe(com.eynny.npships.resources.ResourceWorkMeter.Family.DIRECT_ENTITY_INSERT);
                level.addFreshEntityWithPassengers(readyEntity.root());
                entityInserted = true;
                entityEnrollment = readyEntity.root().getSelfAndPassengers().iterator();
                return false;
            }
            readyEntity.admitted();
            readyEntity = null;
            entityEnrollment = null;
            if (entityCursorComplete) {
                entityCursor.close();
                entityCursor = null;
                entityIndex++;
            }
            return false;
        }
        if (entityCursor == null) {
            if (entityIndex == input.entities.size()) return true;
            if (input.nativeVolume(entityIndex) != topology) {
                entityIndex++;
                return false;
            }
            entityCursor =
                    new DirectEntityPlacement(
                            level,
                            input.entities.getCompound(entityIndex),
                            new DirectEntityPlacement.Mapping() {
                                public net.minecraft.world.level.Level source() {
                                    return input.source;
                                }

                                public UUID bodyFor(BlockPos local) {
                                    var cell = owners.get(local);
                                    return cell == null ? null : cell.body.id;
                                }

                                public BlockPos offset(UUID id) {
                                    var body = allocated.get(id);
                                    return body == null ? null : body.offset;
                                }

                                public Iterator<Map.Entry<BlockPos, UUID>> members() {
                                    var cells = owners.entrySet().iterator();
                                    return new Iterator<>() {
                                        public boolean hasNext() {
                                            return cells.hasNext();
                                        }

                                        public Map.Entry<BlockPos, UUID> next() {
                                            var cell = cells.next();
                                            return Map.entry(
                                                    cell.getKey(), cell.getValue().body.id);
                                        }
                                    };
                                }

                                public Vec3 worldPoint(Vec3 local) {
                                    var point =
                                            DirectSpawnConstruction.this.worldPoint(
                                                    local.x, local.y, local.z);
                                    return new Vec3(point.x, point.y, point.z);
                                }

                                public double yaw() {
                                    return yaw;
                                }
                            },
                            template::reserveDirectCapacity,
                            template::beginNativePlacement);
        }
        entityCursorComplete = entityCursor.step();
        readyEntity = entityCursor.takeReady();
        if (readyEntity != null) {
            entityInserted = false;
            entityEnrollment = readyEntity.root().getSelfAndPassengers().iterator();
        } else if (entityCursorComplete) {
            entityCursor.close();
            entityCursor = null;
            entityIndex++;
        }
        return false;
    }

    private void releaseTicket(Body body) {
        if (!body.ticket) return;
        SubLevelContainer.getContainer(level)
                .removeForceLoadTicket(body.nativeBody, DirectSpawnJournal.BUILD_TICKET, attempt);
        body.ticket = false;
    }

    private void requireOpen() {
        if (nativeTickFailure != null) throw nativeTickFailure;
        if (closed || cleaning) throw new IllegalStateException("Direct construction is closed");
    }

    void beginCancellation() {
        if (closed || cleaning) return;
        cleaning = true;
        ledger.beginCleanup();
        cleanupReservations = reservations.iterator();
    }

    boolean cleanupFinished() {
        return closed;
    }

    /** Cancellation stays in the existing attempt queue and consumes its ordinary shared budget. */
    boolean stepCleanup(BooleanSupplier canContinue) throws Exception {
        beginCancellation();
        boolean[] granted = {false};
        BooleanSupplier nested =
                () -> {
                    if (granted[0]) {
                        granted[0] = false;
                        return true;
                    }
                    return canContinue.getAsBoolean();
                };
        while (!closed && canContinue.getAsBoolean()) {
            granted[0] = true;
            progress++;
            switch (cleanupPhase) {
                case -2 -> {
                    if (!retireFluid(nested)) return false;
                    cleanupPhase++;
                }
                case -1 -> {
                    if (finished()) {
                        cleanupReservations = null;
                        cleanupPhase = 0;
                    } else {
                        if (cancellationBody == null && cleanupReservations.hasNext())
                            cancellationBody = cleanupReservations.next();
                        if (cancellationBody == null) {
                            cleanupReservations = null;
                            cleanupPhase = 0;
                        } else if (DirectSpawnJournal.prepareCancellationStep(
                                level, attempt, cancellationBody)) cancellationBody = null;
                    }
                }
                case 0 -> {
                    if (cleanupIndex < entities.size()) {
                        var entity = entities.get(cleanupIndex++);
                        if (!finished())
                            cleanup(
                                    () -> {
                                        if (!entity.isRemoved()
                                                && level.getEntity(entity.getUUID()) == entity)
                                            entity.discard();
                                    });
                    } else {
                        cleanupIndex = 0;
                        cleanupPhase++;
                    }
                }
                case 1 -> {
                    if (cleanupIndex < allocations.size()) {
                        var body = allocations.get(cleanupIndex++);
                        cleanup(
                                () -> {
                                    if (body.hold != null && body.hold.isValid())
                                        body.hold.remove();
                                });
                        cleanup(() -> releaseTicket(body));
                    } else {
                        cleanupIndex = 0;
                        cleanupPhase++;
                    }
                }
                case 2 -> {
                    if (entityCursor != null && !entityCursor.stepCleanup()) return false;
                    entityCursor = null;
                    readyEntity = null;
                    entityEnrollment = null;
                    cleanup(
                            () -> {
                                if (payloadCopy != null) payloadCopy.close();
                            });
                    payloadCopy = null;
                    pendingPlacement = null;
                    placementPayloads = Map.of();
                    cleanupPhase++;
                }
                case 3 -> {
                    if (region != null && !region.stepCleanup(nested)) return false;
                    region = null;
                    cleanupPhase++;
                }
                case 4 -> {
                    // A failed native removal retains exact ownership and is retried by the queue.
                    if (!ledger.stepCleanup(body -> {})) return false;
                    ledger.close();
                    cleanupReservations = reservations.iterator();
                    cleanupPhase++;
                }
                case 5 -> {
                    if (cleanupReservations.hasNext()) {
                        var id = cleanupReservations.next();
                        var body = allocated.get(id);
                        if (finished())
                            cleanup(() -> DirectSpawnJournal.commit(level, attempt, id));
                        else if (SubLevelContainer.getContainer(level).getSubLevel(id) == null)
                            cleanup(() -> DirectSpawnJournal.cancelled(level, attempt, id));
                        else
                            cleanup(
                                    () -> {
                                        throw new IllegalStateException(
                                                "Construction cleanup retained a changed native UUID: "
                                                        + id);
                                    });
                        DirectSpawnOwnership.forget(id, this);
                    } else {
                        cleanupReservations = null;
                        cleanupPhase++;
                    }
                }
                case 6 -> {
                    if (partition != null && !partition.stepCleanup(nested)) return false;
                    partition = null;
                    cleanupPhase++;
                }
                case 7 -> {
                    if (input != null && !input.stepCleanup(nested)) return false;
                    input = null;
                    cleanupPhase++;
                }
                case 8 -> {
                    // No external mutable aliases exist. Detach scratch instead of clearing a large
                    // graph.
                    owners = Map.of();
                    chunkOwners = Map.of();
                    entities = List.of();
                    bodies = List.of();
                    allocated = Map.of();
                    allocations = List.of();
                    reservations = Set.of();
                    physical = Set.of();
                    physicalOrder = List.of();
                    mobs = List.of();
                    planningMembers = null;
                    planningBody = null;
                    physicalValidation = null;
                    cleanup(() -> template.releaseDirectPlanning());
                    closed = true;
                }
                default -> throw new IllegalStateException("Unknown construction cleanup phase");
            }
        }
        if (closed && cleanupFailure != null) {
            var error = cleanupFailure;
            cleanupFailure = null;
            throw error;
        }
        return closed;
    }

    private boolean retireFluid(BooleanSupplier canContinue) {
        long before = fluid.cleanupProgress();
        try {
            return fluid.stepCleanup(canContinue);
        } finally {
            progress += fluid.cleanupProgress() - before;
        }
    }

    private interface Cleanup {
        void run() throws Exception;
    }

    private void cleanup(Cleanup action) {
        try {
            action.run();
        } catch (Exception failure) {
            if (cleanupFailure == null) cleanupFailure = failure;
            else if (cleanupFailure.getSuppressed().length < 16)
                cleanupFailure.addSuppressed(failure);
        }
    }

    /** Explicit synchronous drain for server shutdown and isolated fixtures only. */
    @Override
    public void close() throws Exception {
        while (!stepCleanup(() -> true)) {}
    }
}
