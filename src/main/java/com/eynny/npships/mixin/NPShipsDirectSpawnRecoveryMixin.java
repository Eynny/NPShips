package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectSpawnJournal;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The saved native pointer is assigned before exact construction cancellation is submitted. */
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class NPShipsDirectSpawnRecoveryMixin {
    @Shadow @Final private ServerSubLevelContainer container;

    @Inject(method = "loadHoldingSubLevel", at = @At("RETURN"), remap = false)
    private void npships$recoverConstruction(HoldingSubLevel saved, CallbackInfo ci) {
        DirectSpawnJournal.loaded(container, saved);
    }
}
