package com.eynny.npships.authoring.document;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Proxy;
import java.util.*;

/** Local connection updates against the complete template, independent of the visible slice. */
public final class NeighborConnections {
    private NeighborConnections() {}

    public static boolean automaticProperty(BlockState state, String name) {
        var block = state.getBlock();
        if (block instanceof StairBlock) return name.equals("shape");
        if (block instanceof CrossCollisionBlock || block instanceof WallBlock)
            return Set.of("north", "south", "east", "west", "up").contains(name);
        return false;
    }

    public static SchematicDocument normalize(SchematicDocument doc, SchematicDocument before) {
        var changed = doc.changedBlocks(before, 4096);
        if (changed == null || changed.isEmpty() || doc.hasMultiplePalettes()) return doc;
        var palette = doc.paletteCopy();
        var decoded = new ArrayList<BlockState>();
        for (var p : palette)
            decoded.add(
                    NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), (CompoundTag) p));
        var edits = new HashMap<BlockPos, BlockState>();
        java.util.function.Function<BlockPos, BlockState> state =
                p -> {
                    if (edits.containsKey(p)) return edits.get(p);
                    var index = doc.blocks().get(p);
                    return index == null ? Blocks.AIR.defaultBlockState() : decoded.get(index);
                };
        var random = net.minecraft.util.RandomSource.create(0);
        var access =
                (LevelAccessor)
                        Proxy.newProxyInstance(
                                LevelAccessor.class.getClassLoader(),
                                new Class<?>[] {LevelAccessor.class},
                                (proxy, method, args) -> {
                                    return switch (method.getName()) {
                                        case "getBlockState" -> state.apply((BlockPos) args[0]);
                                        case "getFluidState" ->
                                                state.apply((BlockPos) args[0]).getFluidState();
                                        case "getHeight" -> doc.size().getY();
                                        case "getMinBuildHeight" -> 0;
                                        case "getMaxBuildHeight" -> doc.size().getY();
                                        case "isClientSide" -> true;
                                        case "getRandom" -> random;
                                        case "getBlockEntity" -> null;
                                        default -> {
                                            if (method.getReturnType() == void.class) yield null;
                                            if (method.getReturnType() == boolean.class)
                                                yield false;
                                            if (method.getReturnType() == int.class) yield 0;
                                            if (method.getReturnType() == long.class) yield 0L;
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                        }
                                    };
                                });
        var candidates = new HashSet<BlockPos>(changed);
        for (var p : changed) for (var face : Direction.values()) candidates.add(p.relative(face));
        // Two passes let both sides of a newly connected pair observe the resulting state.
        for (int pass = 0; pass < 2; pass++)
            for (var p : candidates) {
                var old = state.apply(p);
                if (!(old.getBlock() instanceof CrossCollisionBlock
                        || old.getBlock() instanceof WallBlock
                        || old.getBlock() instanceof StairBlock)) continue;
                var next = old;
                try {
                    for (var face : Direction.values())
                        next =
                                next.updateShape(
                                        face,
                                        state.apply(p.relative(face)),
                                        access,
                                        p,
                                        p.relative(face));
                } catch (RuntimeException ignored) {
                    // A modded subclass requiring live world services retains its saved state.
                    continue;
                }
                if (next.getBlock() == old.getBlock() && next != old) edits.put(p, next);
            }
        if (edits.isEmpty()) return doc;
        var blocks = new HashMap<BlockPos, CompoundTag>();
        edits.forEach(
                (p, s) -> {
                    var encoded = NbtUtils.writeBlockState(s);
                    int index = palette.indexOf(encoded);
                    if (index < 0) {
                        index = palette.size();
                        palette.add(encoded);
                    }
                    var entry = doc.blockEntryCopy(p);
                    entry.putInt("state", index);
                    blocks.put(p, entry);
                });
        return doc.withBlocks(blocks, palette, doc.configCopy());
    }
}
