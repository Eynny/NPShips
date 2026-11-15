package com.eynny.npships.mixin;

import com.eynny.npships.integration.aeronautics.InitialBalloonGas;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional native boundary: initial saved gas is installed before a balloon can be ticked. */
@Pseudo
@Mixin(
        targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap",
        remap = false)
public abstract class NPShipsInitialBalloonGasMixin {
    @Inject(method = "addBalloon", at = @At("HEAD"), require = 1)
    private void npships$initialGas(@Coerce Object balloon, CallbackInfo callback) {
        InitialBalloonGas.beforeNativeAdmission(this, balloon);
    }
}
