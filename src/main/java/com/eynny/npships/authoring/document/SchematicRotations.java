package com.eynny.npships.authoring.document;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.*;

/** Enumerate orientation properties only; preserve materials, waterlogging and other state. */
public final class SchematicRotations {
    private SchematicRotations() {}

    private static List<BlockState> candidates(BlockState state) {
        List<BlockState> states = new ArrayList<>();
        states.add(state);
        for (var property : state.getProperties())
            if (orientation(property)) {
                var next = new ArrayList<BlockState>();
                for (var base : states)
                    for (var value : property.getPossibleValues()) {
                        if (value == SlabType.DOUBLE) continue;
                        if (property.getName().equals("modem")
                                && (value.toString().equalsIgnoreCase("none")
                                        != state.getValue(property)
                                                .toString()
                                                .equalsIgnoreCase("none"))) continue;
                        next.add(set(base, property, value));
                    }
                states = next;
            }
        return states;
    }

    private static final Map<net.minecraft.world.level.block.Block, List<BlockState>> PLACEMENTS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public static boolean orientation(Property<?> property) {
        return property.getValueClass() == net.minecraft.core.Direction.class
                || property.getValueClass() == net.minecraft.core.Direction.Axis.class
                || Set.of(
                                "facing",
                                "horizontal_facing",
                                "axis",
                                "half",
                                "face",
                                "rotation",
                                "modem")
                        .contains(property.getName())
                || property == BlockStateProperties.SLAB_TYPE;
    }

    public static List<BlockState> allowed(
            BlockState state, net.minecraft.world.level.Level level) {
        if (state.getProperties().stream().noneMatch(SchematicRotations::orientation))
            return List.of(state);
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
            return List.of(state);
        var nativeStates =
                PLACEMENTS.computeIfAbsent(state.getBlock(), block -> probe(block, level));
        var result = new ArrayList<BlockState>();
        for (var candidate : candidates(state))
            if (nativeStates.stream()
                    .anyMatch(nativeState -> sameOrientation(candidate, nativeState)))
                result.add(candidate);
        return result.isEmpty() ? List.of(state) : List.copyOf(result);
    }

    private static boolean sameOrientation(BlockState a, BlockState b) {
        for (var p : a.getProperties())
            if (orientation(p) && !a.getValue(p).equals(b.getValue(p))) return false;
        return true;
    }

    public static BlockState cycle(
            BlockState state, int direction, net.minecraft.world.level.Level level) {
        var states = allowed(state, level);
        int index = states.indexOf(state);
        return states.get(
                index < 0 ? 0 : Math.floorMod(index + (direction >= 0 ? 1 : -1), states.size()));
    }

    public static BlockState cycleProperty(
            BlockState state, Property<?> property, net.minecraft.world.level.Level level) {
        if (!orientation(property)) return state.cycle(property);
        var states =
                allowed(state, level).stream()
                        .filter(
                                s ->
                                        state.getProperties().stream()
                                                .allMatch(
                                                        p ->
                                                                p == property
                                                                        || s.getValue(p)
                                                                                .equals(
                                                                                        state
                                                                                                .getValue(
                                                                                                        p))))
                        .toList();
        if (states.isEmpty()) return state;
        int index = states.indexOf(state);
        return states.get(index < 0 ? 0 : (index + 1) % states.size());
    }

    /** Detached, fully supported placement probes; never place blocks or call setPlacedBy. */
    private static List<BlockState> probe(
            net.minecraft.world.level.block.Block block, net.minecraft.world.level.Level parent) {
        var result = new LinkedHashSet<BlockState>();
        var origin = new net.minecraft.core.BlockPos(0, 64, 0);
        var view =
                new net.createmod.catnip.levelWrappers.SchematicLevel(
                        net.minecraft.core.BlockPos.ZERO, parent) {
                    @Override
                    public BlockState getBlockState(net.minecraft.core.BlockPos pos) {
                        return pos.equals(origin)
                                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                                : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                    }
                };
        // Placement APIs may read pitch, crouching or held items directly from the player.
        // This detached owner is never registered or ticked and cannot change the live world.
        var player = new PlacementPlayer(view, origin);
        int yawSteps =
                block.defaultBlockState().hasProperty(BlockStateProperties.ROTATION_16) ? 16 : 4;
        try {
            var items =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                            .filter(
                                    item ->
                                            item instanceof net.minecraft.world.item.BlockItem bi
                                                    && bi.getBlock() == block)
                            .toList();
            if (items.isEmpty()) items = List.of(block.asItem());
            for (var item : items)
                for (int yaw = 0; yaw < yawSteps; yaw++)
                    for (int pitch = -1; pitch <= 1; pitch++)
                        for (var face : net.minecraft.core.Direction.values())
                            for (double height : new double[] {.25, .75})
                                for (boolean secondary : new boolean[] {false, true}) {
                                    float angle = yaw * 360f / yawSteps;
                                    player.setYRot(angle);
                                    player.setXRot(pitch * 80f);
                                    player.setShiftKeyDown(secondary);
                                    player.setItemInHand(
                                            net.minecraft.world.InteractionHand.MAIN_HAND,
                                            new net.minecraft.world.item.ItemStack(item));
                                    var horizontal = net.minecraft.core.Direction.fromYRot(angle);
                                    var look =
                                            pitch < 0
                                                    ? net.minecraft.core.Direction.UP
                                                    : pitch > 0
                                                            ? net.minecraft.core.Direction.DOWN
                                                            : horizontal;
                                    var context =
                                            new net.minecraft.world.item.context
                                                    .DirectionalPlaceContext(
                                                    view,
                                                    origin,
                                                    look,
                                                    new net.minecraft.world.item.ItemStack(item),
                                                    face) {
                                                @Override
                                                public net.minecraft.world.entity.player.Player
                                                        getPlayer() {
                                                    return player;
                                                }

                                                @Override
                                                public net.minecraft.core.Direction
                                                        getNearestLookingDirection() {
                                                    return look;
                                                }

                                                @Override
                                                public net.minecraft.core.Direction[]
                                                        getNearestLookingDirections() {
                                                    var dirs =
                                                            new ArrayList<>(
                                                                    List.of(
                                                                            net.minecraft.core
                                                                                    .Direction
                                                                                    .values()));
                                                    dirs.remove(look);
                                                    dirs.addFirst(look);
                                                    return dirs.toArray(
                                                            net.minecraft.core.Direction[]::new);
                                                }

                                                @Override
                                                public net.minecraft.core.Direction
                                                        getHorizontalDirection() {
                                                    return horizontal;
                                                }

                                                @Override
                                                public float getRotation() {
                                                    return angle;
                                                }

                                                @Override
                                                public boolean isSecondaryUseActive() {
                                                    return secondary;
                                                }

                                                @Override
                                                public net.minecraft.world.phys.Vec3
                                                        getClickLocation() {
                                                    return new net.minecraft.world.phys.Vec3(
                                                            .5, 64 + height, .5);
                                                }
                                            };
                                    var placed = block.getStateForPlacement(context);
                                    if (placed != null && placed.getBlock() == block)
                                        result.add(placed);
                                }
        } catch (RuntimeException exception) {
            return List.of();
        } // Unsupported native placement has no safe general orientation oracle.
        return List.copyOf(result);
    }

    private static final class PlacementPlayer extends net.minecraft.world.entity.player.Player {
        PlacementPlayer(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos origin) {
            super(
                    level,
                    origin,
                    0,
                    new com.mojang.authlib.GameProfile(new UUID(0, 0), "NPShipsPlacement"));
            setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState set(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }
}
