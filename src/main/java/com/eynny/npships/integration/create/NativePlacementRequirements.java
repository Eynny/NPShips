package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.patches.DetachedPatchAssembly.PlacementRequirements;
import com.simibubi.create.api.contraption.BlockMovementChecks;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumSet;
import java.util.LinkedHashSet;

/**
 * Native attachment and survival probes; no block-ID registry and no placement into a live Level.
 */
public final class NativePlacementRequirements {
    private static final Direction[] DIRECTIONS = Direction.values();

    private NativePlacementRequirements() {}

    public static PlacementRequirements inspect(Level source, BlockPos local) {
        var state = source.getBlockState(local);
        if (state.isAir()) return PlacementRequirements.NONE;
        boolean brittle = BlockMovementChecks.isBrittle(state);
        var pairDirection = peerDirection(state);
        var attached = EnumSet.noneOf(Direction.class);
        for (var direction : DIRECTIONS)
            if (BlockMovementChecks.isBlockAttachedTowards(state, source, local, direction))
                attached.add(direction);
        if (attached.isEmpty() && !brittle && pairDirection == null)
            return PlacementRequirements.NONE;
        var supports = new LinkedHashSet<BlockPos>();
        var peers = new LinkedHashSet<BlockPos>();
        var mask = new NeighbourMask(source);
        boolean survives = state.canSurvive(source, local);
        for (var direction : DIRECTIONS) {
            var otherPos = local.relative(direction);
            var other = source.getBlockState(otherPos);
            if (other.isAir()) continue;
            mask.omitted = otherPos;
            boolean shapeRequires =
                    !state.updateShape(
                                    direction,
                                    Blocks.AIR.defaultBlockState(),
                                    mask,
                                    local,
                                    otherPos)
                            .is(state.getBlock());
            boolean required =
                    brittle && attached.contains(direction)
                            || survives && !state.canSurvive(mask, local)
                            || shapeRequires;
            if (!required) continue;
            if (direction == pairDirection
                    && other.is(state.getBlock())
                    && peerDirection(other) == direction.getOpposite()
                    && shapeRequires) {
                mask.omitted = local;
                if (!other.updateShape(
                                direction.getOpposite(),
                                Blocks.AIR.defaultBlockState(),
                                mask,
                                otherPos,
                                local)
                        .is(other.getBlock())) {
                    peers.add(otherPos);
                    continue;
                }
            }
            supports.add(otherPos);
        }
        return new PlacementRequirements(supports, peers);
    }

    // Same generic property/native-shape proof as authoring SchematicPlacement.part. Properties
    // alone do not prove a pair (young crops can carry DOUBLE_BLOCK_HALF while still single-cell).
    private static Direction peerDirection(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? Direction.UP
                    : Direction.DOWN;
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    ? facing
                    : facing.getOpposite();
        }
        return null;
    }

    /** A callback-local read view. Native scheduled probes use SchematicLevel's blackhole ticks. */
    private static final class NeighbourMask extends SchematicLevel {
        private final Level source;
        private BlockPos omitted;

        NeighbourMask(Level source) {
            super(BlockPos.ZERO, source);
            this.source = source;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(omitted) ? Blocks.AIR.defaultBlockState() : source.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getMinBuildHeight() {
            return source == null ? super.getMinBuildHeight() : source.getMinBuildHeight();
        }

        @Override
        public int getHeight() {
            return source == null ? super.getHeight() : source.getHeight();
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return false;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int depth) {
            return false;
        }

        @Override
        public void setBlockEntity(BlockEntity entity) {}

        @Override
        public boolean addFreshEntity(Entity entity) {
            return false;
        }

        @Override
        public void sendBlockUpdated(BlockPos pos, BlockState old, BlockState state, int flags) {}

        @Override
        public ServerLevel getLevel() {
            throw new IllegalStateException("Detached native placement dependency view");
        }
    }
}
