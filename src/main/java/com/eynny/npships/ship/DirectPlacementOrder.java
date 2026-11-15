package com.eynny.npships.ship;

import com.eynny.npships.authoring.patches.DetachedPatchAssembly.PlacementRequirements;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Bounded dependency ordering with face and native edge-connected ready frontiers. The accepted
 * body membership remains authoritative; separate logical/glue-linked components are never
 * repartitioned here. A native two-cell peer is the only indivisible placement unit.
 */
final class DirectPlacementOrder {
    private static final Direction[] NEIGHBOURS = Direction.values();
    // Installed Sable SubLevelHeatMapManager.DIRECTION_OFFSETS: faces plus twelve edges;
    // corner-only contact is not native heat-map connectivity.
    private static final int[][] EDGE_NEIGHBOURS = {
        {-1, -1, 0}, {-1, 1, 0}, {1, -1, 0}, {1, 1, 0},
        {-1, 0, -1}, {-1, 0, 1}, {1, 0, -1}, {1, 0, 1},
        {0, -1, -1}, {0, -1, 1}, {0, 1, -1}, {0, 1, 1}
    };

    private enum Phase {
        READ,
        GROUP,
        EDGES,
        ROOTS,
        SORT,
        DONE
    }

    private static final class Unit {
        final List<BlockPos> cells;
        List<Unit> dependencies;
        List<Unit> waiting;
        int remaining;
        boolean emitted, touchesPlaced, touchesNative, frontierQueued, edgeQueued;

        Unit(List<BlockPos> cells) {
            this.cells = cells;
        }
    }

    private final ToDoubleFunction<BlockPos> mass;
    private final Set<BlockPos> members;
    private final Function<BlockPos, PlacementRequirements> requirements;
    private final LongConsumer reserve;
    private final Map<BlockPos, PlacementRequirements> observed = new LinkedHashMap<>();
    private final Map<BlockPos, Unit> grouped = new HashMap<>();
    private final BlockPos.MutableBlockPos neighbourPosition = new BlockPos.MutableBlockPos();
    private final List<Unit> units = new ArrayList<>();
    private final ArrayDeque<Unit> ready = new ArrayDeque<>();
    private final ArrayDeque<Unit> frontier = new ArrayDeque<>();
    private final ArrayDeque<Unit> edgeFrontier = new ArrayDeque<>();
    private final List<List<BlockPos>> result = new ArrayList<>();
    private Iterator<BlockPos> cursor;
    private Phase phase = Phase.READ;
    private int index;
    private Unit first;
    private BlockPos seed;
    private int disconnectedStarts;

    DirectPlacementOrder(
            DetachedAssemblySource source,
            Set<BlockPos> members,
            Function<BlockPos, PlacementRequirements> requirements,
            LongConsumer reserve) {
        this(
                members,
                requirements,
                p -> PhysicsBlockPropertyHelper.getMass(source, p, source.getBlockState(p)),
                reserve);
    }

    /** The solver owns only graph data; the caller supplies the admitted native mass query. */
    DirectPlacementOrder(
            Set<BlockPos> members,
            Function<BlockPos, PlacementRequirements> requirements,
            ToDoubleFunction<BlockPos> mass,
            LongConsumer reserve) {
        this.mass = mass;
        this.members = members;
        this.requirements = requirements;
        this.reserve = reserve;
        cursor = members.iterator();
    }

    boolean step() {
        switch (phase) {
            case READ -> {
                if (cursor.hasNext()) {
                    var p = cursor.next();
                    reserve.accept(24L * Long.BYTES);
                    var requirement = requirements.apply(p);
                    for (var support : requirement.supports()) adjacent(p, support);
                    for (var peer : requirement.peers()) adjacent(p, peer);
                    if (!requirement.supports().isEmpty() || !requirement.peers().isEmpty())
                        observed.put(p, requirement);
                } else {
                    cursor = members.iterator();
                    phase = Phase.GROUP;
                }
            }
            case GROUP -> {
                if (!cursor.hasNext()) {
                    phase = Phase.EDGES;
                    return false;
                }
                var p = cursor.next();
                if (grouped.containsKey(p)) return false;
                var peers = observed.getOrDefault(p, PlacementRequirements.NONE).peers();
                if (peers.size() > 1)
                    throw new IllegalArgumentException("Native placement peer is not a pair: " + p);
                BlockPos peer = peers.isEmpty() ? null : peers.iterator().next();
                if (peer != null) {
                    var reverse = observed.get(peer);
                    if (reverse == null
                            || !reverse.peers().equals(Set.of(p))
                            || grouped.containsKey(peer))
                        throw new IllegalArgumentException(
                                "Native peer crosses independent bodies or is inconsistent: " + p);
                }
                reserve.accept(24L * Long.BYTES);
                List<BlockPos> cells =
                        peer == null
                                ? List.of(p)
                                : p.compareTo(peer) < 0 ? List.of(p, peer) : List.of(peer, p);
                var unit = new Unit(cells);
                units.add(unit);
                for (var cell : cells) grouped.put(cell, unit);
            }
            case EDGES -> {
                if (index == units.size()) {
                    index = 0;
                    phase = Phase.ROOTS;
                    return false;
                }
                var unit = units.get(index++);
                for (var cell : unit.cells)
                    for (var support :
                            observed.getOrDefault(cell, PlacementRequirements.NONE).supports()) {
                        var prerequisite = grouped.get(support);
                        if (prerequisite == null)
                            throw new IllegalArgumentException(
                                    "Required support crosses independent bodies: " + cell);
                        if (prerequisite != unit
                                && (unit.dependencies == null
                                        || !unit.dependencies.contains(prerequisite))) {
                            reserve.accept(3L * Long.BYTES);
                            if (unit.dependencies == null) unit.dependencies = new ArrayList<>();
                            unit.dependencies.add(prerequisite);
                            if (prerequisite.waiting == null)
                                prerequisite.waiting = new ArrayList<>();
                            prerequisite.waiting.add(unit);
                        }
                    }
                unit.remaining = unit.dependencies == null ? 0 : unit.dependencies.size();
            }
            case ROOTS -> {
                if (index == units.size()) {
                    if (first == null)
                        throw new IllegalArgumentException(
                                "Native body has no independent positive-mass seed");
                    phase = Phase.SORT;
                    return false;
                }
                var unit = units.get(index++);
                if (unit.remaining != 0) return false;
                if (first == null)
                    for (var p : unit.cells) {
                        double mass = this.mass.applyAsDouble(p);
                        if (mass > 0 && Double.isFinite(mass)) {
                            first = unit;
                            seed = p;
                            break;
                        }
                    }
                if (unit != first) ready.addLast(unit);
            }
            case SORT -> {
                Unit unit;
                if (result.isEmpty()) unit = first;
                else {
                    unit = frontier.pollFirst();
                    if (unit == null) unit = edgeFrontier.pollFirst();
                    if (unit == null) unit = ready.pollFirst();
                    if (unit == null) {
                        if (result.size() != units.size())
                            throw new IllegalArgumentException(
                                    "Cyclic native block support requirements");
                        phase = Phase.DONE;
                        return true;
                    }
                    // Entries emitted from the frontier remain in the fallback queue. Retire only
                    // one stale entry per cursor step; a large body never drains a queue at once.
                    if (unit.emitted) return false;
                    if (!unit.touchesNative) disconnectedStarts++;
                }
                unit.emitted = true;
                result.add(unit.cells);
                // Prefer a growing native face-connected prefix while respecting prerequisites.
                // At most two cells and eighteen native neighbours each; never scan ready units.
                for (var cell : unit.cells)
                    for (var direction : NEIGHBOURS) {
                        var neighbour =
                                grouped.get(neighbourPosition.setWithOffset(cell, direction));
                        if (neighbour == null || neighbour == unit || neighbour.emitted) continue;
                        neighbour.touchesPlaced = true;
                        neighbour.touchesNative = true;
                        enqueueFrontier(neighbour);
                    }
                for (var cell : unit.cells)
                    for (var edge : EDGE_NEIGHBOURS) {
                        var neighbour =
                                grouped.get(
                                        neighbourPosition.set(
                                                cell.getX() + edge[0],
                                                cell.getY() + edge[1],
                                                cell.getZ() + edge[2]));
                        if (neighbour == null || neighbour == unit || neighbour.emitted) continue;
                        neighbour.touchesNative = true;
                        enqueueFrontier(neighbour);
                    }
                // Supports are adjacent, so a paired unit has at most twelve waiting neighbours.
                if (unit.waiting != null)
                    for (var dependent : unit.waiting)
                        if (--dependent.remaining == 0) {
                            ready.addLast(dependent);
                            enqueueFrontier(dependent);
                        }
                // If no eligible frontier exists, the next step may start another ready component.
                // Native logical/non-face links and support barriers must not create a false cycle.
            }
            case DONE -> {
                return true;
            }
        }
        return false;
    }

    private void enqueueFrontier(Unit unit) {
        if (unit.remaining == 0 && unit.touchesPlaced && !unit.frontierQueued && !unit.emitted) {
            reserve.accept(Long.BYTES);
            unit.frontierQueued = true;
            frontier.addLast(unit);
        } else if (unit.remaining == 0 && unit.touchesNative && !unit.edgeQueued && !unit.emitted) {
            reserve.accept(Long.BYTES);
            unit.edgeQueued = true;
            edgeFrontier.addLast(unit);
        }
    }

    int disconnectedStarts() {
        requireDone();
        return disconnectedStarts;
    }

    private static void adjacent(BlockPos from, BlockPos to) {
        if (Math.abs((long) from.getX() - to.getX())
                        + Math.abs((long) from.getY() - to.getY())
                        + Math.abs((long) from.getZ() - to.getZ())
                != 1)
            throw new IllegalArgumentException(
                    "Placement support is not an adjacent cell: " + from);
    }

    BlockPos seed() {
        requireDone();
        return seed;
    }

    List<List<BlockPos>> result() {
        requireDone();
        return Collections.unmodifiableList(result);
    }

    private void requireDone() {
        if (phase != Phase.DONE)
            throw new IllegalStateException("Placement ordering is incomplete");
    }
}
