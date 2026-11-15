package com.eynny.npships.mixin;

import com.eynny.npships.ship.DetachedAssemblySource;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.simulated_team.simulated.util.assembly.SimAssemblyContraption;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Direct spawn has its own resumable work budget; ordinary assemblers keep their size cap. */
@Mixin(value = SimAssemblyContraption.class, remap = false)
public abstract class NPShipsDetachedAssemblyLimitMixin {
    @ModifyExpressionValue(
            method = "moveBlock",
            at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"),
            remap = false,
            require = 1)
    private int npships$directLimit(int configured, @Local(argsOnly = true) Level world) {
        return world instanceof DetachedAssemblySource ? Integer.MAX_VALUE : configured;
    }
}
