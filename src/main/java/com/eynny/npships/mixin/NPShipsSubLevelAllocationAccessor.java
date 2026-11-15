package com.eynny.npships.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;

import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses native plot selection so the owner can reserve its UUID before allocation callbacks. */
@Mixin(value = SubLevelContainer.class, remap = false)
public interface NPShipsSubLevelAllocationAccessor {
    @Invoker("getFirstEmptyPlot")
    Vector2i npships$firstEmptyPlot();
}
