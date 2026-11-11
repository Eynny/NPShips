package com.eynny.npships.authoring.document;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.eynny.npships.mixin.NPShipsBlockItemAccessor;

import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

/** Detached placement states and atomic native paired-object edits; never mutate the real world. */
public final class SchematicPlacement {
    private SchematicPlacement() {}

    private record Part(BlockPos pos, BlockState state) {}

    /** Plan complete native paired objects; raw single-cell SNBT edits remain single-cell edits. */
    public static SchematicDocument place(
            SchematicDocument source, BlockPos at, BlockState state, Level parent) {
        var previous = read(source, at);
        var oldPart = part(source, at, previous, parent);
        var newPart = part(source, at, state, parent);
        if (oldPart == null && newPart == null)
            return SchematicBlocks.put(source, at, at, state, null);
        if (newPart != null) {
            if (!source.contains(newPart.pos()))
                throw invalid(
                        "block_position",
                        "The complete object does not fit in the schematic.",
                        newPart.pos().toShortString());
            var existing = read(source, newPart.pos());
            boolean own =
                    oldPart != null
                            && oldPart.pos().equals(newPart.pos())
                            && linked(oldPart.state(), existing);
            boolean occupied =
                    source.blocks().containsKey(newPart.pos())
                            && !source.blockName(newPart.pos()).equals("minecraft:air");
            if (occupied
                    && !own
                    && !linked(newPart.state(), existing)
                    && (existing.isAir()
                            || !existing.canBeReplaced()
                            || !source.blockEntityData(newPart.pos()).isEmpty()))
                throw invalid(
                        "block_occupied",
                        "Another block occupies the object's other part.",
                        newPart.pos().toShortString());
        }
        var intended = new LinkedHashMap<BlockPos, BlockState>();
        intended.put(at, state);
        if (newPart != null) intended.put(newPart.pos(), newPart.state());
        var changes = new LinkedHashMap<BlockPos, BlockState>();
        // Replacing either part retires only its matching old mate. A replaceable two-block
        // plant at the new mate is also removed as one object, without touching unrelated cells.
        for (var pos : intended.keySet()) {
            var old = part(source, pos, read(source, pos), parent);
            if (old != null
                    && source.contains(old.pos())
                    && linked(old.state(), read(source, old.pos())))
                changes.put(old.pos(), Blocks.AIR.defaultBlockState());
        }
        changes.putAll(intended);
        var removed = new HashSet<BlockPos>();
        for (var entry : changes.entrySet())
            if (entry.getValue().isAir()
                    || !source.blockName(entry.getKey())
                            .equals(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .getKey(entry.getValue().getBlock())
                                            .toString())) removed.add(entry.getKey());
        var result =
                SchematicExtensions.intermediate(
                        () -> {
                            var next = source;
                            for (var entry : changes.entrySet())
                                next =
                                        SchematicBlocks.put(
                                                next,
                                                entry.getKey(),
                                                entry.getKey(),
                                                entry.getValue(),
                                                null);
                            return next;
                        });
        return SchematicExtensions.apply(
                source,
                result,
                SchematicExtensions.operation(
                        PatchAuthoring.Kind.EDIT,
                        pos -> removed.contains(pos) ? List.of() : List.of(pos),
                        pos -> List.of(pos),
                        java.util.function.UnaryOperator.identity(),
                        java.util.Optional::of));
    }

    private static BlockState read(SchematicDocument source, BlockPos pos) {
        if (!source.contains(pos)) return Blocks.AIR.defaultBlockState();
        var encoded = source.extensionBlockState(pos);
        var name = net.minecraft.resources.ResourceLocation.tryParse(encoded.getString("Name"));
        // Unknown native records cannot establish a pair. Their raw occupied cells still block
        // an implicit companion, and the ordinary document edit keeps its existing admission.
        if (name == null
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(name))
            return Blocks.AIR.defaultBlockState();
        try {
            return SchematicStateProposals.state(encoded, false);
        } catch (IllegalArgumentException unreadable) {
            // Probe failure is not admission: raw occupied records still block implicit writes,
            // while the existing explicit edit/validator can repair the selected cell.
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static boolean linked(BlockState expected, BlockState actual) {
        if (expected.getBlock() != actual.getBlock()) return false;
        if (expected.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && expected.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        != actual.getValue(BlockStateProperties.HORIZONTAL_FACING)) return false;
        if (expected.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return expected.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == actual.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
        return expected.hasProperty(BlockStateProperties.BED_PART)
                && expected.getValue(BlockStateProperties.BED_PART)
                        == actual.getValue(BlockStateProperties.BED_PART);
    }

    private static Part part(
            SchematicDocument source, BlockPos at, BlockState state, Level parent) {
        Direction direction;
        BlockState other;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower =
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            direction = lower ? Direction.UP : Direction.DOWN;
            other =
                    state.setValue(
                            BlockStateProperties.DOUBLE_BLOCK_HALF,
                            lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            boolean foot = state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
            direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (!foot) direction = direction.getOpposite();
            other =
                    state.setValue(
                            BlockStateProperties.BED_PART, foot ? BedPart.HEAD : BedPart.FOOT);
        } else return null;
        var pos = at.relative(direction);
        var view = new PairView(source, parent, at.below(), pos.below());
        view.states.put(at, state);
        view.states.put(pos, other);
        if (!state.updateShape(direction, other, view, at, pos).is(state.getBlock())
                || !other.updateShape(direction.getOpposite(), state, view, pos, at)
                        .is(state.getBlock())) return null;
        // Both halves must require their mate according to native neighbor rules.
        // Losing a mate may replace a water plant with water rather than air.
        // For example, a young two-stage crop has the property but is still a single block.
        view.states.put(pos, Blocks.AIR.defaultBlockState());
        boolean first =
                !state.updateShape(direction, Blocks.AIR.defaultBlockState(), view, at, pos)
                        .is(state.getBlock());
        view.states.put(pos, other);
        view.states.put(at, Blocks.AIR.defaultBlockState());
        boolean second =
                !other.updateShape(
                                direction.getOpposite(),
                                Blocks.AIR.defaultBlockState(),
                                view,
                                pos,
                                at)
                        .is(state.getBlock());
        return first && second ? new Part(pos, other) : null;
    }

    /** Read-only, callback-local native dependency view; no placement hooks, ticks or live BEs. */
    private static final class PairView extends net.createmod.catnip.levelWrappers.SchematicLevel {
        // Topology probes use a temporary stone state that supports plants. It is never
        // registered or saved; support warnings still inspect the actual document.
        private static final BlockState SUPPORT =
                new BlockState(
                        Blocks.STONE,
                        new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<>(),
                        com.mojang.serialization.MapCodec.unit(Blocks.STONE.defaultBlockState())) {
                    @Override
                    public net.neoforged.neoforge.common.util.TriState canSustainPlant(
                            net.minecraft.world.level.BlockGetter level,
                            BlockPos pos,
                            Direction face,
                            BlockState plant) {
                        return net.neoforged.neoforge.common.util.TriState.TRUE;
                    }
                };
        final SchematicDocument source;
        final Map<BlockPos, BlockState> states = new HashMap<>();
        final java.util.Set<BlockPos> support;

        PairView(SchematicDocument source, Level parent, BlockPos first, BlockPos second) {
            super(BlockPos.ZERO, parent);
            this.source = source;
            this.support = new HashSet<>(List.of(first, second));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.computeIfAbsent(
                    pos.immutable(), p -> support.contains(p) ? SUPPORT : read(source, p));
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return false;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            return false;
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public void setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity entity) {}

        @Override
        public boolean addFreshEntity(net.minecraft.world.entity.Entity entity) {
            return false;
        }

        @Override
        public net.minecraft.server.level.ServerLevel getLevel() {
            throw new IllegalStateException("Detached placement dependency view");
        }
    }

    public static BlockState state(BlockItem item, Level parent, Direction face) {
        var origin = new BlockPos(0, 64, 0);
        var view =
                new net.createmod.catnip.levelWrappers.SchematicLevel(BlockPos.ZERO, parent) {
                    @Override
                    public BlockState getBlockState(BlockPos p) {
                        return p.equals(origin)
                                ? Blocks.AIR.defaultBlockState()
                                : Blocks.STONE.defaultBlockState();
                    }
                };
        var context =
                new DirectionalPlaceContext(
                        view, origin, Direction.NORTH, new ItemStack(item), face);
        return ((NPShipsBlockItemAccessor) item).npships$placementState(context);
    }

    public static BlockState defaultState(Block block, Level level) {
        if (block.asItem() instanceof BlockItem item)
            try {
                var state = state(item, level, Direction.UP);
                if (state != null) return state;
            } catch (RuntimeException ignored) {
            }
        return block.defaultBlockState();
    }
}
