package com.eynny.npships.mixin;

import com.eynny.npships.ship.SpawnRegionGuard;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native accessibility loss retires already-read spawn chunks immediately. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class NPShipsSpawnRegionVisibilityMixin {
    @Inject(
            method =
                    "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
            at = @At("RETURN"),
            require = 1)
    private void npships$status(ChunkPos chunk, FullChunkStatus status, CallbackInfo ci) {
        SpawnRegionGuard.visibilityChanged(this, chunk, status);
    }
}
