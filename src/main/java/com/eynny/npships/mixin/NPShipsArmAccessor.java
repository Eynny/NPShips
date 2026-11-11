package com.eynny.npships.mixin;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ArmBlockEntity.class)
public interface NPShipsArmAccessor {
    @Accessor("updateInteractionPoints")
    void npships$updateInteractionPoints(boolean value);
}
