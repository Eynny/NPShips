package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectSpawnOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Verified native provider callback; never a generic block-entity allocation interceptor. */
@Pseudo
@Mixin(
        targets =
                "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity",
        remap = false)
public abstract class NPShipsSwivelAssemblyOwnershipMixin {
    @WrapMethod(method = "assemble", require = 1, remap = false)
    private void npships$ownedNativeProvider(Operation<Void> original) {
        var bearing = (SwivelBearingBlockEntity) (Object) this;
        try (var scope = DirectSpawnOwnership.nativeCallback(bearing)) {
            if (scope == null || !scope.owned()) {
                original.call();
                return;
            }
            if (scope.suppressed()) return;
            try {
                original.call();
                // Empty-front fallback finishes pose, real link mass and rotary constraint here.
                scope.completed(bearing.getSubLevelID());
            } catch (Throwable failure) {
                scope.failed(failure);
            }
        }
    }

    @WrapOperation(
            method = "assemble",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;allocateNewSubLevel(Ldev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
            require = 1,
            remap = false)
    private SubLevel npships$ownedNativeEmptyFront(
            ServerSubLevelContainer container, Pose3d pose, Operation<SubLevel> original) {
        var bearing = (SwivelBearingBlockEntity) (Object) this;
        try (var allocation =
                DirectSpawnOwnership.nativeChild(
                        (ServerLevel) bearing.getLevel(), bearing.getBlockPos())) {
            return original.call(container, pose);
        }
    }
}
