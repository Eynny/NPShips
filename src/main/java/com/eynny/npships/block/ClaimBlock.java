package com.eynny.npships.block;

import com.eynny.npships.integration.NativeBlockAccess;
import com.eynny.npships.registry.NPShipsRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Player-accessible claim of the clicked physical body, never a proximity guess. */
public final class ClaimBlock extends Block
        implements net.minecraft.world.level.block.EntityBlock,
                dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener {
    /** Direction from the support into the mechanism; UP is the authored model orientation. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public static final IntegerProperty TURN = IntegerProperty.create("turn", 0, 3);

    public ClaimBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(FACING, Direction.UP).setValue(TURN, 0));
    }

    public static BlockPos support(BlockPos pos, BlockState state) {
        return pos.relative(state.getValue(FACING).getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TURN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = defaultBlockState().setValue(FACING, context.getClickedFace());
        // Sable supplies these directions in the containing body's local frame.
        for (var direction : context.getNearestLookingDirections()) {
            if (direction.getAxis() == state.getValue(FACING).getAxis()) continue;
            state = orient(state, state.getValue(FACING), direction.getOpposite());
            break;
        }
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    public static Quaternionf orientation(BlockState state) {
        return state.getValue(FACING)
                .getRotation()
                .rotateY(state.getValue(TURN) * (float) (Math.PI / 2));
    }

    public static Direction front(BlockState state) {
        var north = new Vector3f(0, 0, -1).rotate(orientation(state));
        return Direction.getNearest(north.x, north.y, north.z);
    }

    private static BlockState orient(BlockState state, Direction facing, Direction front) {
        var mounted = state.setValue(FACING, facing);
        for (int turn = 0; turn < 4; turn++) {
            var candidate = mounted.setValue(TURN, turn);
            if (front(candidate) == front) return candidate;
        }
        throw new IllegalArgumentException("Claim front must lie in its mounting plane");
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        try (var read =
                level instanceof net.minecraft.server.level.ServerLevel server
                        ? NativeBlockAccess.capture(server, pos)
                        : null) {
            var support = support(pos, state);
            return Block.isFaceFull(
                    level.getBlockState(support).getCollisionShape(level, support),
                    state.getValue(FACING));
        }
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbor,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        return direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return orient(
                state, rotation.rotate(state.getValue(FACING)), rotation.rotate(front(state)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return orient(state, mirror.mirror(state.getValue(FACING)), mirror.mirror(front(state)));
    }

    @Override
    public void afterMove(
            net.minecraft.server.level.ServerLevel originLevel,
            net.minecraft.server.level.ServerLevel resultingLevel,
            BlockState state,
            BlockPos oldPos,
            BlockPos newPos) {
        if (NativeBlockAccess.at(resultingLevel, newPos) instanceof ClaimBlockEntity claim)
            claim.invalidateStatus(); // Resolve once after the assembly/split/merge commits.
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(
            BlockPos pos, BlockState state) {
        return new ClaimBlockEntity(pos, state);
    }

    @Override
    public <T extends net.minecraft.world.level.block.entity.BlockEntity>
            net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
                    Level level,
                    BlockState state,
                    net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return !level.isClientSide && type == NPShipsRegistry.CLAIM_BLOCK_ENTITY.get()
                ? (world, pos, block, be) ->
                        ClaimBlockEntity.tick(world, pos, block, (ClaimBlockEntity) be)
                : null;
    }

    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state,
            net.minecraft.world.level.BlockGetter level,
            BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return ClaimShape.get(state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSpectator()) return InteractionResult.PASS;
        if (level instanceof ServerLevel server
                && NativeBlockAccess.at(server, pos) instanceof ClaimBlockEntity claim)
            claim.use(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (player.isSpectator()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        useWithoutItem(state, level, pos, player, hit);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}
