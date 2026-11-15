package com.eynny.npships.mixin;

import com.eynny.npships.integration.aeronautics.InitialSpringLinks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Exact pending reference lifecycle, not a spring tick interceptor. */
@Pseudo
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity",
        remap = false)
public abstract class NPShipsInitialSpringMixin implements InitialSpringLinks.Attached {
    @Unique private InitialSpringLinks.Endpoint npships$initialSpring;

    public InitialSpringLinks.Endpoint npships$initialSpring() {
        return npships$initialSpring;
    }

    public void npships$initialSpring(InitialSpringLinks.Endpoint value) {
        npships$initialSpring = value;
    }

    @WrapOperation(
            method = "tick",
            at = {
                @At(
                        value = "INVOKE",
                        target =
                                "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z",
                        ordinal = 1),
                @At(
                        value = "INVOKE",
                        target =
                                "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z",
                        ordinal = 2)
            },
            require = 2,
            remap = false)
    private boolean npships$knownUncreatedPeer(
            Level level, BlockPos target, boolean drops, Operation<Boolean> original) {
        return !InitialSpringLinks.missingPeerWaits((BlockEntity) (Object) this, level, target)
                && original.call(level, target, drops);
    }

    @WrapOperation(
            method = "remove",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
            require = 1,
            remap = false)
    private boolean npships$preserveReplacedPeer(
            Level level, BlockPos target, boolean drops, Operation<Boolean> original) {
        return !InitialSpringLinks.foreignPartnerMustSurvive(
                        (BlockEntity) (Object) this, level, target)
                && original.call(level, target, drops);
    }
}
