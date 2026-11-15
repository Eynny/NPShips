package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectSpawnOwnership;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Observe only allocations explicitly authorized by the current direct construction callback. */
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class NPShipsDirectAllocationMixin {
    @Inject(method = "allocateSubLevel", at = @At("HEAD"), require = 1, remap = false)
    private void npships$reserve(
            UUID id, int x, int z, Pose3d pose, CallbackInfoReturnable<SubLevel> cir) {
        DirectSpawnOwnership.beforeAllocation((SubLevelContainer) (Object) this, id);
    }

    @Inject(
            method = "allocateSubLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"),
            require = 1,
            remap = false)
    private void npships$registered(
            UUID id, int x, int z, Pose3d pose, CallbackInfoReturnable<SubLevel> cir) {
        DirectSpawnOwnership.registered((SubLevelContainer) (Object) this, id);
    }
}
