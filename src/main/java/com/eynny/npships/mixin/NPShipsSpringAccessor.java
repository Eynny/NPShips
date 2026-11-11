package com.eynny.npships.mixin;

import net.createmod.catnip.animation.LerpedFloat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Pseudo
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity",
        remap = false)
public interface NPShipsSpringAccessor {
    @Accessor("renderLength")
    LerpedFloat npships$renderLength();
}
