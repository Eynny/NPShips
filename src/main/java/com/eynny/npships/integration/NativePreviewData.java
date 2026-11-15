package com.eynny.npships.integration;

import com.eynny.npships.authoring.patches.PatchPreviewData;
import com.eynny.npships.mixin.NPShipsAnalogLeverAccessor;
import com.eynny.npships.mixin.NPShipsSpringAccessor;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gauge.GaugeBlockEntity;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;

import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Native interpolation serializers. Only registered stateless code is cached, never a BE. */
public final class NativePreviewData {
    private NativePreviewData() {}

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchPreviewData.class
                || !java.util.Set.of(
                                "create_devices", "simulated_devices", "cbc_default_connections")
                        .contains(type)) return null;
        return contract.cast(
                new PatchPreviewData() {
                    @Override
                    public boolean applies(BlockEntity entity) {
                        return switch (type) {
                            case "create_devices" ->
                                    entity instanceof KineticBlockEntity
                                            || entity instanceof AnalogLeverBlockEntity
                                            || entity instanceof GaugeBlockEntity
                                            || entity
                                                    instanceof
                                                    com.simibubi.create.content.contraptions.chassis
                                                            .StickerBlockEntity;
                            case "simulated_devices" ->
                                    entity instanceof SpringBlockEntity
                                            || entity.getClass()
                                                    .getName()
                                                    .equals(
                                                            "dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity")
                                            || entity.getClass()
                                                    .getName()
                                                    .equals(
                                                            "dev.simulated_team.simulated.content.blocks.redstone.redstone_accumulator.RedstoneAccumulatorBlockEntity")
                                            || entity.getClass()
                                                    .getName()
                                                    .equals(
                                                            "dev.simulated_team.simulated.content.blocks.redstone.redstone_inductor.RedstoneInductorBlockEntity");
                            case "cbc_default_connections" -> cannonType(entity) != null;
                            default -> false;
                        };
                    }

                    @Override
                    public void settle(BlockEntity entity, CompoundTag data, boolean emptySource) {
                        switch (type) {
                            case "create_devices" -> {
                                if (entity instanceof KineticBlockEntity kinetic)
                                    kinetic.setSpeed(0);
                                if (entity instanceof GaugeBlockEntity gauge) {
                                    gauge.dialState = gauge.dialTarget;
                                    gauge.prevDialState = gauge.dialTarget;
                                }
                                animation(entity);
                                if (!emptySource && entity instanceof AnalogLeverBlockEntity lever)
                                    ((NPShipsAnalogLeverAccessor) lever)
                                            .npships$clientState()
                                            .startWithValue(lever.getState());
                            }
                            case "simulated_devices" -> {
                                animation(entity);
                                if (!emptySource && entity instanceof SpringBlockEntity spring)
                                    ((NPShipsSpringAccessor) spring)
                                            .npships$renderLength()
                                            .startWithValue(data.getDouble("DesiredLength"));
                            }
                            case "cbc_default_connections" -> {
                                var owner = cannonType(entity);
                                try {
                                    for (String axis : new String[] {"Yaw", "Pitch"}) {
                                        var previous = owner.getDeclaredField("prev" + axis);
                                        var current = owner.getDeclaredField("cannon" + axis);
                                        previous.setAccessible(true);
                                        current.setAccessible(true);
                                        previous.setFloat(entity, current.getFloat(entity));
                                    }
                                    for (var part :
                                            (java.util.List<?>)
                                                    owner.getMethod("getAllKineticBlockEntities")
                                                            .invoke(entity))
                                        ((KineticBlockEntity) part).setSpeed(0);
                                } catch (ReflectiveOperationException error) {
                                    throw new IllegalStateException(
                                            "Cannot settle cannon mount preview", error);
                                }
                            }
                            default ->
                                    throw new IllegalStateException(
                                            "Unknown registered preview serializer: " + type);
                        }
                    }
                });
    }

    private static Class<?> cannonType(BlockEntity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName()
                    .equals(
                            "rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity"))
                return type;
        return null;
    }

    private static final ClassValue<java.lang.reflect.Field> VISUAL_FIELD =
            new ClassValue<>() {
                @Override
                protected java.lang.reflect.Field computeValue(Class<?> type) {
                    String field =
                            switch (type.getName()) {
                                case "dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity" ->
                                        "clientAngle";
                                case "dev.simulated_team.simulated.content.blocks.redstone.redstone_accumulator.RedstoneAccumulatorBlockEntity",
                                                "dev.simulated_team.simulated.content.blocks.redstone.redstone_inductor.RedstoneInductorBlockEntity" ->
                                        "lerpedState";
                                case "com.simibubi.create.content.contraptions.chassis.StickerBlockEntity" ->
                                        "piston";
                                default -> null;
                            };
                    if (field == null) return null;
                    try {
                        var result = type.getDeclaredField(field);
                        result.setAccessible(true);
                        return result;
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(
                                "Cannot initialize static preview for " + type.getName(), error);
                    }
                }
            };

    private static void animation(BlockEntity entity) {
        var field = VISUAL_FIELD.get(entity.getClass());
        if (field == null) return;
        try {
            var animation = (net.createmod.catnip.animation.LerpedFloat) field.get(entity);
            double target = animation.getChaseTarget();
            if (net.neoforged.fml.ModList.get().isLoaded("simulated")
                    && entity
                            instanceof
                            dev.simulated_team.simulated.content.blocks.throttle_lever
                                                    .ThrottleLeverBlockEntity
                                            lever)
                target =
                        lever.getBlockState()
                                        .getValue(
                                                dev.simulated_team.simulated.content.blocks
                                                        .throttle_lever.ThrottleLeverBlock.INVERTED)
                                ? 15 - lever.getState()
                                : lever.getState();
            if (entity
                    instanceof
                    com.simibubi.create.content.contraptions.chassis.StickerBlockEntity sticker)
                target = sticker.isBlockStateExtended() ? 1 : 0;
            animation.startWithValue(target);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot settle static preview animation", error);
        }
    }
}
