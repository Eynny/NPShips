package com.eynny.npships.mixin;

import com.eynny.npships.integration.NativeBlockAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Failed native insertion stays missing; a read during restoration must not manufacture a BE. */
@Mixin(Level.class)
public abstract class NPShipsPlacementEntityReadMixin {
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void npships$existingPlacementEntity(
            BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if ((Object) this instanceof ServerLevel level) {
            var chunk = NativeBlockAccess.scopedExistingChunk(level, pos);
            if (chunk != null) cir.setReturnValue(NativeBlockAccess.existing(level, chunk, pos));
        }
    }
}
