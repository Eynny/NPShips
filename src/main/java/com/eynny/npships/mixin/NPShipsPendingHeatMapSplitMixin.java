package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectSpawnOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.HeatMapPropagationState;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;

import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Only structural split commit waits for an exact pending construction. Native heat queues,
 * mechanics and physics remain live; completed and foreign bodies use ordinary split behavior.
 */
@Mixin(value = SubLevelHeatMapManager.class, remap = false)
public abstract class NPShipsPendingHeatMapSplitMixin {
    @Shadow @Final private ServerSubLevel subLevel;

    @Shadow @Final private Long2IntOpenHashMap subLevelSplits;
    @Shadow @Final private ObjectList<BlockPos> floodfill;
    @Shadow @Final private ObjectList<BlockPos> removed;
    @Shadow @Final private ObjectList<BlockPos> newStarts;
    @Shadow private HeatMapPropagationState state;
    @Shadow private boolean splitComplete;

    @Inject(method = "onSolidAdded", at = @At("HEAD"), require = 1, remap = false)
    private void npships$pendingCellStillBelongsToInitialFill(BlockPos position, CallbackInfo ci) {
        // Native onSolidAdded otherwise ignores a later isolated cell after its first fill.
        // Keep creation bookkeeping open while this exact owner's future plot writes remain.
        if (DirectSpawnOwnership.deferHeatMapSplit(subLevel)) splitComplete = false;
    }

    @Inject(method = "step", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void npships$waitOnlyAtNativeSplitCommit(CallbackInfoReturnable<Boolean> cir) {
        if (state == HeatMapPropagationState.CLEARING
                && floodfill.isEmpty()
                && removed.isEmpty()
                && newStarts.isEmpty()
                && !subLevelSplits.isEmpty()
                && DirectSpawnOwnership.pendingHeatMapSplit(subLevel)) {
            // Return native "nothing left this tick" at the structural commit barrier, rather
            // than repeating the same skipped split for the entire heat-map step allowance.
            // Retain both native maps; publication makes the next ordinary tick resume them.
            cir.setReturnValue(true);
        }
    }

    @WrapMethod(method = "split", require = 1, remap = false)
    private void npships$ownedNativeTick(Operation<Void> original) {
        if (DirectSpawnOwnership.deferHeatMapSplit(subLevel)) {
            splitComplete = false;
            return;
        }
        try (var scope = DirectSpawnOwnership.nativeSplit(subLevel)) {
            if (scope == null) {
                original.call();
                return;
            }
            if (scope.suppressed()) return;
            try {
                original.call();
            } catch (Throwable failure) {
                scope.failed(failure);
            }
        }
    }
}
