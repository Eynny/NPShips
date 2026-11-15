package com.eynny.npships.mixin;

import com.eynny.npships.ship.SpawnRegionGuard;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Plot growth is observable before the next native body tick publishes its global bounds. */
@Mixin(value = LevelPlot.class, remap = false)
public abstract class NPShipsSpawnRegionPlotMixin {
    @Inject(method = "updateBoundingBox", at = @At("RETURN"), remap = false, require = 1)
    private void npships$extent(CallbackInfo ci) {
        SpawnRegionGuard.bodyChanged(((LevelPlot) (Object) this).getSubLevel());
    }

    @Inject(method = "setBoundingBox", at = @At("RETURN"), remap = false, require = 1)
    private void npships$assignedExtent(
            dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds, CallbackInfo ci) {
        SpawnRegionGuard.bodyChanged(((LevelPlot) (Object) this).getSubLevel());
    }
}
