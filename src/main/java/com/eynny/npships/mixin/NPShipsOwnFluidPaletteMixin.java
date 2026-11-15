package com.eynny.npships.mixin;

import com.eynny.npships.ship.DirectFluidProvenance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Restrict fluid authority to the native chunk's immediate section write. */
@Mixin(LevelChunk.class)
public abstract class NPShipsOwnFluidPaletteMixin {
    @WrapOperation(
            method = "setBlockState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1)
    private BlockState npships$exactPalette(
            LevelChunkSection section,
            int x,
            int y,
            int z,
            BlockState state,
            Operation<BlockState> original) {
        try (var scope = DirectFluidProvenance.palette(section, x, y, z, state)) {
            return original.call(section, x, y, z, state);
        }
    }
}
