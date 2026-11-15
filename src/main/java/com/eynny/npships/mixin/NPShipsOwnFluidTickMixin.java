package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectFluidProvenance;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Provenance only: all ordinary native fluid operations still execute unchanged. */
@Mixin(FlowingFluid.class)
public abstract class NPShipsOwnFluidTickMixin {
    @WrapMethod(method = "tick")
    private void npships$ownedOrigin(
            Level level, BlockPos pos, FluidState state, Operation<Void> original) {
        try (var scope = DirectFluidProvenance.tick(level, pos, state)) {
            original.call(level, pos, state);
        }
    }

    @WrapOperation(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 2)
    private boolean npships$flowUpdate(
            Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        try (var scope = DirectFluidProvenance.write(level, pos, state)) {
            boolean result = original.call(level, pos, state, flags);
            if (scope != null) scope.completed(result);
            return result;
        }
    }

    @WrapOperation(
            method = "spreadTo",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1)
    private boolean npships$flowSpread(
            LevelAccessor level,
            BlockPos pos,
            BlockState state,
            int flags,
            Operation<Boolean> original) {
        try (var scope = DirectFluidProvenance.write(level, pos, state)) {
            boolean result = original.call(level, pos, state, flags);
            if (scope != null) scope.completed(result);
            return result;
        }
    }
}
