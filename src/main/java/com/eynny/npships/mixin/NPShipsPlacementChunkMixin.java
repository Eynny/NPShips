package com.eynny.npships.mixin;

import com.eynny.npships.integration.NativeBlockAccess;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A patch callback reads its captured plot chunk, including native code called by that callback.
 */
@Mixin(ServerChunkCache.class)
public abstract class NPShipsPlacementChunkMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(
            method =
                    "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void npships$currentPlacementChunk(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (status != ChunkStatus.FULL) return;
        var chunk = NativeBlockAccess.scopedChunk(level, x, z);
        if (chunk != null) cir.setReturnValue(chunk);
    }
}
