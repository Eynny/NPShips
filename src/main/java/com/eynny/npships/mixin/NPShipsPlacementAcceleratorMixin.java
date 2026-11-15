package com.eynny.npships.mixin;

import com.eynny.npships.integration.NativeBlockAccess;

import dev.ryanhcode.sable.util.LevelAccelerator;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Qualify the native result only during an NPShips-owned operation. The original helper executes
 * once, retaining its own cache/side effects; an already-correct result is returned unchanged.
 */
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class NPShipsPlacementAcceleratorMixin {
    @Unique private Level npships$level;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/Level;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1)
    private void npships$captureLevel(Level level, CallbackInfo ci) {
        npships$level = level;
    }

    @Inject(
            method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 1)
    private void npships$currentNativeChunk(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (npships$level instanceof ServerLevel server) {
            var chunk = NativeBlockAccess.scopedChunk(server, x, z);
            if (chunk != null && chunk != cir.getReturnValue()) cir.setReturnValue(chunk);
        }
    }
}
