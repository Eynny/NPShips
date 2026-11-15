package com.eynny.npships.mixin;

import com.eynny.npships.ship.SpawnRegionGuard;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual palette write, including writers that bypass Level.setBlock. */
@Mixin(LevelChunkSection.class)
public abstract class NPShipsSpawnRegionSectionMixin
        implements com.eynny.npships.ship.DirectFluidProvenance.WatchedSection {
    @org.spongepowered.asm.mixin.Unique
    private com.eynny.npships.ship.DirectFluidProvenance.Watch npships$fluidWatch;

    public com.eynny.npships.ship.DirectFluidProvenance.Watch npships$fluidWatch() {
        return npships$fluidWatch;
    }

    public void npships$fluidWatch(com.eynny.npships.ship.DirectFluidProvenance.Watch watch) {
        npships$fluidWatch = watch;
    }

    @Inject(
            method =
                    "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"),
            require = 1)
    private void npships$changed(
            int x,
            int y,
            int z,
            BlockState state,
            boolean lock,
            CallbackInfoReturnable<BlockState> cir) {
        com.eynny.npships.ship.DirectFluidProvenance.changed(
                (LevelChunkSection) (Object) this, x, y, z, cir.getReturnValue(), state);
        SpawnRegionGuard.sectionChanged(
                (LevelChunkSection) (Object) this, x, y, z, cir.getReturnValue(), state);
    }
}
