package com.eynny.npships.integration.create;

import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.lifecycle.NPShipsSavedData;
import com.eynny.npships.ship.DirectSpawnOwnership;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelSupportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Synchronous native transfer of a tracked board's typed edges, without reloading live state. */
public final class LogisticsNativeTransfer {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private LogisticsNativeTransfer() {}

    public static Scope open(ServerLevel level) {
        return new Scope(level);
    }

    /** Called only after the native full payload has already been serialized. */
    public static void saved(BlockEntity source) {
        var scope = ACTIVE.get();
        if (scope == null) return;
        scope.restore();
        if (!(source instanceof FactoryPanelBlockEntity board) || source.getLevel() != scope.level)
            return;
        var containing = Sable.HELPER.getContaining(source);
        if (!(containing instanceof ServerSubLevel body)
                || !NativeBlockAccess.current(scope.level, body)
                || NativeBlockAccess.at(scope.level, source.getBlockPos()) != source
                || !(DirectSpawnOwnership.pendingHeatMapSplit(body)
                        || NPShipsSavedData.get(scope.level).isTracked(body.getUniqueId()))) return;
        scope.pending = new Move(board);
    }

    /** Native target object is fully loaded; update references before any ordinary tick. */
    public static void loaded(BlockEntity target) {
        var scope = ACTIVE.get();
        if (scope == null || scope.pending == null) return;
        if (!(target instanceof FactoryPanelBlockEntity board) || target.getLevel() != scope.level)
            throw new IllegalStateException(
                    "Native board transfer changed block-entity type or level");
        scope.pending.finish(scope.level, board);
        scope.pending = null;
    }

    public static void failed() {
        var scope = ACTIVE.get();
        if (scope != null) scope.restore();
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private ServerLevel level;
        private Move pending;
        private boolean closed;

        private Scope(ServerLevel level) {
            this.level = level;
            previous = ACTIVE.get();
            ACTIVE.set(this);
        }

        private void restore() {
            if (pending != null) pending.restore();
            pending = null;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                restore();
            } finally {
                level = null;
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        }
    }

    private record Edges(
            FactoryPanelBehaviour old,
            Map<FactoryPanelPosition, FactoryPanelConnection> incoming,
            Map<BlockPos, FactoryPanelConnection> links,
            Set<FactoryPanelPosition> outgoing) {
        Edges(FactoryPanelBehaviour panel) {
            this(panel, panel.targetedBy, panel.targetedByLinks, panel.targeting);
            // Retain these existing collections locally; native removal must not sever the peers.
            panel.targetedBy = new HashMap<>();
            panel.targetedByLinks = new HashMap<>();
            panel.targeting = new HashSet<>();
        }

        void restore() {
            old.targetedBy = incoming;
            old.targetedByLinks = links;
            old.targeting = outgoing;
        }
    }

    private static final class Move {
        final FactoryPanelBlockEntity source;
        final ArrayList<Edges> panels = new ArrayList<>(4);
        final ArrayDeque<Runnable> undo = new ArrayDeque<>();
        final ArrayList<SmartBlockEntity> changed = new ArrayList<>();
        boolean committed;

        Move(FactoryPanelBlockEntity source) {
            this.source = source;
            for (var panel : source.panels.values()) panels.add(new Edges(panel));
        }

        void restore() {
            if (committed) return;
            Throwable failed = null;
            while (!undo.isEmpty()) {
                try {
                    undo.removeLast().run();
                } catch (Throwable error) {
                    if (failed == null) failed = error;
                    else failed.addSuppressed(error);
                }
            }
            for (var edges : panels) edges.restore();
            changed.clear();
            if (failed != null)
                throw new IllegalStateException("Native graph rollback failed", failed);
        }

        void finish(ServerLevel level, FactoryPanelBlockEntity target) {
            try {
                rebind(level, target);
            } catch (Throwable failure) {
                try {
                    restore();
                } catch (Throwable rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
            // The server graph is now complete. Notification errors must never put old source
            // edges back over this valid graph; native source removal must still see empty edges.
            committed = true;
            undo.clear();
            for (var board : changed) board.notifyUpdate();
            target.notifyUpdate();
            changed.clear();
        }

        void rebind(ServerLevel level, FactoryPanelBlockEntity target) {
            // Validate the native slots before touching peers, so failed copies can restore source.
            for (var edges : panels)
                if (!target.panels.containsKey(edges.old.getPanelPosition().slot()))
                    throw new IllegalStateException("Native copied board lost a panel slot");
            for (var edges : panels) {
                var oldPosition = edges.old.getPanelPosition();
                var panel = target.panels.get(oldPosition.slot());
                var newPosition = panel.getPanelPosition();
                selfReferences(panel, source.getBlockPos(), target.getBlockPos(), undo);
                for (var destination : edges.outgoing) {
                    var other =
                            panelAt(
                                    level,
                                    remap(destination, source.getBlockPos(), target.getBlockPos()));
                    if (other == null) continue;
                    var connection = other.targetedBy.get(oldPosition);
                    if (connection != null) {
                        remove(other.targetedBy, oldPosition, undo);
                        relocate(connection, newPosition, undo);
                        put(other.targetedBy, newPosition, connection, undo);
                        changed.add(other.blockEntity);
                    }
                }
                for (var connection : edges.incoming.values()) {
                    var other =
                            panelAt(
                                    level,
                                    remap(
                                            connection.from,
                                            source.getBlockPos(),
                                            target.getBlockPos()));
                    if (other == null) continue;
                    if (other.targeting.contains(oldPosition)) {
                        remove(other.targeting, oldPosition, undo);
                        add(other.targeting, newPosition, undo);
                    }
                    changed.add(other.blockEntity);
                }
                for (var position : edges.links.keySet()) {
                    var support =
                            BlockEntityBehaviour.get(
                                    NativeBlockAccess.at(level, position),
                                    FactoryPanelSupportBehaviour.TYPE);
                    if (support == null) continue;
                    var linked = support.getLinkedPanels();
                    int oldIndex = linked.indexOf(oldPosition);
                    boolean hadNew = linked.contains(newPosition);
                    undo.addLast(
                            () -> {
                                if (!hadNew) linked.remove(newPosition);
                                if (oldIndex >= 0 && !linked.contains(oldPosition))
                                    linked.add(Math.min(oldIndex, linked.size()), oldPosition);
                            });
                    support.disconnect(edges.old);
                    support.connect(panel);
                    changed.add(support.blockEntity);
                }
            }
        }
    }

    private static FactoryPanelBehaviour panelAt(ServerLevel level, FactoryPanelPosition position) {
        var entity = NativeBlockAccess.at(level, position.pos());
        return entity instanceof FactoryPanelBlockEntity board
                ? board.panels.get(position.slot())
                : null;
    }

    private static FactoryPanelPosition remap(
            FactoryPanelPosition position, BlockPos from, BlockPos to) {
        return position.pos().equals(from)
                ? new FactoryPanelPosition(to, position.slot())
                : position;
    }

    private static <K, V> void put(Map<K, V> map, K key, V value, ArrayDeque<Runnable> undo) {
        boolean present = map.containsKey(key);
        var before = map.get(key);
        undo.addLast(
                () -> {
                    if (present) map.put(key, before);
                    else map.remove(key);
                });
        map.put(key, value);
    }

    private static <K, V> void remove(Map<K, V> map, K key, ArrayDeque<Runnable> undo) {
        boolean present = map.containsKey(key);
        var before = map.get(key);
        undo.addLast(
                () -> {
                    if (present) map.put(key, before);
                    else map.remove(key);
                });
        map.remove(key);
    }

    private static <K> void add(Set<K> values, K key, ArrayDeque<Runnable> undo) {
        boolean present = values.contains(key);
        undo.addLast(
                () -> {
                    if (present) values.add(key);
                    else values.remove(key);
                });
        values.add(key);
    }

    private static <K> void remove(Set<K> values, K key, ArrayDeque<Runnable> undo) {
        boolean present = values.contains(key);
        undo.addLast(
                () -> {
                    if (present) values.add(key);
                    else values.remove(key);
                });
        values.remove(key);
    }

    private static void relocate(
            FactoryPanelConnection connection,
            FactoryPanelPosition position,
            ArrayDeque<Runnable> undo) {
        var before = connection.from;
        var cache = connection.cachedSource;
        var path = connection.path;
        undo.addLast(
                () -> {
                    connection.from = before;
                    connection.cachedSource = cache;
                    connection.path = path;
                });
        connection.from = position;
        connection.cachedSource = new WeakReference<>(null);
        connection.path = new ArrayList<>();
    }

    private static void selfReferences(
            FactoryPanelBehaviour panel, BlockPos from, BlockPos to, ArrayDeque<Runnable> undo) {
        // At most four same-board slots need key changes; peer maps are never copied wholesale.
        var outgoing = new ArrayList<FactoryPanelPosition>(4);
        for (var position : panel.targeting)
            if (position.pos().equals(from)) outgoing.add(position);
        for (var position : outgoing) {
            remove(panel.targeting, position, undo);
            add(panel.targeting, remap(position, from, to), undo);
        }
        var incoming = new ArrayList<FactoryPanelPosition>(4);
        for (var position : panel.targetedBy.keySet())
            if (position.pos().equals(from)) incoming.add(position);
        for (var position : incoming) {
            var connection = panel.targetedBy.get(position);
            remove(panel.targetedBy, position, undo);
            relocate(connection, remap(connection.from, from, to), undo);
            put(panel.targetedBy, connection.from, connection, undo);
        }
    }
}
