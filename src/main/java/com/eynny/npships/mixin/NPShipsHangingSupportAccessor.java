package com.eynny.npships.mixin;

import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Ask the installed native entity for its exact support footprint without duplicating geometry. */
@Mixin(HangingEntity.class)
public interface NPShipsHangingSupportAccessor {
    @Invoker("calculateSupportBox")
    AABB npships$supportBox();
}
