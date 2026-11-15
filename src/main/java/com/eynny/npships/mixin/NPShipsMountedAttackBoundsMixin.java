package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableMountedCombat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;

/** Keep the native reach calculation; normalize its demonstrated mixed-frame vehicle union. */
@Mixin(Mob.class)
public abstract class NPShipsMountedAttackBoundsMixin {
    @WrapMethod(method = "getAttackBoundingBox", require = 0)
    private AABB npships$mountedAttackBounds(Operation<AABB> original) {
        return SableMountedCombat.bounds((Mob) (Object) this, original.call());
    }
}
