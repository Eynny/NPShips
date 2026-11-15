package com.eynny.npships.mixin;

import com.eynny.npships.integration.create.PendingBearingAssembly;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native search reads remain real; unfinished planned inputs abort before collection commits. */
@Mixin(value = Level.class, priority = 2000)
public abstract class NPShipsBearingAssemblyReadsMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), require = 1)
    private void npships$pendingNativeInput(
            BlockPos position, CallbackInfoReturnable<BlockState> cir) {
        PendingBearingAssembly.reading((Level) (Object) this, position);
    }
}
