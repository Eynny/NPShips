package com.eynny.npships.mixin;

import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read the native virtual jump calculation without moving or ticking the entity. */
@Mixin(LivingEntity.class)
public interface NPShipsMobJumpAccessor {
    @Invoker("getJumpPower")
    float npships$jumpPower();
}
