package com.eynny.npships.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.joml.Vector3f;

/** Resting model geometry, cached for each mounting face and quarter turn. */
final class ClaimShape {
    private static final VoxelShape[] SHAPES = new VoxelShape[24];
    private static final VoxelShape RESTING = resting();

    private ClaimShape() {}

    static VoxelShape get(BlockState state) {
        int index =
                state.getValue(ClaimBlock.FACING).ordinal() * 4 + state.getValue(ClaimBlock.TURN);
        if (SHAPES[index] == null) SHAPES[index] = rotate(RESTING, state);
        return SHAPES[index];
    }

    private static VoxelShape resting() {
        // Coordinates of the authored Claim model, in model pixels.
        double[][] boxes = {
            {1, 0, 1, 15, 2, 15},
            {4, 2, 4, 12, 4, 12},
            {6, 4, 6, 10, 7, 10},
            {5, 7, 5, 11, 9, 11},
            {3, 9, 3, 13, 13, 13}
        };
        var shape = Shapes.empty();
        for (var b : boxes) shape = Shapes.or(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        return shape.optimize();
    }

    private static VoxelShape rotate(VoxelShape source, BlockState state) {
        var rotation = ClaimBlock.orientation(state);
        var result = Shapes.empty();
        for (var box : source.toAabbs()) {
            var min = new Vector3f(Float.POSITIVE_INFINITY);
            var max = new Vector3f(Float.NEGATIVE_INFINITY);
            for (int corner = 0; corner < 8; corner++) {
                var point =
                        new Vector3f(
                                        (float) ((corner & 1) == 0 ? box.minX : box.maxX) - .5f,
                                        (float) ((corner & 2) == 0 ? box.minY : box.maxY) - .5f,
                                        (float) ((corner & 4) == 0 ? box.minZ : box.maxZ) - .5f)
                                .rotate(rotation)
                                .add(.5f, .5f, .5f);
                min.min(point);
                max.max(point);
            }
            result =
                    Shapes.or(
                            result,
                            Shapes.box(
                                    clean(min.x),
                                    clean(min.y),
                                    clean(min.z),
                                    clean(max.x),
                                    clean(max.y),
                                    clean(max.z)));
        }
        return result.optimize();
    }

    private static double clean(float value) {
        // Quarter turns are exact; remove quaternion rounding at block boundaries.
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
