package com.eynny.npships.mixin;

import com.eynny.npships.ship.ConstructionProtection;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keep native transport/removal ordering; only the exact pending owner's destroy predicate changes.
 */
@Mixin(value = ServerLevelPlot.class, remap = false)
public abstract class NPShipsConstructionEntityCleanupMixin {
    @ModifyExpressionValue(
            method = "kickAllEntities",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/EntityType;is(Lnet/minecraft/tags/TagKey;)Z",
                            remap = true),
            remap = false,
            require = 1)
    private boolean npships$preserveForeign(boolean nativeDecision, @Local Entity entity) {
        return ConstructionProtection.destroyWithBody(
                ((ServerLevelPlot) (Object) this).getSubLevel(), entity, nativeDecision);
    }
}
