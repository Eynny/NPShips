package com.eynny.npships.mixin;

import com.eynny.npships.integration.aeronautics.InitialSpringLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.spring.SpringBlock", remap = false)
public abstract class NPShipsInitialSpringMovementMixin {
    @Inject(method = "afterMove", at = @At("RETURN"), require = 1, remap = false)
    private void npships$moveInitialReference(
            ServerLevel oldLevel,
            ServerLevel newLevel,
            BlockState state,
            BlockPos oldPos,
            BlockPos newPos,
            CallbackInfo ci) {
        InitialSpringLinks.moved(oldLevel.getBlockEntity(oldPos), newLevel.getBlockEntity(newPos));
    }
}
