package com.eynny.npships.mixin;

import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;

import net.createmod.catnip.animation.LerpedFloat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AnalogLeverBlockEntity.class)
public interface NPShipsAnalogLeverAccessor {
    @Accessor("clientState")
    LerpedFloat npships$clientState();
}
