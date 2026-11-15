package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectFluidProvenance;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;

/** Exact native mutation scope; all foreign/reentrant mutations still run normally. */
@Mixin(Level.class)
public abstract class NPShipsOwnFluidMutationMixin {
    @WrapMethod(
            method =
                    "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean npships$exactMutation(
            BlockPos pos,
            BlockState state,
            int flags,
            int recursionLeft,
            Operation<Boolean> original) {
        try (var scope = DirectFluidProvenance.mutation((Level) (Object) this, pos, state)) {
            boolean result = original.call(pos, state, flags, recursionLeft);
            if (scope != null) scope.completed(result);
            return result;
        }
    }
}
