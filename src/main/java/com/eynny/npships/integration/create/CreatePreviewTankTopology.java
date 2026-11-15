package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchPreviewTopology;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlock.Shape;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Visual-only adapter for Create 6.0.10 empty, unformed, isolated square tank cuboids.
 * ConnectivityHandler's Y-axis width/height domain and FluidTankBlockEntity's window/cap rules
 * determine the native states. Ambiguous partitions, fluids, boilers, creative tanks and saved
 * controllers keep their authored/native path. Never calls formMulti, setters or gameplay
 * callbacks.
 */
public final class CreatePreviewTankTopology implements PatchPreviewTopology.Session {
    private record Group(BlockPos origin, int width, int height, boolean window) {}

    private final Set<BlockPos> candidates = new HashSet<>();
    private final Map<BlockPos, Group> groups = new HashMap<>();

    public CreatePreviewTankTopology(SchematicDocument doc, Map<BlockPos, BlockState> blocks) {
        for (var p : blocks.keySet()) if (eligible(doc, blocks, p)) candidates.add(p);
        rebuild(doc, blocks, new HashSet<>(candidates));
    }

    private boolean eligible(SchematicDocument doc, Map<BlockPos, BlockState> blocks, BlockPos p) {
        var state = blocks.get(p);
        if (state == null || !state.is(AllBlocks.FLUID_TANK.get())) return false;
        var data = doc.blockEntityData(p);
        return !data.contains("Controller")
                && !doc.blockEntryCopy(p).contains(MountedStorageSnapshot.CONTROLLER_OFFSET)
                && (!data.contains("Size") || data.getInt("Size") == 1)
                && (!data.contains("Height") || data.getInt("Height") == 1)
                && (!data.contains("Window")
                        || data.contains("Window", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))
                && (!data.contains("TankContent")
                        || data.contains("TankContent", net.minecraft.nbt.Tag.TAG_COMPOUND)
                                && data.getCompound("TankContent").isEmpty())
                && (!data.contains("Boiler")
                        || data.contains("Boiler", net.minecraft.nbt.Tag.TAG_COMPOUND)
                                && data.getCompound("Boiler").isEmpty());
    }

    public Set<BlockPos> update(
            SchematicDocument doc, Map<BlockPos, BlockState> blocks, Set<BlockPos> changed) {
        var seeds = new HashSet<BlockPos>();
        for (var p : changed) {
            var old = groups.get(p);
            if (old != null) for (var q : members(old)) seeds.add(q.immutable());
            if (eligible(doc, blocks, p)) candidates.add(p);
            else candidates.remove(p);
            seeds.add(p);
            for (var side : Direction.values()) seeds.add(p.relative(side));
        }
        return rebuild(doc, blocks, seeds);
    }

    private Set<BlockPos> rebuild(
            SchematicDocument doc, Map<BlockPos, BlockState> blocks, Set<BlockPos> seeds) {
        var visited = new HashSet<BlockPos>();
        var affected = new HashSet<BlockPos>();
        for (var seed : seeds) {
            if (!candidates.contains(seed)) {
                if (groups.remove(seed) != null) affected.add(seed);
                continue;
            }
            if (!visited.add(seed)) continue;
            var component = new HashSet<BlockPos>();
            var queue = new ArrayDeque<BlockPos>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                var p = queue.removeFirst();
                component.add(p);
                for (var face : Direction.values()) {
                    var q = p.relative(face);
                    if (candidates.contains(q) && visited.add(q)) queue.addLast(q);
                }
            }
            // A native tank cannot contain more cells than its maximum cuboid. Still visit
            // the whole component above so stale groups and neighboring seeds are handled.
            long maximumCells =
                    (long) FluidTankBlockEntity.getMaxSize()
                            * FluidTankBlockEntity.getMaxSize()
                            * FluidTankBlockEntity.getMaxHeight();
            var group = component.size() > maximumCells ? null : cuboid(doc, blocks, component);
            for (var p : component) {
                var previous = group == null ? groups.remove(p) : groups.put(p, group);
                if (!java.util.Objects.equals(previous, group)) affected.add(p);
            }
        }
        return affected;
    }

    private Group cuboid(
            SchematicDocument doc, Map<BlockPos, BlockState> blocks, Set<BlockPos> component) {
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0;
        int x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (var p : component) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
            for (var face : Direction.values()) {
                var q = p.relative(face);
                var other = blocks.get(q);
                // An authored controller/fluid beside this component makes repartitioning
                // ambiguous.
                if (other != null
                        && other.is(AllBlocks.FLUID_TANK.get())
                        && !candidates.contains(q)) return null;
            }
        }
        int width = x1 - x0 + 1, height = y1 - y0 + 1;
        if (width != z1 - z0 + 1
                || width > FluidTankBlockEntity.getMaxSize()
                || height > FluidTankBlockEntity.getMaxHeight()
                || (long) width * width * height != component.size()) return null;
        var origin = new BlockPos(x0, y0, z0);
        var saved = doc.blockEntityData(origin);
        return new Group(
                origin, width, height, !saved.contains("Window") || saved.getBoolean("Window"));
    }

    private Iterable<BlockPos> members(Group group) {
        return BlockPos.betweenClosed(
                group.origin,
                group.origin.offset(group.width - 1, group.height - 1, group.width - 1));
    }

    public BlockState state(BlockPos p, BlockState authored) {
        var group = groups.get(p);
        if (group == null) return authored;
        int x = p.getX() - group.origin.getX(), z = p.getZ() - group.origin.getZ();
        Shape shape = Shape.PLAIN;
        if (group.window) {
            if (group.width == 1 || group.width == 3 && Math.abs(x - z) == 1) shape = Shape.WINDOW;
            if (group.width == 2)
                shape =
                        x == 0
                                ? z == 0 ? Shape.WINDOW_NW : Shape.WINDOW_SW
                                : z == 0 ? Shape.WINDOW_NE : Shape.WINDOW_SE;
        }
        return authored.setValue(FluidTankBlock.BOTTOM, p.getY() == group.origin.getY())
                .setValue(FluidTankBlock.TOP, p.getY() == group.origin.getY() + group.height - 1)
                .setValue(FluidTankBlock.SHAPE, shape);
    }

    public void visualData(BlockPos p, CompoundTag saved) {
        var group = groups.get(p);
        if (group == null) return;
        saved.putInt("Size", group.width);
        saved.putInt("Height", group.height);
        saved.putBoolean("Window", group.window);
        if (!p.equals(group.origin)) saved.put("Controller", NbtUtils.writeBlockPos(group.origin));
    }
}
