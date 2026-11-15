package com.eynny.npships.mixin;

import com.eynny.npships.integration.create.PendingBearingAssembly;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shared Create bearing search, including providers using its native contraption type. */
@Mixin(value = BearingContraption.class, remap = false)
public abstract class NPShipsBearingAssemblyReadinessMixin {
    @WrapOperation(
            method = "assemble",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/simibubi/create/content/contraptions/bearing/BearingContraption;searchMovedStructure(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"),
            require = 1,
            remap = false)
    private boolean npships$waitForNativeInputs(
            BearingContraption contraption,
            Level level,
            BlockPos anchor,
            Direction forced,
            Operation<Boolean> original,
            Level assemblyLevel,
            BlockPos controllerPosition) {
        try (var search = PendingBearingAssembly.begin(assemblyLevel, controllerPosition)) {
            if (search == null) return original.call(contraption, level, anchor, forced);
            return search.run(() -> original.call(contraption, level, anchor, forced));
        }
    }
}
