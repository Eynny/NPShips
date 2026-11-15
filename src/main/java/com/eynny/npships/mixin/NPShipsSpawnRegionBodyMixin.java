package com.eynny.npships.mixin;

import com.eynny.npships.ship.SpawnRegionGuard;

import dev.ryanhcode.sable.sublevel.SubLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native pose/bounds publication invalidates only intersecting active spawn guards. */
@Mixin(value = SubLevel.class, remap = false)
public abstract class NPShipsSpawnRegionBodyMixin {
    @Inject(method = "updateBoundingBox", at = @At("RETURN"), remap = false, require = 1)
    private void npships$bounds(CallbackInfo ci) {
        SpawnRegionGuard.bodyChanged((SubLevel) (Object) this);
    }
}
