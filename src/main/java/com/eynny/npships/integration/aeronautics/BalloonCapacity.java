package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.SchematicResources;
import com.eynny.npships.integration.OptionalCalls;

import dev.ryanhcode.sable.util.LevelAccelerator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Native balloon graph over detached schematic blocks; never registers a live balloon. */
public final class BalloonCapacity {
    private BalloonCapacity() {}

    public static BlockPos cast(SchematicDocument doc, Level view, BlockPos source) {
        var be = SchematicResources.detached(doc, source, view.registryAccess());
        if (be == null || !AeronauticsPatch.burner(doc.blockName(source))) return null;
        try {
            be.setLevel(view);
            OptionalCalls.call(be, "doRaycast");
            return (BlockPos) OptionalCalls.call(be, "getCastPosition");
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot inspect balloon provider", e);
        }
    }

    public static int calculate(SchematicDocument doc, Level view, BlockPos cast) {
        var graph = graph(doc, view, cast);
        if (graph == null) return 0;
        try {
            int capacity = 0;
            for (var row : (Object[]) OptionalCalls.call(graph, "getAllLayers"))
                if (row != null)
                    for (var layer : (java.util.List<?>) row)
                        capacity +=
                                ((Number) OptionalCalls.field(layer, "hotAirCount")).intValue()
                                        - ((Number) OptionalCalls.field(layer, "solidCount"))
                                                .intValue();
            return capacity;
        } catch (Exception e) {
            throw new IllegalArgumentException("Balloon capacity cannot be determined", e);
        }
    }

    /** Group provider casts using Aeronautics' connected-volume graph, not block proximity. */
    public static java.util.Map<BlockPos, BlockPos> groups(
            SchematicDocument doc, Level view, java.util.Map<BlockPos, BlockPos> casts) {
        var result = new java.util.HashMap<BlockPos, BlockPos>();
        for (var owner : casts.keySet().stream().sorted().toList()) {
            if (result.containsKey(owner)) continue;
            result.put(owner, owner);
            var graph = graph(doc, view, casts.get(owner));
            if (graph == null) continue;
            for (var other : casts.entrySet()) {
                if (other.getValue() == null || result.containsKey(other.getKey())) continue;
                try {
                    if ((boolean) OptionalCalls.call(graph, "hasBlockAt", other.getValue()))
                        result.put(other.getKey(), owner);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Cannot group balloon sources", e);
                }
            }
        }
        return java.util.Map.copyOf(result);
    }

    private static Object graph(SchematicDocument doc, Level view, BlockPos cast) {
        if (cast == null || !doc.contains(cast)) return null;
        var palette = new java.util.ArrayList<BlockState>();
        var tags = doc.paletteCopy();
        for (int i = 0; i < tags.size(); i++)
            palette.add(
                    net.minecraft.nbt.NbtUtils.readBlockState(
                            view.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                            tags.getCompound(i)));
        // LevelAccelerator normally reads real chunks. Override the only read path
        // used by BalloonBuilder so it sees the detached document exclusively.
        var reader =
                new LevelAccelerator(view) {
                    int reads;

                    @Override
                    public BlockState getBlockState(BlockPos pos) {
                        if (++reads > 16_000_000)
                            throw new IllegalArgumentException("Balloon analysis limit exceeded.");
                        var index = doc.blocks().get(pos);
                        return index == null
                                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                                : palette.get(index);
                    }

                    @Override
                    public boolean isOutsideBuildHeight(net.minecraft.core.Vec3i pos) {
                        return pos.getY() < 0 || pos.getY() >= doc.size().getY();
                    }
                };
        try {
            String root = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.";
            var graph =
                    Class.forName(root + "BalloonLayerGraph")
                            .getConstructor(int.class)
                            .newInstance(cast.getY());
            var builder = Class.forName(root + "BalloonBuilder");
            if (!(boolean)
                    OptionalCalls.call(
                            builder, "upwardsBiasedFloodFill", reader, cast, graph, null, null))
                return null;
            OptionalCalls.call(builder, "completeBalloon", reader, graph, null);
            return graph;
        } catch (Exception e) {
            throw new IllegalArgumentException("Balloon capacity cannot be determined", e);
        }
    }
}
