package com.eynny.npships.mixin;

import com.eynny.npships.ship.SpawnRegionGuard;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Keep a bounded initial body cursor valid when the native list changes between slices. */
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class NPShipsSpawnRegionContainerMixin {
    private void npships$listChanged() {
        if (((SubLevelContainer) (Object) this).getLevel() instanceof ServerLevel level)
            SpawnRegionGuard.bodyListChanged(level);
    }

    @Inject(method = "allocateSubLevel", at = @At("RETURN"), remap = false, require = 1)
    private void npships$added(
            UUID id, int x, int z, Pose3d pose, CallbackInfoReturnable<SubLevel> cir) {
        npships$listChanged();
    }

    @Inject(
            method =
                    "removeSubLevel(IILdev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1)
    private void npships$removed(int x, int z, SubLevelRemovalReason reason, CallbackInfo ci) {
        npships$listChanged();
    }
}
